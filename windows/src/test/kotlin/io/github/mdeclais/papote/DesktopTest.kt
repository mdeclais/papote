package io.github.mdeclais.papote

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioSystem

class DesktopTest {
    private fun render(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = File(System.getProperty("papote.screens")).apply { mkdirs() }
        ImageComposeScene(480, 860, Density(1f)) { PapoteTheme { content() } }.use { scene ->
            scene.render()  // première image : mise en page
            val png = scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(dir, "$name.png").writeBytes(png)
        }
    }

    @Test fun screens() {
        Recorder.setGuestCount(3)
        render("main") { MainScreen(onSessions = {}, onSettings = {}) }
        render("settings") { SettingsScreen(onBack = {}) }
        render("sessions") { SessionsScreen(onBack = {}, onOpen = {}) }
        Recorder.setGuestCount(1)
    }

    /** Transcrit un WAV 16 kHz mono si on en fournit un (-Dpapote.speech=…, langue -Dpapote.speechLang=…). */
    @Test fun vosk() {
        val wav = File(System.getProperty("papote.speech").orEmpty())
        assumeTrue(wav.isFile)
        val lang = VoskModels.language(System.getProperty("papote.speechLang").orEmpty().ifEmpty { "fr-FR" })
        if (!VoskModels.isInstalled(Desktop.dataDir, lang)) VoskModels.download(Desktop.dataDir, lang) {}
        val words = mutableListOf<Word>()
        VoskModel(VoskModels.dir(Desktop.dataDir, lang).path).use { model ->
            VoskRecognizer(model, 16_000f).use { rec ->
                AudioSystem.getAudioInputStream(wav).use { input ->
                    val bytes = ByteArray(3200)
                    while (true) {
                        val n = input.read(bytes); if (n <= 0) break
                        val samples = ShortArray(n / 2) { ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
                        if (rec.accept(samples)) words += CueBuilder.words(rec.result)
                    }
                }
                words += CueBuilder.words(rec.finalResult)
            }
        }
        val cues = CueBuilder.cues(words)
        cues.forEach { println("CUE ${it.start}-${it.end} ${it.text}") }
        assertTrue(words.size > 5)
    }

    /** Trois secondes sur l'entrée par défaut : WAV lisible, TimeReference cohérent avec l'heure de départ. */
    @Test fun record() {
        assumeTrue(AudioSystem.getMixerInfo().isNotEmpty())
        val file = File.createTempFile("papote", ".wav")
        var info: AudioEngine.StartInfo? = null
        val engine = AudioEngine(file, "test", 1, 16, "", null).apply { onStart = { info = it } }
        val before = System.currentTimeMillis()
        // Machine sans carte son (serveur de compilation) : rien à tester.
        assumeTrue(runCatching { engine.start() }.isSuccess)
        Thread.sleep(3000)
        val duration = engine.stop()
        val i = info!!
        println("REC rate=${i.sampleRate} ch=${i.channels} duration=$duration startLag=${i.dateMs - before} ms bytes=${file.length()}")
        assertTrue(duration in 2.5..3.5)
        assertTrue(i.dateMs - before in -500..500)
        val tod = Timecode.secondsSinceMidnight(i.dateMs.toDouble())
        assertTrue(Math.abs(i.timeReference.toDouble() / i.sampleRate - tod) < 0.01)
        assertTrue(file.length() == BwfWriter.HEADER_SIZE + (duration * i.sampleRate).toLong() * 2)
        file.delete()
    }
}
