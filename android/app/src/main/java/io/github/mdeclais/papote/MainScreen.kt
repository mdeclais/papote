package io.github.mdeclais.papote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

@Composable
fun MainScreen(onSessions: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val r = Recorder
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) r.start()
        else r.errorMessage = "Papote a besoin du micro. Autorise-le dans Réglages > Applis > Papote > Autorisations."
    }
    fun toggle() {
        when (r.phase) {
            Recorder.Phase.Idle ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) r.start()
                else permission.launch(recordPermissions)
            Recorder.Phase.Recording -> r.stop()
            else -> {}
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Papote", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Palette.coral, modifier = Modifier.weight(1f))
            IconButton(onClick = onSessions) { Icon(Icons.AutoMirrored.Outlined.List, "Interviews", tint = Palette.ink) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Réglages", tint = Palette.ink) }
        }
        Header()
        if (r.speakersEnabled) Speakers()
        r.errorMessage?.let { Notice(it, onClose = { r.errorMessage = null }) }
        Transcript(Modifier.weight(1f))
        r.backupStatus?.let {
            Text(it, color = Palette.inkSoft, fontSize = 12.sp, textAlign = TextAlign.Center,
                 modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp))
        }
        Controls(onToggle = ::toggle)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header() {
    val r = Recorder
    val recording = r.phase == Recorder.Phase.Recording
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { clock = System.currentTimeMillis() } }
    val session = r.session
    val tc = when {
        recording && session != null -> session.tc(r.now())
        else -> Timecode.string(Timecode.secondsSinceMidnight(clock.toDouble()), r.fps)
    }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(26.dp)).background(Palette.card).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording) RecBadge()
            Text(if (recording) "Timecode de l'interview" else "Heure du jour", color = Palette.inkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Text(tc, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = Palette.ink)
        if (recording && session != null) {
            Text(Timecode.duration(r.now()), color = Palette.inkSoft, fontSize = 14.sp)
            LevelMeter(r.level)
        }
        r.status?.let { Text(it, color = Palette.inkSoft, fontSize = 13.sp) }
        r.downloading?.let { (id, p) ->
            Text("Téléchargement ${VoskModels.language(id).name}… ${(p * 100).roundToInt()} %", color = Palette.inkSoft, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val idle = r.phase == Recorder.Phase.Idle
            ProjectChip(idle)
            LanguageChip(idle)
            MenuChip("${r.fps} i/s", Icons.Outlined.Timer, idle) { close ->
                Timecode.frameRates.forEach { f ->
                    DropdownMenuItem(text = { Text("$f i/s") }, onClick = { r.fps = f; r.savePrefs(); close() })
                }
            }
            MenuChip(r.currentInputName, Icons.Outlined.Mic, idle, onOpen = { r.refreshInputs() }) { close ->
                DropdownMenuItem(text = { Text("Micro par défaut") }, onClick = { r.inputId = 0; r.savePrefs(); close() })
                r.inputs.forEach { input ->
                    DropdownMenuItem(text = { Text(input.name) }, onClick = { r.inputId = input.id; r.savePrefs(); close() })
                }
            }
        }
    }
}

