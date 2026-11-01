package io.github.mdeclais.papote

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class Segment(
    val id: String = UUID.randomUUID().toString(),
    /** Secondes depuis le premier échantillon enregistré. */
    val start: Double,
    val end: Double,
    val text: String,
    /** Index dans [Session.speakers], null si les locuteurs sont désactivés. */
    val speaker: Int? = null,
)

/** Extrait posé avec IN / OUT. `duration` null : marqueur ponctuel. */
data class Marker(
    val id: String = UUID.randomUUID().toString(),
    val time: Double,
    val name: String,
    val duration: Double? = null,
)

/** Ligne affichée dans une transcription : un sous-titre ou un marqueur, triés par temps. */
sealed interface TranscriptItem {
    val id: String
    val time: Double

    data class Seg(val segment: Segment) : TranscriptItem {
        override val id get() = segment.id
        override val time get() = segment.start
    }

    data class Mark(val marker: Marker) : TranscriptItem {
        override val id get() = marker.id
        override val time get() = marker.time
    }
}

/** Même format de session.json que l'app iPhone. */
data class Session(
    val id: String,
    val startDateMs: Long,
    /** Heure du premier échantillon, en secondes depuis minuit : exactement TimeReference / sampleRate du WAV. */
    val startTOD: Double,
    val sampleRate: Double,
    val fps: Int,
    val localeID: String,
    val duration: Double = 0.0,
    val segments: List<Segment> = emptyList(),
    val project: String? = null,
    val speakers: List<String>? = null,
    val markers: List<Marker> = emptyList(),
    val channels: Int = 1,
    val bitDepth: Int = 16,
    /** true une fois l'enregistrement arrêté normalement. */
    val finished: Boolean = false,
    /** true tant que la copie vers le dossier de sauvegarde n'a pas réussi. */
    val backupPending: Boolean = false,
) {
    fun tc(offset: Double) = Timecode.string(startTOD + offset, fps)

    /** Durée d'un marqueur en images, au moins 1. */
    fun frames(m: Marker) = max(1, ((m.duration ?: 0.0) * fps).roundToInt())

    fun speakerName(index: Int?): String? = index?.let { speakers?.getOrNull(it) }

    val items: List<TranscriptItem>
        get() = (segments.map { TranscriptItem.Seg(it) } + markers.map { TranscriptItem.Mark(it) })
            .sortedBy { it.time }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startDate", Instant.ofEpochMilli(startDateMs).toString())
        put("startTOD", startTOD)
        put("sampleRate", sampleRate)
        put("fps", fps)
        put("localeID", localeID)
        put("duration", duration)
        put("segments", JSONArray(segments.map { s ->
            JSONObject().apply {
                put("id", s.id); put("start", s.start); put("end", s.end); put("text", s.text)
                s.speaker?.let { put("speaker", it) }
            }
        }))
        project?.let { put("project", it) }
        speakers?.let { put("speakers", JSONArray(it)) }
        put("markers", JSONArray(markers.map { m ->
            JSONObject().apply {
                put("id", m.id); put("time", m.time); put("name", m.name)
                m.duration?.let { put("duration", it) }
            }
        }))
        put("channels", channels)
        put("bitDepth", bitDepth)
        put("finished", finished)
        put("backupPending", backupPending)
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val segs = o.optJSONArray("segments") ?: JSONArray()
            val marks = o.optJSONArray("markers") ?: JSONArray()
            val spk = o.optJSONArray("speakers")
            return Session(
                id = o.getString("id"),
                startDateMs = Instant.parse(o.getString("startDate")).toEpochMilli(),
                startTOD = o.getDouble("startTOD"),
                sampleRate = o.getDouble("sampleRate"),
                fps = o.getInt("fps"),
                localeID = o.optString("localeID", "fr-FR"),
                duration = o.optDouble("duration", 0.0),
                segments = (0 until segs.length()).map { i ->
                    val s = segs.getJSONObject(i)
                    Segment(
                        id = s.optString("id", UUID.randomUUID().toString()),
                        start = s.getDouble("start"), end = s.getDouble("end"), text = s.getString("text"),
                        speaker = if (s.has("speaker") && !s.isNull("speaker")) s.getInt("speaker") else null,
                    )
                },
                project = o.optString("project").takeIf { o.has("project") && it.isNotEmpty() },
                speakers = spk?.let { a -> (0 until a.length()).map { a.getString(it) } },
                markers = (0 until marks.length()).map { i ->
                    val m = marks.getJSONObject(i)
                    Marker(
                        id = m.optString("id", UUID.randomUUID().toString()),
                        time = m.getDouble("time"), name = m.getString("name"),
                        duration = if (m.has("duration") && !m.isNull("duration")) m.getDouble("duration") else null,
                    )
                },
                channels = o.optInt("channels", 1),
                bitDepth = o.optInt("bitDepth", 16),
                finished = o.optBoolean("finished", true),
                backupPending = o.optBoolean("backupPending", false),
            )
        }
    }
}
