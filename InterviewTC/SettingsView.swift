import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Bindable var model: RecorderModel
    @Environment(\.dismiss) private var dismiss
    @State private var destinations = Backup.destinations
    @State private var pickingFor: CloudService?
    @State private var pickError: (service: CloudService, message: String)?
    @AppStorage(Backup.autoKey) private var autoBackup = true

    /// Dossiers ajoutés avec la version précédente, hors iCloud et Google Drive.
    private var otherDestinations: [BackupDestination] {
        destinations.filter { d in !CloudService.allCases.contains { $0.rawValue == d.provider } }
    }

    var body: some View {
        NavigationStack {
            Form {
                ForEach(CloudService.allCases) { service in
                    connectionSection(service)
                }

                Section {
                    Toggle("Sauvegarder à la fin de chaque interview", isOn: $autoBackup)
                    ForEach(otherDestinations) { dest in
                        Label(dest.label, systemImage: "folder").foregroundStyle(Theme.ink)
                    }
                    .onDelete { offsets in
                        let doomed = offsets.map { otherDestinations[$0].id }
                        destinations.removeAll { doomed.contains($0.id) }
                        Backup.destinations = destinations
                    }
                } header: {
                    Text("Sauvegarde")
                } footer: {
                    Text("Dans chaque service connecté, Papote crée un dossier par projet. Les textes (SRT, texte, EDL) partent en premier, le WAV ensuite. Une sauvegarde qui échoue est relancée à l'ouverture suivante.")
                }

                Section {
                    Picker("Entrée", selection: $model.inputUID) {
                        Text("Micro par défaut").tag("")
                        ForEach(model.inputs) { Text($0.name).tag($0.id) }
                    }
                    Toggle("Stéréo (2 pistes)", isOn: $model.stereo)
                    Picker("Qualité", selection: $model.bitDepth) {
                        Text("16 bits · léger").tag(16)
                        Text("24 bits · studio").tag(24)
                    }
                } header: {
                    Text("Micro")
                } footer: {
                    Text("Branche le micro USB-C ou Bluetooth avant d'ouvrir ces réglages. En stéréo, le micro intégré enregistre en stéréo ; une interface à deux entrées donne une piste par micro. Si l'entrée ne fournit qu'un canal, le fichier reste mono. Le 16 bits pèse un tiers de moins (environ 5,8 Mo par minute en mono) et garde le timecode pour Resolve.")
                }

                Section {
                    Toggle("Distinguer les locuteurs", isOn: $model.speakersEnabled)
                    if model.speakersEnabled {
                        ForEach(model.speakerNames.indices, id: \.self) { i in
                            TextField("Locuteur \(i + 1)", text: $model.speakerNames[i])
                        }
                        Toggle("Noms dans les sous-titres SRT", isOn: $model.speakerNamesInSRT)
                    }
                } header: {
                    Text("Locuteurs")
                } footer: {
                    Text("Pendant l'interview, touche le nom de la personne qui parle. Chaque phrase prend le nom actif au moment où elle commence.")
                }
            }
            .papoteList()
            .navigationTitle("Réglages")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") { dismiss() }
                }
            }
            .onAppear { model.refreshInputs() }
            .fileImporter(isPresented: Binding(get: { pickingFor != nil },
                                               set: { if !$0 { pickingFor = nil } }),
                          allowedContentTypes: [.folder]) { result in
                guard let service = pickingFor else { return }
                do {
                    try Backup.connect(service, to: result.get())
                    destinations = Backup.destinations
                    pickError = nil
                } catch {
                    pickError = (service, error.localizedDescription)
                }
            }
        }
    }

    @ViewBuilder
    private func connectionSection(_ service: CloudService) -> some View {
        let dest = destinations.first { $0.provider == service.rawValue }
        Section {
            if let dest {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Connecté").fontWeight(.semibold)
                        Text("Dossier : \(dest.folderName)").font(.caption).foregroundStyle(Theme.inkSoft)
                    }
                } icon: {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                }
                Button("Changer de dossier…") { pickingFor = service }
                Button("Déconnecter", role: .destructive) {
                    Backup.disconnect(service)
                    destinations = Backup.destinations
                }
            } else {
                serviceAvailability(service)
                Button {
                    pickingFor = service
                } label: {
                    Label("Se connecter…", systemImage: "link").fontWeight(.semibold)
                }
            }
            if let pickError, pickError.service == service {
                Text(pickError.message).font(.footnote).foregroundStyle(Theme.rec)
            }
        } header: {
            Label(service.rawValue, systemImage: service.icon)
        } footer: {
            Text(service == .iCloud
                 ? "Choisis ou crée un dossier dans iCloud Drive, par exemple « Papote ». Tes fichiers y seront aussi visibles depuis le Mac."
                 : "Dans le sélecteur, touche « Parcourir » puis « Drive », et choisis ou crée un dossier. Si Drive n'apparaît pas : ouvre l'app Google Drive, connecte-toi, puis dans Fichiers > Parcourir > … > Modifier, active Drive.")
        }
        .listRowBackground(Theme.surface)
    }

    @ViewBuilder
    private func serviceAvailability(_ service: CloudService) -> some View {
        switch service {
        case .iCloud:
            if !Backup.iCloudAccountAvailable {
                Label("Aucun compte iCloud sur cet iPhone. Connecte-toi dans Réglages > ton nom.",
                      systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(Theme.rec)
            }
        case .google:
            if let app = URL(string: "googledrive://"), !UIApplication.shared.canOpenURL(app) {
                Link(destination: URL(string: "https://apps.apple.com/app/google-drive/id507874739")!) {
                    Label("Installer l'app Google Drive", systemImage: "arrow.down.app")
                }
            }
        }
    }
}
