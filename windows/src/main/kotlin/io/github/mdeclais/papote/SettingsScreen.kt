package io.github.mdeclais.papote

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val r = Recorder
    fun save() = r.savePrefs()
    fun pickFolder() {
        Desktop.chooseFolder("Dossier de sauvegarde de Papote", r.backupDir)?.let { r.backupDir = it; save() }
    }

    Column(Modifier.fillMaxSize().background(Palette.background)) {
        TopBar("Réglages", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle("Sauvegarde")
            Card {
                val dir = r.backupDir
                if (dir != null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF3FA34D))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Connecté", color = Palette.ink, fontWeight = FontWeight.SemiBold)
                            Text(dir.path, color = Palette.inkSoft, fontSize = 13.sp)
                        }
                    }
                    Divider()
                    Action("Changer de dossier…") { pickFolder() }
                    Divider()
                    Action("Ne plus sauvegarder", color = Palette.rec) { r.backupDir = null; save() }
                } else {
                    Row(Modifier.fillMaxWidth().clickable { pickFolder() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, tint = Palette.coral)
                        Spacer(Modifier.width(12.dp))
                        Text("Choisir un dossier de sauvegarde…", color = Palette.coral, fontWeight = FontWeight.SemiBold)
                    }
                }
                Divider()
                Toggle("Sauvegarder à la fin de chaque interview", r.autoBackup) { r.autoBackup = it; save() }
            }
            Footer("Papote crée un dossier par projet dans le dossier choisi. Les textes (SRT, texte, EDL) partent en premier, le WAV ensuite. " +
                   "Choisis un dossier dans OneDrive, dans Google Drive (avec l'app Google Drive pour ordinateur) ou sur un disque externe. " +
                   "Les interviews sont rangées dans ${Desktop.interviewsRoot.path}.")

            SectionTitle("Micro")
            Card {
                Toggle("Stéréo (2 pistes)", r.stereo) { r.stereo = it; save() }
                Divider()
                Toggle("24 bits (studio) au lieu de 16 bits", r.bitDepth == 24) { r.bitDepth = if (it) 24 else 16; save() }
            }
            Footer("L'entrée se choisit sur l'écran principal. Branche le micro USB ou Bluetooth avant. Si l'entrée ne fournit qu'un canal, " +
                   "le fichier reste mono. Le 16 bits pèse un tiers de moins (environ 5,8 Mo par minute en mono) et garde le timecode pour Resolve. " +
                   "Si aucun son n'arrive : Paramètres Windows > Confidentialité > Microphone, autorise les applications de bureau.")

            SectionTitle("Locuteurs")
            Card {
                Toggle("Distinguer les locuteurs", r.speakersEnabled) { r.speakersEnabled = it; save() }
                if (r.speakersEnabled) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Invités : ${r.guestCount}", color = Palette.ink, modifier = Modifier.weight(1f))
                        StepButton(Icons.Outlined.Remove, "Un invité de moins", r.guestCount > 1) { r.setGuestCount(r.guestCount - 1) }
                        Spacer(Modifier.width(8.dp))
                        StepButton(Icons.Outlined.Add, "Un invité de plus", r.guestCount < Recorder.MAX_GUESTS) { r.setGuestCount(r.guestCount + 1) }
                    }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        r.speakerNames.forEachIndexed { i, n ->
                            OutlinedTextField(n, { v -> r.speakerNames = r.speakerNames.toMutableList().also { it[i] = v }; save() },
                                              label = { Text(if (i == 0) "Intervieweur" else "Invité $i") }, singleLine = true,
                                              modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Toggle("Noms dans les sous-titres SRT", r.speakerNamesInSRT) { r.speakerNamesInSRT = it; save() }
                }
            }
            Footer("Un intervieweur et jusqu'à ${Recorder.MAX_GUESTS} invités. Pendant l'interview, clique sur le nom de la personne qui parle, ou tape 1 à ${Recorder.MAX_GUESTS + 1} (I et O pour les extraits). Chaque phrase prend le nom actif au moment où elle commence.")

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
            Footer("La transcription se fait sur l'ordinateur avec Vosk, sans Internet. Chaque langue se télécharge une fois.")
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) =
    Icon(icon, label, tint = if (enabled) Palette.coral else Palette.inkSoft.copy(alpha = 0.4f),
         modifier = Modifier.clip(CircleShape).background(Palette.background)
             .clickable(enabled = enabled, onClick = onClick).padding(8.dp))

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
