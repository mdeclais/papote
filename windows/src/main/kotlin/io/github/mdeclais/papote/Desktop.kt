package io.github.mdeclais.papote

import com.sun.jna.platform.win32.Kernel32
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView

/** Ce qui dépend du système : dossiers, veille, Explorateur. Pensé pour Windows, fonctionne aussi sur Mac. */
object Desktop {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows = os.startsWith("windows")
    val isMac = os.startsWith("mac")
    private val home = File(System.getProperty("user.home"))

    /** Documents\Papote : visible dans l'Explorateur, suit la redirection OneDrive de Windows. */
    val interviewsRoot: File by lazy {
        val documents = if (isWindows) FileSystemView.getFileSystemView().defaultDirectory else File(home, "Documents")
        File(documents, "Papote")
    }

    /** Modèles de langue : hors de Documents, ils ne concernent pas l'utilisateur. */
    val dataDir: File by lazy {
        when {
            isWindows -> File(System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path, "Papote")
            isMac -> File(home, "Library/Application Support/Papote")
            else -> File(home, ".local/share/papote")
        }.apply { mkdirs() }
    }

    /** Ouvre le dossier dans l'Explorateur (ou le Finder) en sélectionnant le fichier. */
    fun reveal(file: File) {
        runCatching {
            when {
                isWindows && file.isFile -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                isMac -> ProcessBuilder("open", "-R", file.absolutePath).start()
                else -> java.awt.Desktop.getDesktop().open(if (file.isDirectory) file else file.parentFile)
            }
        }
    }

    private var caffeinate: Process? = null

    /** Empêche la mise en veille pendant l'enregistrement. À appeler depuis le thread de l'interface. */
    fun keepAwake(on: Boolean) {
        runCatching {
            if (isWindows) {
                // ES_CONTINUOUS | ES_SYSTEM_REQUIRED, puis ES_CONTINUOUS seul pour rendre la main.
                Kernel32.INSTANCE.SetThreadExecutionState(if (on) 0x80000001.toInt() else 0x80000000.toInt())
            } else if (isMac) {
                caffeinate?.destroy()
                caffeinate = if (on) ProcessBuilder("caffeinate", "-i", "-w", ProcessHandle.current().pid().toString()).start() else null
            }
        }
    }

    /** Sélecteur de dossier natif de Windows (style système). */
    fun chooseFolder(title: String, start: File?): File? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(start ?: FileSystemView.getFileSystemView().defaultDirectory).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showDialog(null, "Choisir ce dossier") == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
