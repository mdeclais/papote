package io.github.mdeclais.papote

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

object Timecode {
    val frameRates = listOf(24, 25, 30, 50, 60)

    /** Secondes écoulées depuis minuit, heure locale, pour un instant en millisecondes (fractionnaires). */
    fun secondsSinceMidnight(epochMs: Double, zone: ZoneId = ZoneId.systemDefault()): Double {
        val day = Instant.ofEpochMilli(floor(epochMs).toLong()).atZone(zone).toLocalDate()
        val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return (epochMs - midnight) / 1000.0
    }

    /** HH:MM:SS:FF (non drop-frame), repli sur 24 h. */
    fun string(seconds: Double, fps: Int): String {
        var s = seconds % 86_400.0
        if (s < 0) s += 86_400.0
        val total = floor(s * fps + 1e-6).toLong()
        val secs = total / fps
        return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60, total % fps)
    }

    /** HH:MM:SS,mmm pour les sous-titres SRT. */
    fun srt(seconds: Double): String {
        val ms = (max(0.0, seconds) * 1000).roundToLong()
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
    }

    fun duration(seconds: Double): String {
        val s = max(0.0, seconds).toLong()
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}
