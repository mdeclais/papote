package io.github.mdeclais.papote

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WAV « Broadcast Wave » 16 ou 24 bits, mono ou stéréo. Le chunk bext porte
 * TimeReference = échantillons écoulés depuis minuit : DaVinci Resolve le lit
 * comme timecode source du clip.
 *
 * Les tailles d'en-tête sont remises à jour toutes les ~5 s, pour qu'un
 * fichier reste lisible même si l'app est tuée en plein enregistrement.
 */
class BwfWriter(
    file: File,
    sampleRate: Int,
    private val channels: Int,
    bitDepth: Int,
    timeReference: Long,
    dateMs: Long,
    description: String,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val raf = RandomAccessFile(file, "rw")
    /** Octets par échantillon : 2 (16 bits) ou 3 (24 bits). */
    private val bytes = if (bitDepth == 16) 2 else 3
    private val flushEvery = sampleRate * bytes * channels * 5
    private var dataBytes = 0L
    private var sinceFlush = 0
    var frames = 0L
        private set

    init {
        raf.setLength(0)
        val at = Instant.ofEpochMilli(dateMs).atZone(zone)
        val h = Bytes()
        h.ascii("RIFF"); h.le32(0); h.ascii("WAVE")

        h.ascii("bext"); h.le32(BEXT_SIZE.toLong())
        h.fixed(description, 256)
        h.fixed("Papote (Android)", 32)                         // Originator
        h.fixed("", 32)                                         // OriginatorReference
        h.fixed(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(at), 10)
        h.fixed(DateTimeFormatter.ofPattern("HH:mm:ss").format(at), 8)
        h.le32(timeReference and 0xFFFF_FFFFL)
        h.le32(timeReference ushr 32)
        h.le16(1)                                               // Version
        h.zeros(64 + 190)                                       // UMID + réservé

        h.ascii("fmt "); h.le32(16)
        h.le16(1)                                               // PCM
        h.le16(channels)
        h.le32(sampleRate.toLong())
        h.le32(sampleRate.toLong() * bytes * channels)          // octets / s
        h.le16(bytes * channels)                                // block align
        h.le16(bytes * 8)

        h.ascii("data"); h.le32(0)
        check(h.size == HEADER_SIZE)
        raf.write(h.toByteArray())
    }

    /**
     * Float [-1, 1] entrelacés sur `inChannels` → PCM entier little-endian.
     * Entrée mono et fichier stéréo : on duplique le canal.
     */
    fun encode(samples: FloatArray, frameCount: Int, inChannels: Int): ByteArray {
        val scale = if (bytes == 2) 32_767f else 8_388_607f
        val out = ByteArray(frameCount * channels * bytes)
        var o = 0
        for (i in 0 until frameCount) {
            for (c in 0 until channels) {
                val sample = samples[i * inChannels + min(c, inChannels - 1)]
                val v = (max(-1f, min(1f, sample)) * scale).roundToInt()
                out[o] = v.toByte()
                out[o + 1] = (v shr 8).toByte()
                if (bytes == 3) out[o + 2] = (v shr 16).toByte()
                o += bytes
            }
        }
        return out
    }

    fun append(pcm: ByteArray) {
        raf.write(pcm)
        dataBytes += pcm.size
        frames += pcm.size / (bytes * channels)
        sinceFlush += pcm.size
        if (sinceFlush >= flushEvery) {
            patchSizes(0)
            sinceFlush = 0
        }
    }

    fun close() {
        // RIFF impose un nombre pair d'octets par chunk.
        val pad = dataBytes % 2
        if (pad == 1L) raf.write(0)
        patchSizes(pad)
        raf.fd.sync()
        raf.close()
    }

    private fun patchSizes(pad: Long) {
        val end = raf.filePointer
        raf.seek(4)
        raf.write(le32Bytes(min(HEADER_SIZE - 8 + dataBytes + pad, 0xFFFF_FFFFL)))
        raf.seek((HEADER_SIZE - 4).toLong())
        raf.write(le32Bytes(min(dataBytes, 0xFFFF_FFFFL)))
        raf.seek(end)
    }

    companion object {
        const val BEXT_SIZE = 602
        const val HEADER_SIZE = 12 + (8 + BEXT_SIZE) + (8 + 16) + 8

        /**
         * Remet les tailles d'en-tête d'un WAV coupé net (plantage, batterie) en
         * accord avec ce qui est réellement sur le disque ; renvoie le nombre d'échantillons.
         */
        fun repair(file: File): Long? {
            if (!file.exists() || file.length() < HEADER_SIZE) return null
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek((12 + 8 + BEXT_SIZE + 8 + 12).toLong())  // block align du chunk fmt
                val lo = raf.read(); val hi = raf.read()
                if (lo < 0 || hi < 0) return null
                val blockAlign = max(1, lo or (hi shl 8)).toLong()
                var dataBytes = raf.length() - HEADER_SIZE
                dataBytes -= dataBytes % blockAlign
                raf.seek(4)
                raf.write(le32Bytes(min(HEADER_SIZE - 8 + dataBytes + dataBytes % 2, 0xFFFF_FFFFL)))
                raf.seek((HEADER_SIZE - 4).toLong())
                raf.write(le32Bytes(min(dataBytes, 0xFFFF_FFFFL)))
                raf.setLength(HEADER_SIZE + dataBytes)
                if (dataBytes % 2 == 1L) {  // octet de bourrage RIFF
                    raf.seek(raf.length())
                    raf.write(0)
                }
                return dataBytes / blockAlign
            }
        }

        private fun le32Bytes(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }

    private class Bytes {
        private val buf = java.io.ByteArrayOutputStream()
        val size get() = buf.size()
        fun ascii(s: String) = buf.write(s.toByteArray(Charsets.US_ASCII))
        fun fixed(s: String, length: Int) {
            val b = s.toByteArray(Charsets.UTF_8).take(length).toByteArray()
            buf.write(b); zeros(length - b.size)
        }
        fun zeros(n: Int) = buf.write(ByteArray(n))
        fun le16(v: Int) { buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF) }
        fun le32(v: Long) = buf.write(le32Bytes(v))
        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}
