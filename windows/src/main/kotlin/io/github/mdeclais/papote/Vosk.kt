package io.github.mdeclais.papote

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * Accès direct aux fonctions de libvosk utilisées par Papote. Le jar
 * com.alphacephei:vosk fournit les bibliothèques natives, mais sa propre
 * liaison Java exige une fonction absente de la version Mac : on ne s'en sert pas.
 */
private interface VoskLib : Library {
    fun vosk_set_log_level(level: Int)
    fun vosk_model_new(path: String): Pointer?
    fun vosk_model_free(model: Pointer)
    fun vosk_recognizer_new(model: Pointer, sampleRate: Float): Pointer?
    fun vosk_recognizer_set_words(recognizer: Pointer, words: Int)
    fun vosk_recognizer_accept_waveform_s(recognizer: Pointer, data: ShortArray, length: Int): Int
    // Textes en UTF-8 : décodés à la main, Windows utiliserait sinon sa page de code.
    fun vosk_recognizer_result(recognizer: Pointer): Pointer
    fun vosk_recognizer_partial_result(recognizer: Pointer): Pointer
    fun vosk_recognizer_final_result(recognizer: Pointer): Pointer
    fun vosk_recognizer_free(recognizer: Pointer)
}

private object VoskNative {
    /** Bibliothèques dans l'ordre de chargement : dépendances MinGW d'abord sous Windows. */
    private val files = when {
        Desktop.isWindows -> listOf("libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll", "libvosk.dll").map { "win32-x86-64/$it" }
        Desktop.isMac -> listOf("darwin/libvosk.dylib")
        else -> listOf("linux-x86-64/libvosk.so")
    }

    val lib: VoskLib by lazy {
        // Copiées une fois dans le dossier de l'app, la DLL a besoin d'un vrai fichier.
        val dir = File(Desktop.dataDir, "native/vosk-0.3.45").apply { mkdirs() }
        val paths = files.map { res ->
            val out = File(dir, res.substringAfter('/'))
            if (!out.exists()) {
                val input = VoskModel::class.java.classLoader.getResourceAsStream(res) ?: error("Bibliothèque $res absente.")
                val tmp = File(dir, out.name + ".tmp")
                input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(out)
            }
            out.absolutePath
        }
        paths.dropLast(1).forEach { System.load(it) }
        Native.load(paths.last(), VoskLib::class.java).apply { vosk_set_log_level(-1) }
    }
}

class VoskModel(path: String) : AutoCloseable {
    internal val handle: Pointer = VoskNative.lib.vosk_model_new(path) ?: error("Modèle de langue illisible : $path")
    override fun close() = VoskNative.lib.vosk_model_free(handle)
}

class VoskRecognizer(model: VoskModel, sampleRate: Float) : AutoCloseable {
    private val lib = VoskNative.lib
    private val handle: Pointer = lib.vosk_recognizer_new(model.handle, sampleRate) ?: error("Reconnaissance vocale indisponible.")

    init { lib.vosk_recognizer_set_words(handle, 1) }

    /** true quand une phrase est terminée : lire alors [result]. */
    fun accept(samples: ShortArray, length: Int = samples.size) = lib.vosk_recognizer_accept_waveform_s(handle, samples, length) != 0
    val result: String get() = lib.vosk_recognizer_result(handle).getString(0, "UTF-8")
    val partialResult: String get() = lib.vosk_recognizer_partial_result(handle).getString(0, "UTF-8")
    val finalResult: String get() = lib.vosk_recognizer_final_result(handle).getString(0, "UTF-8")
    override fun close() = lib.vosk_recognizer_free(handle)
}
