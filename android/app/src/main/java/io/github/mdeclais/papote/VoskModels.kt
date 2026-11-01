package io.github.mdeclais.papote

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Langue de transcription et son modèle Vosk (téléchargé une fois, puis hors ligne). */
data class Language(val id: String, val name: String, val model: String, val sizeMb: Int)

object VoskModels {
    val languages = listOf(
        Language("fr-FR", "Français", "vosk-model-small-fr-0.22", 41),
        Language("en-US", "English (US)", "vosk-model-small-en-us-0.15", 40),
        Language("es-ES", "Español", "vosk-model-small-es-0.42", 39),
        Language("de-DE", "Deutsch", "vosk-model-small-de-0.15", 45),
        Language("it-IT", "Italiano", "vosk-model-small-it-0.22", 48),
        Language("pt-BR", "Português", "vosk-model-small-pt-0.3", 31),
        Language("nl-NL", "Nederlands", "vosk-model-small-nl-0.22", 39),
    )

    fun language(id: String) = languages.firstOrNull { it.id == id } ?: languages.first()

    fun dir(base: File, lang: Language) = File(File(base, "models"), lang.model)

    fun isInstalled(base: File, lang: Language) = File(dir(base, lang), "conf").exists() || File(dir(base, lang), "am").exists()

    /** Télécharge et décompresse le modèle. Bloquant : à lancer hors du thread principal. */
    fun download(base: File, lang: Language, progress: (Float) -> Unit) {
        val models = File(base, "models").apply { mkdirs() }
        val staging = File(models, ".${lang.model}.partial").apply { deleteRecursively(); mkdirs() }
        val conn = URL("https://alphacephei.com/vosk/models/${lang.model}.zip").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) error("Téléchargement refusé (HTTP ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (lang.sizeMb * 1_000_000L)
            var read = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) { read += n; progress((read.toFloat() / total).coerceAtMost(1f)) }
                    return n
                }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                val root = staging.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(staging, entry.name)
                    require(out.canonicalPath.startsWith(root)) { "Archive invalide." }
                    if (entry.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
            // L'archive contient un dossier au nom du modèle.
            val inner = File(staging, lang.model).takeIf { it.isDirectory } ?: staging
            val target = dir(base, lang)
            target.deleteRecursively()
            if (!inner.renameTo(target)) error("Impossible d'installer le modèle.")
        } finally {
            conn.disconnect()
            staging.deleteRecursively()
        }
    }

    fun delete(base: File, lang: Language) { dir(base, lang).deleteRecursively() }
}
