package io.github.mdeclais.papote

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** Sous-titres, texte et marqueurs : les mêmes fichiers que l'app iPhone. */
object Exports {
    /**
     * Temps absolus, alignés sur le TC de la timeline : 3600 = timeline à
     * 01:00:00:00 avec le WAV posé au début, null = heure du jour du WAV.
     */
    fun srt(s: Session, timelineStart: Double?, speakerNames: Boolean): String {
        val base = timelineStart ?: s.startTOD
        val out = StringBuilder()
        s.segments.forEachIndexed { i, seg ->
            val end = max(seg.end, seg.start + 0.5)
            val name = if (speakerNames) s.speakerName(seg.speaker)?.let { "$it : " } ?: "" else ""
            out.append("${i + 1}\n")
            out.append("${Timecode.srt(base + seg.start)} --> ${Timecode.srt(base + end)}\n")
            out.append("$name${seg.text}\n\n")
        }
        return out.toString()
    }

    fun text(s: Session, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
            .withLocale(locale).format(Instant.ofEpochMilli(s.startDateMs).atZone(zone))
        val out = StringBuilder("Interview ${s.id}\n")
        s.project?.let { out.append("Projet : $it\n") }
        out.append("$date\n")
        out.append("Début ${s.tc(0.0)} · fin ${s.tc(s.duration)} · ${s.fps} i/s · ${s.localeID}\n\n")
        for (item in s.items) {
            when (item) {
                is TranscriptItem.Seg -> {
                    val seg = item.segment
                    val name = s.speakerName(seg.speaker)?.let { "$it : " } ?: ""
                    out.append("[${s.tc(seg.start)}] $name${seg.text}\n")
                }
                is TranscriptItem.Mark -> {
                    val m = item.marker
                    if (m.duration == null) out.append("[${s.tc(m.time)}] ★ ${m.name}\n")
                    else out.append(
                        "[${s.tc(m.time)} → ${s.tc(m.time + s.frames(m).toDouble() / s.fps)}] ★ ${m.name} (${Timecode.duration(m.duration)})\n"
                    )
                }
            }
        }
        return out.toString()
    }

    /**
     * Marqueurs au format EDL de Resolve, avec leur durée (|D: en images) :
     * Timeline > Import > Timeline Markers from EDL. La cadence de l'app doit être celle de la timeline.
     */
    fun edl(s: Session, timelineStart: Double?): String {
        val out = StringBuilder("TITLE: ${s.id}\nFCM: NON-DROP FRAME\n\n")
        val fps = s.fps.toDouble()
        s.markers.sortedBy { it.time }.forEachIndexed { i, m ->
            // Fin calculée en images entières depuis l'entrée, pour que IN/OUT et |D: concordent.
            val inFrames = floor(((timelineStart ?: s.startTOD) + m.time) * fps + 1e-6)
            val d = s.frames(m)
            val a = Timecode.string(inFrames / fps, s.fps)
            val b = Timecode.string((inFrames + d) / fps, s.fps)
            out.append(String.format(Locale.ROOT, "%03d  001      V     C        ", i + 1))
            out.append("$a $b $a $b  \n")
            out.append(" |C:ResolveColorBlue |M:${m.name} |D:$d\n\n")
        }
        return out.toString()
    }
}
