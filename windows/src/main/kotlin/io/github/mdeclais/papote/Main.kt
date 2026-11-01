package io.github.mdeclais.papote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

sealed interface Screen {
    data object Main : Screen
    data object Sessions : Screen
    data class Detail(val id: String) : Screen
    data object Settings : Screen
}

private val speakerKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six,
                                 Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6)

fun main() = application {
    var quitting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Recorder.recoverInterrupted()
        Recorder.retryPendingBackups()
    }
    // Fermer pendant l'enregistrement : on arrête proprement avant de quitter.
    LaunchedEffect(quitting, Recorder.phase) {
        if (!quitting) return@LaunchedEffect
        when (Recorder.phase) {
            Recorder.Phase.Idle -> exitApplication()
            Recorder.Phase.Recording -> Recorder.stop()
            else -> {}  // préparation ou finalisation en cours : on attend
        }
    }
    Window(
        onCloseRequest = { quitting = true },
        title = "Papote",
        icon = painterResource("icon.png"),
        state = rememberWindowState(size = DpSize(480.dp, 860.dp)),
        // Raccourcis actifs seulement pendant l'enregistrement : aucun champ de texte n'est alors ouvert.
        onKeyEvent = { e ->
            val r = Recorder
            if (e.type != KeyEventType.KeyDown || r.phase != Recorder.Phase.Recording ||
                e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@Window false
            val index = speakerKeys.indexOf(e.key).takeIf { it >= 0 }?.rem(6)
            when {
                index != null -> { if (r.speakersEnabled) r.setSpeaker(index); true }
                e.key == Key.I -> { r.markIn(); true }
                e.key == Key.O -> { r.markOut(); true }
                else -> false
            }
        },
    ) {
        window.minimumSize = java.awt.Dimension(400, 640)
        PapoteTheme { App() }
    }
}

@Composable
private fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    when (val s = screen) {
        Screen.Main -> MainScreen(onSessions = { screen = Screen.Sessions }, onSettings = { screen = Screen.Settings })
        Screen.Sessions -> SessionsScreen(onBack = { screen = Screen.Main }, onOpen = { screen = Screen.Detail(it) })
        is Screen.Detail -> SessionDetailScreen(s.id, onBack = { screen = Screen.Sessions })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
    }
}
