package io.github.mdeclais.papote

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Copie les fichiers d'une interview dans <dossier choisi>/<projet>/. Le
 * dossier est choisi une fois dans le sélecteur d'Android (stockage du
 * téléphone, carte SD, ou une app de cloud qui le permet) et Papote garde
 * l'autorisation d'y écrire.
 */
object Backup {
    fun folderName(context: Context, uri: Uri?): String? =
        uri?.let { DocumentFile.fromTreeUri(context, it)?.name }

    /** Copie bloquante : à lancer hors du thread principal. Renvoie un message d'erreur, ou null si tout est copié. */
    fun run(context: Context, tree: Uri, project: String?, files: List<File>): String? = runCatching {
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("Dossier de sauvegarde introuvable.")
        if (!root.canWrite()) error("Papote n'a plus le droit d'écrire dans « ${root.name} ». Choisis à nouveau le dossier.")
        val name = project ?: "Sans projet"
        val folder = root.findFile(name)?.takeIf { it.isDirectory }
            ?: root.createDirectory(name) ?: error("Impossible de créer le dossier « $name ».")
        for (file in files) {
            folder.findFile(file.name)?.delete()
            // octet-stream : le fournisseur garde le nom tel quel, sans ajouter d'extension.
            val target = folder.createFile("application/octet-stream", file.name)
                ?: error("Impossible de créer ${file.name}.")
            context.contentResolver.openOutputStream(target.uri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: error("Impossible d'écrire ${file.name}.")
        }
        null
    }.getOrElse { it.message ?: it.toString() }
}
