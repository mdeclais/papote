package io.github.mdeclais.papote

import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * <racine>/<projet>/<interview>/ — ou <racine>/<interview>/ sans projet.
 * La racine est le dossier de l'app sur le stockage du téléphone.
 */
class Store(val root: File) {
    init { root.mkdirs() }

    fun folder(id: String, project: String?): File =
        if (project.isNullOrEmpty()) File(root, id) else File(File(root, project), id)

    fun folder(s: Session) = folder(s.id, s.project)
    fun audio(s: Session) = File(folder(s), "${s.id}.wav")
    /** Pour une timeline qui démarre à 01:00:00:00 avec le WAV posé au début. */
    fun srt(s: Session) = File(folder(s), "${s.id}.srt")
    /** Pour une timeline dont le TC de départ est celui du WAV (heure du jour). */
    fun srtTOD(s: Session) = File(folder(s), "${s.id}_TC-horaire.srt")
    fun txt(s: Session) = File(folder(s), "${s.id}.txt")
    fun edl(s: Session) = File(folder(s), "${s.id}_marqueurs.edl")
    fun edlTOD(s: Session) = File(folder(s), "${s.id}_marqueurs_TC-horaire.edl")

    fun exportFiles(s: Session) = listOf(audio(s), srt(s), srtTOD(s), txt(s), edl(s), edlTOD(s)).filter { it.exists() }

    /** Fichiers légers, envoyés en premier lors d'une sauvegarde. */
    fun textFiles(s: Session) = exportFiles(s).filter { it != audio(s) }

    private fun json(s: Session) = File(folder(s), "session.json")

    // Projets

    fun projects(): List<String> = subfolders(root)
        .filter { !File(it, "session.json").exists() }
        .map { it.name }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Crée le dossier du projet ; renvoie le nom réellement utilisé. */
    fun createProject(name: String): String {
        val clean = name.replace("/", "-").replace(":", "-").trim()
        require(clean.isNotEmpty() && !clean.startsWith(".")) { "Nom de projet invalide." }
        File(root, clean).mkdirs()
        return clean
    }

    // Sessions

    fun createFolder(id: String, project: String?) { folder(id, project).mkdirs() }

    fun save(s: Session) {
        val target = json(s)
        val tmp = File(target.parentFile, "session.json.tmp")
        tmp.writeText(s.toJson().toString(2))
        tmp.renameTo(target)
    }

    fun loadAll(): List<Session> {
        val top = subfolders(root)
        return (top + top.flatMap { subfolders(it) })
            .map { File(it, "session.json") }
            .filter { it.exists() }
            .mapNotNull { runCatching { Session.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startDateMs }
    }

    /**
     * Au lancement : termine les interviews coupées par un plantage ou une
     * coupure de batterie — WAV réparé, durée recalculée, exports réécrits.
     */
    fun recoverInterrupted(speakerNamesInSRT: Boolean, backupConfigured: Boolean): List<Session> =
        loadAll().filter { !it.finished }.map { s0 ->
            var s = s0
            val frames = BwfWriter.repair(audio(s))
            if (frames != null && s.sampleRate > 0) s = s.copy(duration = frames / s.sampleRate)
            s = s.copy(finished = true, backupPending = backupConfigured)
            save(s)
            writeExports(s, speakerNamesInSRT)
            s
        }

    fun delete(s: Session) { folder(s).deleteRecursively() }

    fun writeExports(s: Session, speakerNamesInSRT: Boolean) {
        runCatching {
            srt(s).writeText(Exports.srt(s, 3600.0, speakerNamesInSRT))
            srtTOD(s).writeText(Exports.srt(s, null, speakerNamesInSRT))
            txt(s).writeText(Exports.text(s))
            if (s.markers.isNotEmpty()) {
                edl(s).writeText(Exports.edl(s, 3600.0))
                edlTOD(s).writeText(Exports.edl(s, null))
            } else {
                edl(s).delete()
                edlTOD(s).delete()
            }
        }
    }

    private fun subfolders(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.toList() ?: emptyList()

    companion object {
        /** « Doc Ferme du Bec » → « Doc-Ferme-du-Bec », pour des noms de fichiers sûrs. */
        fun slug(s: String): String {
            val folded = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return folded.split(Regex("[^A-Za-z0-9_-]+")).filter { it.isNotEmpty() }.joinToString("-")
        }

        fun makeID(dateMs: Long, project: String?, zone: ZoneId = ZoneId.systemDefault()): String {
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").format(Instant.ofEpochMilli(dateMs).atZone(zone))
            val prefix = project?.let { slug(it) }?.takeIf { it.isNotEmpty() } ?: "ITW"
            return "${prefix}_$stamp"
        }
    }
}
