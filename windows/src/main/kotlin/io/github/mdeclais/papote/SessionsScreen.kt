package io.github.mdeclais.papote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = Palette.ink) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.ink, maxLines = 1, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Palette.inkSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)) { content() }
}

private fun date(ms: Long) = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
fun SessionsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val sessions = remember { Recorder.store.loadAll() }
    val groups = sessions.groupBy { it.project ?: "" }.toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        TopBar("Interviews", onBack)
        if (sessions.isEmpty()) {
            Text("Aucune interview pour l'instant.", color = Palette.inkSoft, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { (project, list) ->
                item(key = "h-$project") { SectionTitle(project.ifEmpty { "Sans projet" }) }
                item(key = "g-$project") {
                    Card {
                        list.forEachIndexed { i, s ->
                            Column(Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(s.id, color = Palette.ink, fontWeight = FontWeight.SemiBold)
                                Text("${date(s.startDateMs)} · ${Timecode.duration(s.duration)} · ${s.segments.size} phrases" +
                                     (if (s.markers.isNotEmpty()) " · ${s.markers.size} extraits" else ""),
                                     color = Palette.inkSoft, fontSize = 13.sp)
                            }
                            if (i < list.lastIndex) androidx.compose.material3.HorizontalDivider(color = Palette.background)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionDetailScreen(id: String, onBack: () -> Unit) {
    val r = Recorder
    var session by remember { mutableStateOf(r.store.loadAll().firstOrNull { it.id == id }) }
    var edited by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<Segment?>(null) }
    var editingMarker by remember { mutableStateOf<Marker?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val s = session ?: run { onBack(); return }

    // En quittant l'écran : fichiers réécrits et sauvegarde relancée si on a corrigé le texte.
    DisposableEffect(Unit) {
        onDispose {
            val final = session
            if (edited && final != null) {
                r.update(final)
                if (r.backupDir != null && r.autoBackup) r.backUp(final)
            }
        }
    }
    fun change(new: Session) { session = new; edited = true; r.update(new) }

    Column(Modifier.fillMaxSize().background(Palette.background)) {
        TopBar(s.id, onBack) {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Supprimer", tint = Palette.rec) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SectionTitle("Informations")
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Info("Date", date(s.startDateMs))
                        Info("Début", s.tc(0.0))
                        Info("Fin", s.tc(s.duration))
                        Info("Durée", Timecode.duration(s.duration))
                        Info("Cadence", "${s.fps} i/s")
                        Info("Audio", "${s.sampleRate.toInt()} Hz · ${s.bitDepth} bits · ${if (s.channels == 2) "stéréo" else "mono"}")
                        Info("Langue", VoskModels.language(s.localeID).name)
                    }
                }
                s.speakers?.let { names ->
                    SectionTitle("Locuteurs")
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            names.forEachIndexed { i, n ->
                                var text by remember(i) { mutableStateOf(n) }
                                OutlinedTextField(text, { text = it; change(s.copy(speakers = names.toMutableList().also { l -> l[i] = it })) },
                                                  label = { Text(if (i == 0) "Intervieweur" else "Invité $i") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                SectionTitle("Fichiers")
                Card {
                    r.store.exportFiles(s).forEach { f -> FileRow(f) { Desktop.reveal(f) } }
                    Row(Modifier.fillMaxWidth().clickable { Desktop.reveal(r.store.audio(s)) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, null, tint = Palette.coral)
                        Spacer(Modifier.width(12.dp))
                        Text("Ouvrir le dossier dans l'Explorateur", color = Palette.coral, fontWeight = FontWeight.SemiBold)
                    }
                    if (r.backupDir != null) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !r.backupRunning) { r.backUp(s) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudUpload, null, tint = Palette.coral)
                            Spacer(Modifier.width(12.dp))
                            Text("Sauvegarder maintenant", color = Palette.coral, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                r.backupStatus?.let { Text(it, color = Palette.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                SectionTitle("Transcription · clique sur une phrase pour la corriger")
            }
            items(s.items, key = { it.id }) { item ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    when (item) {
                        is TranscriptItem.Seg -> Bubble(s, item.segment, onClick = { editingSegment = item.segment })
                        is TranscriptItem.Mark -> MarkerRow(s, item.marker, onClick = { editingMarker = item.marker })
                    }
                }
            }
        }
    }

    editingSegment?.let { seg ->
        var text by remember(seg.id) { mutableStateOf(seg.text) }
        var speaker by remember(seg.id) { mutableStateOf(seg.speaker) }
        AlertDialog(
            onDismissRequest = { editingSegment = null },
            title = { Text(s.tc(seg.start)) },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
                    s.speakers?.let { names ->
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            names.forEachIndexed { i, n -> FilterChip(speaker == i, { speaker = i }, label = { Text(n) }) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    change(s.copy(segments = s.segments.map { if (it.id == seg.id) it.copy(text = text.trim(), speaker = speaker) else it }))
                    editingSegment = null
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = {
                    change(s.copy(segments = s.segments.filter { it.id != seg.id }))
                    editingSegment = null
                }) { Text("Supprimer la phrase", color = Palette.rec) }
            },
        )
    }

    editingMarker?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.name) }
        AlertDialog(
            onDismissRequest = { editingMarker = null },
            title = { Text("Extrait") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nom") }) },
            confirmButton = {
                TextButton(onClick = {
                    change(s.copy(markers = s.markers.map { if (it.id == m.id) it.copy(name = name.trim().ifEmpty { m.name }) else it }))
                    editingMarker = null
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = {
                    change(s.copy(markers = s.markers.filter { it.id != m.id }))
                    editingMarker = null
                }) { Text("Supprimer", color = Palette.rec) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer l'interview ?") },
            text = { Text("L'audio, les sous-titres et les marqueurs seront effacés de l'ordinateur. Les copies déjà sauvegardées ne sont pas touchées.") },
            confirmButton = {
                TextButton(onClick = { edited = false; r.delete(s); session = null; confirmDelete = false; onBack() }) {
                    Text("Supprimer", color = Palette.rec)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, color = Palette.inkSoft, modifier = Modifier.width(90.dp))
        Text(value, color = Palette.ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FileRow(file: File, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShare).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, null, tint = Palette.inkSoft)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, color = Palette.ink, fontSize = 14.sp)
            Text(size(file.length()), color = Palette.inkSoft, fontSize = 12.sp)
        }
        Icon(Icons.Outlined.FolderOpen, "Afficher dans l'Explorateur", tint = Palette.coral)
    }
}

private fun size(bytes: Long) = when {
    bytes >= 1_000_000 -> String.format(java.util.Locale.FRANCE, "%.1f Mo", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} Ko"
    else -> "$bytes o"
}