@Composable
private fun ProjectChip(enabled: Boolean) {
    val r = Recorder
    var creating by remember { mutableStateOf(false) }
    MenuChip(r.project.ifEmpty { "Sans projet" }, Icons.Outlined.Folder, enabled, onOpen = { r.refreshProjects() }) { close ->
        DropdownMenuItem(text = { Text("Sans projet") }, onClick = { r.project = ""; r.savePrefs(); close() })
        r.projects.forEach { p ->
            DropdownMenuItem(text = { Text(p) }, onClick = { r.project = p; r.savePrefs(); close() })
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Nouveau projet…") }, onClick = { close(); creating = true })
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nouveau projet") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Doc Ferme du Bec") }) },
            confirmButton = { TextButton(onClick = { r.createProject(name); creating = false }, enabled = name.isNotBlank()) { Text("Créer") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun LanguageChip(enabled: Boolean) {
    val r = Recorder
    r.modelsVersion  // relit l'état des modèles après un téléchargement
    val lang = r.language
    val installed = r.modelInstalled(lang)
    MenuChip(lang.name + if (installed) "" else " ⬇", Icons.Outlined.Language, enabled) { close ->
        VoskModels.languages.forEach { l ->
            val ok = r.modelInstalled(l)
            DropdownMenuItem(
                text = { Text(if (ok) l.name else "${l.name} · ${l.sizeMb} Mo à télécharger") },
                trailingIcon = { if (!ok) Icon(Icons.Outlined.Download, null) },
                onClick = {
                    r.localeID = l.id; r.savePrefs(); close()
                    if (!ok) r.downloadModel(l)
                },
            )
        }
    }
}

@Composable
private fun MenuChip(
    label: String, icon: ImageVector, enabled: Boolean, onOpen: () -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).background(Palette.surface)
                .clickable(enabled = enabled) { onOpen(); open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .alpha(if (enabled) 1f else 0.6f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Palette.ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@Composable
private fun RecBadge() {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        1f, 0.3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.padding(end = 6.dp).size(10.dp).alpha(pulse).clip(CircleShape).background(Palette.rec))
}

@Composable
private fun LevelMeter(level: Float) {
    Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.7f).height(6.dp).clip(CircleShape).background(Palette.surface)) {
        Box(Modifier.fillMaxWidth(level).height(6.dp).clip(CircleShape).background(if (level > 0.92f) Palette.rec else Palette.coral))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Speakers() {
    val r = Recorder
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        r.speakerNames.forEachIndexed { i, name ->
            val active = i == r.currentSpeaker
            Text(
                name.ifBlank { "Locuteur ${i + 1}" },
                color = if (active) Palette.cream else Palette.ink,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).background(if (active) Palette.coral else Palette.surface)
                    .clickable { r.setSpeaker(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun Notice(text: String, onClose: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Palette.surface).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Palette.inkSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Fermer", tint = Palette.inkSoft) }
    }
}

@Composable
private fun Transcript(modifier: Modifier) {
    val r = Recorder
    val session = r.session
    val items = session?.items ?: emptyList()
    val state = rememberLazyListState()
    val extra = (if (r.openIn != null) 1 else 0) + (if (r.volatileText.isNotEmpty()) 1 else 0)
    LaunchedEffect(items.size, r.volatileText, r.openIn) {
        val last = items.size + extra - 1
        if (last >= 0) state.animateScrollToItem(last)
    }
    if (session == null || (items.isEmpty() && extra == 0)) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Prêt à papoter ?", color = Palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (r.phase == Recorder.Phase.Recording) "La transcription apparaît ici dès qu'on parle."
                else "Touche le bouton rouge pour lancer l'interview.",
                color = Palette.inkSoft, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(8.dp),
               contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(items, key = { it.id }) { item ->
            when (item) {
                is TranscriptItem.Seg -> Bubble(session, item.segment)
                is TranscriptItem.Mark -> MarkerRow(session, item.marker)
            }
        }
        r.openIn?.let { start ->
            item(key = "open") {
                Text("IN à ${session.tc(start)} · touche OUT pour fermer l'extrait", color = Palette.coral, fontSize = 13.sp,
                     fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        if (r.volatileText.isNotEmpty()) {
            item(key = "volatile") {
                Bubble(session, Segment(start = session.duration, end = session.duration, text = r.volatileText,
                                        speaker = if (session.speakers != null) r.currentSpeaker else null), volatile = true)
            }
        }
    }
}

@Composable
fun Bubble(session: Session, seg: Segment, volatile: Boolean = false, onClick: (() -> Unit)? = null) {
    val interviewer = seg.speaker == 0
    val name = session.speakerName(seg.speaker)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (interviewer) Alignment.End else Alignment.Start) {
        Text(listOfNotNull(name, if (volatile) null else session.tc(seg.start)).joinToString(" · "),
             color = Palette.inkSoft, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            seg.text,
            color = if (interviewer) Palette.ink else Palette.cream,
            fontStyle = if (volatile) FontStyle.Italic else FontStyle.Normal,
            fontSize = 16.sp,
            modifier = Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (interviewer) Palette.bubbleAlt else Palette.coral)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .alpha(if (volatile) 0.7f else 1f),
        )
    }
}

@Composable
fun MarkerRow(session: Session, m: Marker, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(Palette.card)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Star, null, tint = Palette.coral, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            val range = if (m.duration == null) session.tc(m.time)
                        else "${session.tc(m.time)} → ${session.tc(m.time + session.frames(m).toDouble() / session.fps)}"
            Text("${m.name} · $range", color = Palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Controls(onToggle: () -> Unit) {
    val r = Recorder
    val context = LocalContext.current
    val recording = r.phase == Recorder.Phase.Recording
    val session = r.session
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction("Effacer", Icons.Outlined.DeleteSweep, enabled = r.phase == Recorder.Phase.Idle && session != null) { r.clearScreen() }
        ExtractButton("IN", enabled = recording, highlighted = r.openIn != null) { r.markIn() }
        RecordButton(r.phase, onToggle)
        ExtractButton("OUT", enabled = recording && r.openIn != null, highlighted = false) { r.markOut() }
        RoundAction("Partager", Icons.Outlined.Share, enabled = r.phase == Recorder.Phase.Idle && session?.finished == true) {
            session?.let { share(context, r.store.exportFiles(it), it.id) }
        }
    }
}

@Composable
private fun RecordButton(phase: Recorder.Phase, onClick: () -> Unit) {
    val recording = phase == Recorder.Phase.Recording
    val busy = phase == Recorder.Phase.Preparing || phase == Recorder.Phase.Finishing
    Box(
        Modifier.size(78.dp).clip(CircleShape).background(Palette.card).border(4.dp, Palette.coral, CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Palette.rec))
        else Box(Modifier.size(50.dp).scale(if (busy) 0.7f else 1f).clip(CircleShape).background(Palette.rec.copy(alpha = if (busy) 0.5f else 1f)))
    }
}

@Composable
private fun ExtractButton(label: String, enabled: Boolean, highlighted: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape)
            .background(if (highlighted) Palette.coral else Palette.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Black, fontSize = 16.sp, color = if (highlighted) Palette.cream else Palette.coral)
    }
}

@Composable
private fun RoundAction(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.surface).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Palette.ink)
        }
        Text(label, fontSize = 11.sp, color = Palette.inkSoft)
    }
}
