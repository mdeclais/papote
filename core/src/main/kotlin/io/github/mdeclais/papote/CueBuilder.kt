package io.github.mdeclais.papote

import org.json.JSONObject

/** Un mot reconnu par Vosk, avec ses temps en secondes depuis le début de l'audio. */
data class Word(val text: String, val start: Double, val end: Double)

/**
 * Découpe les mots reconnus en sous-titres lisibles : 84 caractères ou
 * 6 secondes au plus, coupure aussi sur un silence. Vosk ne ponctue pas :
 * chaque sous-titre commence par une majuscule.
 */
object CueBuilder {
    const val MAX_CHARS = 84
    const val MAX_DURATION = 6.0
    const val PAUSE = 0.8

    fun cues(words: List<Word>): List<Segment> {
        val cues = mutableListOf<Segment>()
        var current = mutableListOf<Word>()

        fun flush() {
            if (current.isEmpty()) return
            val text = current.joinToString(" ") { it.text }.trim()
            if (text.isNotEmpty()) {
                cues += Segment(start = current.first().start, end = current.last().end,
                                text = text.replaceFirstChar { it.titlecase() })
            }
            current = mutableListOf()
        }

        for ((i, w) in words.withIndex()) {
            if (current.isNotEmpty()) {
                val chars = current.sumOf { it.text.length + 1 } + w.text.length
                val duration = w.end - current.first().start
                // Dernier mot avant un silence : on tolère un léger dépassement plutôt que de le laisser seul.
                val next = words.getOrNull(i + 1)
                val endsPhrase = next == null || next.start - w.end > PAUSE
                val slack = if (endsPhrase) 1.25 else 1.0
                if (chars > MAX_CHARS * slack || duration > MAX_DURATION * slack || w.start - current.last().end > PAUSE) {
                    flush()
                }
            }
            current += w
        }
        flush()
        return cues
    }

    /** Résultat final de Vosk (`{"result":[{"word","start","end"}…],"text":…}`) → mots. */
    fun words(json: String): List<Word> {
        val o = JSONObject(json)
        val arr = o.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            Word(w.getString("word"), w.getDouble("start"), w.getDouble("end"))
        }
    }
}
