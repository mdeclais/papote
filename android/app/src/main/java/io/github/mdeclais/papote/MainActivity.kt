package io.github.mdeclais.papote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File

sealed interface Screen {
    data object Main : Screen
    data object Sessions : Screen
    data class Detail(val id: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Recorder.init(this)
        if (savedInstanceState == null) {
            Recorder.recoverInterrupted()
            Recorder.retryPendingBackups()
        }
        setContent {
            PapoteTheme {
                // Écran allumé pendant l'enregistrement.
                LaunchedEffect(Recorder.phase) {
                    if (Recorder.phase == Recorder.Phase.Recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                App()
            }
        }
    }
}

@Composable
private fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    BackHandler(enabled = screen != Screen.Main) {
        screen = when (screen) {
            is Screen.Detail -> Screen.Sessions
            else -> Screen.Main
        }
    }
    when (val s = screen) {
        Screen.Main -> MainScreen(onSessions = { screen = Screen.Sessions }, onSettings = { screen = Screen.Settings })
        Screen.Sessions -> SessionsScreen(onBack = { screen = Screen.Main }, onOpen = { screen = Screen.Detail(it) })
        is Screen.Detail -> SessionDetailScreen(s.id, onBack = { screen = Screen.Sessions })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
    }
}

/** Permissions demandées au premier enregistrement. */
val recordPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.RECORD_AUDIO)

/** Ouvre la feuille de partage d'Android (Drive, Gmail, WeTransfer…) pour ces fichiers. */
fun share(context: Context, files: List<File>, title: String) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                 else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    intent.type = "*/*"
    intent.putExtra(Intent.EXTRA_SUBJECT, title)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title))
}
