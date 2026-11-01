package io.github.mdeclais.papote

/**
 * Ramène le flux mono à 16 kHz pour Vosk en moyennant les échantillons de
 * chaque intervalle de sortie (filtre passe-bas grossier, suffisant pour la voix).
 */
class Downsampler(inRate: Int, outRate: Int = 16_000) {
    private val step = inRate.toDouble() / outRate
    private var acc = 0.0
    private var count = 0
    private var pos = 0.0

    fun process(mono: FloatArray, n: Int): ShortArray {
        val out = ShortArray((n / step).toInt() + 2)
        var o = 0
        for (i in 0 until n) {
            acc += mono[i]
            count++
            pos += 1.0
            if (pos >= step) {
                val v = (acc / count).coerceIn(-1.0, 1.0)
                out[o++] = (v * 32_767).toInt().toShort()
                acc = 0.0; count = 0; pos -= step
            }
        }
        return out.copyOf(o)
    }
}
