package io.github.mdeclais.papote

import android.app.Activity
import android.os.Bundle
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Debug : lit files/test.wav (16 kHz, 16 bits, mono), le passe dans Vosk par
 * blocs de 100 ms comme pendant un enregistrement, et écrit les sous-titres
 * obtenus dans files/test-result.txt.
 */
class TranscribeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val out = File(filesDir, "test-result.txt")
            out.writeText(runCatching {
                val bytes = File(filesDir, "test.wav").readBytes()
                val pcm = ShortArray((bytes.size - 44) / 2) { i ->
                    ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort()
                }
                val model = Model(VoskModels.dir(filesDir, VoskModels.language("fr-FR")).path)
                val rec = Recognizer(model, 16_000f).apply { setWords(true) }
                val words = mutableListOf<Word>()
                pcm.toList().chunked(1600).forEach { c ->
                    val chunk = c.toShortArray()
                    if (rec.acceptWaveForm(chunk, chunk.size)) words += CueBuilder.words(rec.result)
                }
                words += CueBuilder.words(rec.finalResult)
                rec.close(); model.close()
                CueBuilder.cues(words).joinToString("\n") { "%.2f-%.2f %s".format(it.start, it.end, it.text) }
            }.getOrElse { "ERREUR " + it.stackTraceToString() })
            finish()
        }.start()
    }
}
