package io.github.mdeclais.papote

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val r = Recorder
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            r.backupTree = uri
            r.savePrefs()
        }
    }
    fun save() = r.savePrefs()

    Column(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
        TopBar("Réglages", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle("Sauvegarde")
            Card {
                val tree = r.backupTree
                if (tree != null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF3FA34D))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Connecté", color = Palette.ink, fontWeight = FontWeight.SemiBold)
                            Text("Dossier : ${Backup.folderName(context, tree) ?: "?"}", color = Palette.inkSoft, fontSize = 13.sp)
                        }
                    }
                    Divider()
                    Action("Changer de dossier…") { pickFolder.launch(null) }
                    Divider()
                    Action("Déconnecter", color = Palette.rec) {
                        runCatching { context.contentResolver.releasePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        r.backupTree = null; save()
                    }
                } else {
                    Row(Modifier.fillMaxWidth().clickable { pickFolder.launch(null) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, tint = Palette.coral)
                        Spacer(Modifier.width(12.dp))
                        Text("Choisir un dossier de sauvegarde…", color = Palette.coral, fontWeight = FontWeight.SemiBold)
                    }
                }
                Divider()
                Toggle("Sauvegarder à la fin de chaque interview", r.autoBackup) { r.autoBackup = it; save() }
            }
            Footer("Papote crée un dossier par projet dans le dossier choisi. Les textes (SRT, texte, EDL) partent en premier, le WAV ensuite. " +
                   "Pour Google Drive : utilise « Partager » à la fin d'une interview et choisis Drive (Drive n'autorise pas les apps à écrire dans un dossier choisi).")

            SectionTitle("Micro")
            Card {
                Toggle("Stéréo (2 pistes)", r.stereo) { r.stereo = it; save() }
                Divider()
                Toggle("24 bits (studio) au lieu de 16 bits", r.bitDepth == 24) { r.bitDepth = if (it) 24 else 16; save() }
            }
            Footer("L'entrée se choisit sur l'écran principal. Branche le micro USB-C ou Bluetooth avant. Si l'entrée ne fournit qu'un canal, " +
                   "le fichier reste mono. Le 16 bits pèse un tiers de moins (environ 5,8 Mo par minute en mono) et garde le timecode pour Resolve.")

            SectionTitle("Locuteurs")
            Card {
                Toggle("Distinguer les locuteurs", r.speakersEnabled) { r.speakersEnabled = it; save() }
                if (r.speakersEnabled) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        r.speakerNames.forEachIndexed { i, n ->
                            OutlinedTextField(n, { v -> r.speakerNames = r.speakerNames.toMutableList().also { it[i] = v }; save() },
                                              label = { Text("Locuteur ${i + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Toggle("Noms dans les sous-titres SRT", r.speakerNamesInSRT) { r.speakerNamesInSRT = it; save() }
                }
            }
            Footer("Pendant l'interview, touche le nom de la personne qui parle. Chaque phrase prend le nom actif au moment où elle commence.")

            SectionTitle("Langues de transcription")
            Card {
                r.modelsVersion
                VoskModels.languages.forEachIndexed { i, l ->
                    val installed = r.modelInstalled(l)
                    val progress = r.downloading?.takeIf { it.first == l.id }?.second
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, color = Palette.ink)
                            Text(when {
                                progress != null -> "Téléchargement… ${(progress * 100).toInt()} %"
                                installed -> "Installée · hors ligne"
                                else -> "${l.sizeMb} Mo à télécharger"
                            }, color = Palette.inkSoft, fontSize = 12.sp)
                        }
                        when {
                            progress != null -> {}
                            installed -> Text("Supprimer", color = Palette.rec, modifier = Modifier.clickable { r.deleteModel(l) })
                            else -> Icon(Icons.Outlined.Download, "Télécharger", tint = Palette.coral,
                                         modifier = Modifier.clickable(enabled = r.downloading == null) { r.downloadModel(l) })
                        }
                    }
                    if (i < VoskModels.languages.lastIndex) Divider()
                }
            }
            Footer("La transcription se fait sur le téléphone avec Vosk, sans Internet. Chaque langue se télécharge une fois.")
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Palette.background)

@Composable
private fun Footer(text: String) =
    Text(text, color = Palette.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

@Composable
private fun Action(label: String, color: Color = Palette.coral, onClick: () -> Unit) =
    Text(label, color = color, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp))

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.ink, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.coral))
    }
}
