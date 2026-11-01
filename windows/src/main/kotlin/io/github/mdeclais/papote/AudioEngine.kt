package io.github.mdeclais.papote

import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sqrt

data class AudioInput(val id: String, val name: String)

/**
 * Capture du micro (Java Sound) → WAV BWF horodaté, et en parallèle → Vosk.
 * Les rappels arrivent sur les threads audio : à renvoyer sur le thread de l'interface.
 */
class AudioEngine(
    private val audioFile: File,
    private val description: String,
    private val wantedChannels: Int,
    private val bitDepth: Int,
    private val inputId: String,
    private val model: VoskModel?,
) {
    data class StartInfo(val dateMs: Long, val sampleRate: Int, val channels: Int, val timeReference: Long)

    var onStart: (StartInfo) -> Unit = {}
    var onLevel: (Float) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onWords: (List<Word>) -> Unit = {}
    var onPartial: (String) -> Unit = {}

    private var line: TargetDataLine? = null
    private var writer: BwfWriter? = null
    private var captureThread: Thread? = null
    private var speechThread: Thread? = null
    private val speechQueue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = false
    private var sampleRate = 48_000

    fun start() {
        val l = open()
        sampleRate = l.format.sampleRate.toInt()
        l.start()
        line = l
        running = true
        if (model != null) {
            speechThread = Thread({ speechLoop(VoskRecognizer(model, 16_000f)) }, "papote-speech").apply { start() }
        }
        captureThread = Thread({ captureLoop(l) }, "papote-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Arrête et ferme les fichiers ; renvoie la durée enregistrée en secondes. */
    fun stop(): Double {
        running = false
        line?.run { runCatching { stop() } }
        captureThread?.join(3000)
        line?.close()
        line = null
        val frames = writer?.frames ?: 0
        writer?.close()
        writer = null
        speechQueue.put(ShortArray(0))  // fin de flux
        speechThread?.join(10_000)
        return frames.toDouble() / sampleRate
    }

    /** Essaie 48 kHz puis 44,1 kHz, la stéréo demandée puis le mono, 24 bits si demandé puis 16. */
    private fun open(): TargetDataLine {
        // Entrée débranchée depuis : on retombe sur l'entrée par défaut.
        val mixer = inputId.takeIf { it.isNotEmpty() }?.let { id -> mixerInfos().firstOrNull { it.name == id } }
        val sizes = if (bitDepth == 24) listOf(24, 16) else listOf(16)
        val channelOptions = if (wantedChannels == 2) listOf(2, 1) else listOf(1)
        for (rate in listOf(48_000f, 44_100f)) for (ch in channelOptions) for (bits in sizes) {
            val format = AudioFormat(rate, bits, ch, true, false)
            val l = runCatching {
                (if (mixer != null) AudioSystem.getTargetDataLine(format, mixer) else AudioSystem.getTargetDataLine(format))
                    .apply { open(format, format.frameSize * rate.toInt() / 2) }
            }.getOrNull() ?: continue
            return l
        }
        throw IllegalStateException("Impossible d'ouvrir le micro. Vérifie qu'il est branché et autorisé dans Paramètres > Confidentialité > Microphone.")
    }

    private fun captureLoop(l: TargetDataLine) {
        val fmt = l.format
        val channels = fmt.channels
        val bytesPerSample = fmt.sampleSizeInBits / 8
        val frameSize = fmt.frameSize
        val chunkFrames = sampleRate / 10
        val bytes = ByteArray(chunkFrames * frameSize)
        val buf = FloatArray(chunkFrames * channels)
        val mono = FloatArray(chunkFrames)
        val down = Downsampler(sampleRate)
        var framesRead = 0L
        var lastLevel = 0L
        try {
            while (running) {
                val n = l.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                val frames = n / frameSize
                decode(bytes, frames * channels, bytesPerSample, buf)
                framesRead += frames
                if (writer == null) begin(l, channels, framesRead)
                val w = writer!!
                w.append(w.encode(buf, frames, channels))

                var sum = 0.0
                for (i in 0 until frames) {
                    var v = 0f
                    for (c in 0 until channels) v += buf[i * channels + c]
                    v /= channels
                    mono[i] = v
                    sum += v * v
                }
                val now = System.currentTimeMillis()
                if (now - lastLevel > 80) {
                    lastLevel = now
                    val db = 20 * log10(sqrt(sum / frames).coerceAtLeast(1e-6))
                    onLevel(((db + 60) / 60).toFloat().coerceIn(0f, 1f))
                }
                if (model != null) speechQueue.put(down.process(mono, frames))
            }
        } catch (t: Throwable) {
            onError("Erreur d'enregistrement : ${t.message}")
        }
    }

    /** PCM entier signé little-endian → flottants -1…1. */
    private fun decode(bytes: ByteArray, samples: Int, bytesPerSample: Int, out: FloatArray) {
        var p = 0
        for (i in 0 until samples) {
            out[i] = if (bytesPerSample == 3) {
                val v = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)
                v / 8_388_608f
            } else {
                ((bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8)) / 32_768f
            }
            p += bytesPerSample
        }
    }

    /**
     * Premier tampon reçu : l'échantillon 0 date de « maintenant » moins tout
     * ce que la carte son a capturé depuis (lu + encore en attente). Java Sound
     * ne donne pas d'horodatage matériel ; l'écart reste de l'ordre de 10 ms.
     */
    private fun begin(l: TargetDataLine, channels: Int, framesRead: Long) {
        val nowMs = System.currentTimeMillis().toDouble()
        val captured = framesRead + l.available() / l.format.frameSize
        val startMs = nowMs - captured * 1000.0 / sampleRate
        val timeReference = (Timecode.secondsSinceMidnight(startMs) * sampleRate).roundToLong()
        writer = BwfWriter(audioFile, sampleRate, channels, bitDepth, timeReference, startMs.toLong(), description)
        onStart(StartInfo(startMs.toLong(), sampleRate, channels, timeReference))
    }

    private fun speechLoop(rec: VoskRecognizer) {
        var lastPartial = ""
        try {
            while (true) {
                val chunk = speechQueue.poll(1, TimeUnit.SECONDS) ?: if (running) continue else break
                if (chunk.isEmpty()) break
                if (rec.accept(chunk)) {
                    lastPartial = ""
                    onPartial("")
                    val words = CueBuilder.words(rec.result)
                    if (words.isNotEmpty()) onWords(words)
                } else {
                    val partial = JSONObject(rec.partialResult).optString("partial")
                    if (partial != lastPartial) { lastPartial = partial; onPartial(partial) }
                }
            }
            val words = CueBuilder.words(rec.finalResult)
            onPartial("")
            if (words.isNotEmpty()) onWords(words)
        } catch (t: Throwable) {
            onError("Transcription interrompue : ${t.message}")
        } finally {
            rec.close()
        }
    }

    companion object {
        private fun mixerInfos(): List<Mixer.Info> = AudioSystem.getMixerInfo().filter { info ->
            !info.name.startsWith("Port ") &&
                AudioSystem.getMixer(info).targetLineInfo.any { it is DataLine.Info && TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
        }

        /** Entrées audio du système. « Primary Sound Capture Driver » est l'entrée par défaut, déjà proposée à part. */
        fun inputs(): List<AudioInput> = mixerInfos()
            .filterNot { it.name.startsWith("Primary Sound Capture") || it.name == "Default Audio Device" }
            .map { AudioInput(it.name, it.name) }
            .distinctBy { it.id }
    }
}
