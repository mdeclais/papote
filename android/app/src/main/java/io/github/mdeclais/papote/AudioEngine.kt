package io.github.mdeclais.papote

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Capture du micro → WAV BWF horodaté, et en parallèle → Vosk pour la transcription.
 * Les rappels arrivent sur les threads audio : à renvoyer sur le thread principal.
 */
class AudioEngine(
    private val audioFile: File,
    private val description: String,
    private val wantedChannels: Int,
    private val bitDepth: Int,
    private val device: AudioDeviceInfo?,
    private val model: Model?,
) {
    data class StartInfo(val dateMs: Long, val sampleRate: Int, val channels: Int, val timeReference: Long)

    var onStart: (StartInfo) -> Unit = {}
    var onLevel: (Float) -> Unit = {}
    var onError: (String) -> Unit = {}
    /** Mots d'une phrase terminée. */
    var onWords: (List<Word>) -> Unit = {}
    /** Texte provisoire de la phrase en cours. */
    var onPartial: (String) -> Unit = {}

    private var record: AudioRecord? = null
    private var writer: BwfWriter? = null
    private var captureThread: Thread? = null
    private var speechThread: Thread? = null
    private val speechQueue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = false
    private var sampleRate = 48_000

    @SuppressLint("MissingPermission")
    fun start() {
        val (rec, rate) = open()
        sampleRate = rate
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("Le micro est déjà utilisé par une autre app.")
        }
        record = rec
        running = true
        if (model != null) {
            speechThread = Thread({ speechLoop(Recognizer(model, 16_000f).apply { setWords(true) }) }, "papote-speech").apply { start() }
        }
        captureThread = Thread({ captureLoop(rec) }, "papote-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Arrête et ferme les fichiers ; renvoie la durée enregistrée en secondes. */
    fun stop(): Double {
        running = false
        captureThread?.join(3000)
        record?.run { runCatching { stop() }; release() }
        record = null
        val frames = writer?.frames ?: 0
        writer?.close()
        writer = null
        speechQueue.put(ShortArray(0))  // fin de flux
        speechThread?.join(10_000)
        return frames.toDouble() / sampleRate
    }

    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, Int> {
        val mask = if (wantedChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        // CAMCORDER : la source qui donne la stéréo sur les micros intégrés.
        val source = if (wantedChannels == 2) MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.MIC
        for (rate in listOf(48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
            if (min <= 0) continue
            val rec = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(maxOf(min * 4, rate * 4 * 2 / 2))
                    .build()
            }.getOrNull() ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            device?.let { rec.preferredDevice = it }
            return rec to rate
        }
        throw IllegalStateException("Impossible d'ouvrir le micro.")
    }

    private fun captureLoop(rec: AudioRecord) {
        val channels = rec.channelCount
        val chunkFrames = sampleRate / 10
        val buf = FloatArray(chunkFrames * channels)
        val mono = FloatArray(chunkFrames)
        val down = Downsampler(sampleRate)
        var framesRead = 0L
        var lastLevel = 0L
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) { onError("Erreur du micro ($n). Enregistrement arrêté."); break }
                if (n == 0) continue
                val frames = n / channels
                if (writer == null) begin(rec, channels, framesRead)
                val w = writer!!
                w.append(w.encode(buf, frames, channels))
                framesRead += frames

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

    /**
     * Premier tampon reçu : on date l'échantillon 0 avec l'horloge du système
     * audio, puis on crée le WAV avec TimeReference = échantillons depuis minuit.
     */
    private fun begin(rec: AudioRecord, channels: Int, framesBefore: Long) {
        val ts = AudioTimestamp()
        val nowNano = System.nanoTime()
        val nowMs = System.currentTimeMillis().toDouble()
        val frame0Nano = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            ts.nanoTime - ts.framePosition * 1_000_000_000.0 / sampleRate
        } else {
            nowNano - (framesBefore + sampleRate / 10) * 1_000_000_000.0 / sampleRate
        }
        val startMs = nowMs - (nowNano - frame0Nano) / 1_000_000.0
        val timeReference = (Timecode.secondsSinceMidnight(startMs) * sampleRate).roundToLong()
        writer = BwfWriter(audioFile, sampleRate, channels, bitDepth, timeReference, startMs.toLong(), description)
        onStart(StartInfo(startMs.toLong(), sampleRate, channels, timeReference))
    }

    private fun speechLoop(rec: Recognizer) {
        var lastPartial = ""
        try {
            while (true) {
                val chunk = speechQueue.poll(1, TimeUnit.SECONDS) ?: if (running) continue else break
                if (chunk.isEmpty()) break
                if (rec.acceptWaveForm(chunk, chunk.size)) {
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
}
