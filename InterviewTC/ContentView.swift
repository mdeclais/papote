import SwiftUI

@main
struct InterviewTCApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .tint(Theme.coral)
                .fontDesign(.rounded)
        }
        #if os(macOS)
        .defaultSize(width: 460, height: 820)
        .windowResizability(.contentMinSize)
        #endif
    }
}

struct ContentView: View {
    @State private var model = RecorderModel()
    @State private var showSettings = false
    @State private var showNewProject = false
    @State private var newProjectName = ""

    private var isRecording: Bool { model.phase == .recording }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                transcript
                controls
            }
            .background(Theme.background.ignoresSafeArea())
            .papoteNavigationBar()
            .toolbar {
                ToolbarItem(placement: .principal) {
                    HStack(spacing: 6) {
                        Image(systemName: "bubble.left.fill").foregroundStyle(Theme.coral)
                        Text("Papote").font(.headline.weight(.heavy)).foregroundStyle(Theme.ink)
                    }
                }
                ToolbarItem(placement: .papoteLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .disabled(model.phase != .idle)
                }
                ToolbarItem(placement: .papoteTrailing) {
                    NavigationLink { SessionsView() } label: { Image(systemName: "list.bullet") }
                        .disabled(model.phase != .idle)
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView(model: model) }
            .task {
                model.recoverInterrupted()
                model.refreshProjects()
                model.refreshInputs()
                await model.loadLocales()
                await model.retryPendingBackups()
            }
            .alert("Nouveau projet", isPresented: $showNewProject) {
                TextField("Nom du projet", text: $newProjectName)
                Button("Annuler", role: .cancel) {}
                Button("Créer") { model.createProject(newProjectName) }
            } message: {
                Text("Les interviews de ce projet seront rangées dans son dossier, avec son nom en tête des fichiers.")
            }
            .alert("Attention", isPresented: Binding(
                get: { model.errorMessage != nil },
                set: { if !$0 { model.errorMessage = nil } })
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.errorMessage ?? "")
            }
        }
    }

    // MARK: En-tête

    private var header: some View {
        VStack(spacing: 12) {
            TimelineView(.periodic(from: .now, by: 1.0 / Double(model.fps.rawValue))) { context in
                Text(Timecode.string(Timecode.secondsSinceMidnight(context.date), fps: model.fps))
                    .font(.system(size: 48, weight: .heavy, design: .rounded).monospacedDigit())
                    .foregroundStyle(isRecording ? Theme.rec : Theme.ink)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }

            if isRecording, let s = model.session {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    HStack(spacing: 8) {
                        RecBadge()
                        Text("depuis \(s.tc(0))").foregroundStyle(Theme.inkSoft)
                        Spacer()
                        Text(Timecode.duration(context.date.timeIntervalSince(s.startDate)))
                            .fontWeight(.bold)
                            .foregroundStyle(Theme.ink)
                    }
                    .font(.subheadline.monospacedDigit())
                }
                WaveMeter(level: model.level)
                Text("\(s.project ?? "Sans projet") · \(model.currentInputName) · \(s.channels == 2 ? "stéréo" : "mono")")
                    .font(.caption)
                    .foregroundStyle(Theme.inkSoft)
                    .lineLimit(1)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        projectMenu
                        Menu {
                            Picker("Langue", selection: $model.localeID) {
                                ForEach(model.locales, id: \.self) { locale in
                                    Text(model.displayName(locale)).tag(locale.identifier(.bcp47))
                                }
                            }
                        } label: {
                            ChipLabel(text: model.locales.first { $0.identifier(.bcp47) == model.localeID }
                                .map(model.displayName) ?? model.localeID, systemImage: "globe")
                        }
                        Menu {
                            Picker("Images/s", selection: $model.fps) {
                                ForEach(FrameRate.allCases) { Text("\($0.rawValue) i/s").tag($0) }
                            }
                        } label: {
                            ChipLabel(text: "\(model.fps.rawValue) i/s", systemImage: "film")
                        }
                        Button { showSettings = true } label: {
                            ChipLabel(text: model.currentInputName + (model.stereo ? " · stéréo" : ""),
                                      systemImage: "mic")
                        }
                    }
                }
                .disabled(model.phase != .idle)
            }

            if let status = model.status {
                HStack(spacing: 8) {
                    ProgressView().tint(Theme.ink)
                    Text(status).font(.footnote).foregroundStyle(Theme.ink)
                }
            }
        }
        .padding(16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.top, 4)
    }

    private var projectMenu: some View {
        Menu {
            Picker("Projet", selection: $model.project) {
                Text("Sans projet").tag("")
                ForEach(model.projects, id: \.self) { Text($0).tag($0) }
            }
            Divider()
            Button {
                newProjectName = ""
                showNewProject = true
            } label: {
                Label("Nouveau projet…", systemImage: "folder.badge.plus")
            }
        } label: {
            ChipLabel(text: model.project.isEmpty ? "Sans projet" : model.project, systemImage: "folder")
        }
    }

    // MARK: Transcription

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    if let s = model.session {
                        ForEach(s.items) { item in
                            TranscriptRow(item: item, session: s)
                        }
                        if !model.volatileText.isEmpty {
                            SegmentRow(tc: s.tc(model.volatileStart),
                                       speaker: s.speakers != nil ? s.speakerName(model.currentSpeaker) : nil,
                                       interviewer: s.speakers != nil && model.currentSpeaker == 0,
                                       text: model.volatileText, volatile: true)
                        }
                        if let start = model.openIn {
                            OpenExtractRow(session: s, start: start)
                        }
                    } else if model.phase == .idle {
                        EmptyTranscript()
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .onChange(of: model.session?.segments.count) {
                withAnimation { proxy.scrollTo("bottom") }
            }
            .onChange(of: model.session?.markers?.count) {
                withAnimation { proxy.scrollTo("bottom") }
            }
            .onChange(of: model.openIn) {
                withAnimation { proxy.scrollTo("bottom") }
            }
            .onChange(of: model.volatileText) {
                proxy.scrollTo("bottom")
            }
        }
    }

    // MARK: Commandes

    private var controls: some View {
        VStack(spacing: 14) {
            if model.speakersEnabled, model.phase != .finishing {
                // Une ligne jusqu'à 3 personnes, puis une grille de 3 colonnes.
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10),
                                         count: min(model.speakerNames.count, 3)), spacing: 10) {
                    ForEach(model.speakerNames.indices, id: \.self) { i in
                        let active = model.currentSpeaker == i
                        Button {
                            model.setSpeaker(i)
                        } label: {
                            Label(model.speakerNames[i], systemImage: active ? "waveform" : "person")
                                .font(.subheadline.weight(active ? .bold : .medium))
                                .lineLimit(1)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(active ? Theme.coral : Theme.surface, in: Capsule())
                                .foregroundStyle(active ? Theme.cream : Theme.ink)
                        }
                        .buttonStyle(.plain)
                        .keyboardShortcut(KeyEquivalent(Character("\(i + 1)")), modifiers: [])
                    }
                }
            }

            HStack {
                Group {
                    if isRecording {
                        ExtractButton(title: "IN", systemImage: "arrow.right.to.line",
                                      active: model.openIn != nil) { model.markIn() }
                            .disabled(model.session == nil)
                    } else if model.phase == .idle, model.session != nil {
                        RoundAction(title: "Effacer", systemImage: "arrow.counterclockwise") { model.clearScreen() }
                    }
                }
                .frame(width: 84)

                Spacer()
                RecordButton(recording: isRecording) {
                    Task { await model.toggle() }
                }
                .disabled(model.phase == .preparing || model.phase == .finishing)
                Spacer()

                Group {
                    if isRecording {
                        ExtractButton(title: "OUT", systemImage: "arrow.left.to.line", active: false) {
                            model.markOut()
                        }
                        .disabled(model.openIn == nil)
                        .opacity(model.openIn == nil ? 0.4 : 1)
                    } else if model.phase == .idle, let s = model.session {
                        ShareLink(items: s.exportURLs) {
                            RoundActionLabel(title: "Partager", systemImage: "square.and.arrow.up")
                        }
                    }
                }
                .frame(width: 84)
            }
        }
        .overlay(alignment: .top) {
            if let status = model.backupStatus, model.phase == .idle {
                BackupStatusChip(text: status, running: model.backupRunning)
                    .offset(y: -44)
                    .onTapGesture { model.backupStatus = nil }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 8)
        .background(Theme.card.opacity(0.35).ignoresSafeArea(edges: .bottom))
    }
}

// MARK: - Transcription

struct TranscriptRow: View {
    let item: TranscriptItem
    let session: InterviewSession

    var body: some View {
        switch item {
        case .segment(let seg):
            SegmentRow(tc: session.tc(seg.start), speaker: session.speakerName(seg.speaker),
                       interviewer: session.speakers != nil && seg.speaker == 0, text: seg.text)
        case .marker(let m):
            MarkerRow(marker: m, session: session)
        }
    }
}

/// Une phrase en bulle, comme sur l'icône : l'invité à gauche en corail,
/// l'intervieweur à droite en jaune pâle.
struct SegmentRow: View {
    let tc: String
    var speaker: String?
    var interviewer = false
    let text: String
    var volatile = false

    var body: some View {
        VStack(alignment: interviewer ? .trailing : .leading, spacing: 3) {
            HStack(spacing: 6) {
                if let speaker { Text(speaker).fontWeight(.bold) }
                Text(tc).monospacedDigit()
            }
            .font(.caption2)
            .foregroundStyle(Theme.inkSoft)
            .padding(.horizontal, 6)

            Text(text)
                .italic(volatile)
                .textSelection(.enabled)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(interviewer ? Theme.bubbleAlt : Theme.coral, in: bubble)
                .foregroundStyle(interviewer ? Theme.ink : Theme.cream)
                .opacity(volatile ? 0.6 : 1)
        }
        .frame(maxWidth: .infinity, alignment: interviewer ? .trailing : .leading)
        .padding(interviewer ? .leading : .trailing, 36)
    }

    /// Coin pointu du côté de la personne qui parle, comme la queue de la bulle du logo.
    private var bubble: UnevenRoundedRectangle {
        UnevenRoundedRectangle(topLeadingRadius: 18,
                               bottomLeadingRadius: interviewer ? 18 : 4,
                               bottomTrailingRadius: interviewer ? 4 : 18,
                               topTrailingRadius: 18, style: .continuous)
    }
}

struct MarkerRow: View {
    let marker: Marker
    let session: InterviewSession

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "bookmark.fill").foregroundStyle(Theme.coral)
            VStack(alignment: .leading, spacing: 1) {
                Text(marker.name).font(.subheadline.weight(.bold))
                Group {
                    if let duration = marker.duration {
                        Text("\(session.tc(marker.time)) → \(session.tc(marker.end)) · \(Timecode.duration(duration))")
                    } else {
                        Text(session.tc(marker.time))
                    }
                }
                .font(.caption2.monospacedDigit())
                .foregroundStyle(Theme.inkSoft)
            }
        }
        .foregroundStyle(Theme.ink)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Theme.card, in: Capsule())
        .frame(maxWidth: .infinity)
    }
}

/// Extrait ouvert par IN, en attente de OUT.
struct OpenExtractRow: View {
    let session: InterviewSession
    let start: Double

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let elapsed = context.date.timeIntervalSince(session.startDate) - start
            HStack(spacing: 6) {
                Image(systemName: "record.circle")
                Text("IN \(session.tc(start)) · extrait en cours \(Timecode.duration(elapsed))")
                    .monospacedDigit()
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(Theme.rec)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .overlay(Capsule().strokeBorder(Theme.rec, style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
            .frame(maxWidth: .infinity)
        }
    }
}

struct EmptyTranscript: View {
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "bubble.left.and.text.bubble.right.fill")
                .font(.system(size: 54))
                .foregroundStyle(Theme.coral, Theme.card)
            Text("Prêt à papoter ?")
                .font(.title3.weight(.heavy))
                .foregroundStyle(Theme.ink)
            Text("Touche le bouton rouge pour lancer l'interview. Le timecode suit l'heure locale et la transcription s'affiche ici en direct.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(Theme.inkSoft)
        }
        .padding(.top, 50)
        .padding(.horizontal, 20)
    }
}

// MARK: - Commandes

/// Le point REC de l'icône : rond rouge et cœur crème ; carré crème pendant l'enregistrement.
struct RecordButton: View {
    let recording: Bool
    let action: () -> Void
    @State private var pulse = false

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(Theme.rec.opacity(recording ? 0.25 : 0))
                    .frame(width: 96, height: 96)
                    .scaleEffect(pulse ? 1.12 : 0.92)
                Circle().fill(Theme.card).frame(width: 84, height: 84)
                Circle().fill(Theme.rec).frame(width: 70, height: 70)
                if recording {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(Theme.cream).frame(width: 26, height: 26)
                } else {
                    Circle().fill(Theme.cream).frame(width: 26, height: 26)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(recording ? "Arrêter" : "Enregistrer")
        .onChange(of: recording, initial: true) {
            if recording {
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true }
            } else {
                withAnimation(.default) { pulse = false }
            }
        }
    }
}

struct ExtractButton: View {
    let title: String
    let systemImage: String
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: systemImage).font(.headline)
                Text(title).font(.subheadline.weight(.heavy))
            }
            .frame(width: 72, height: 56)
            .background(active ? Theme.coral : Theme.surface,
                        in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .foregroundStyle(active ? Theme.cream : Theme.coral)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title == "IN" ? "Début d'extrait" : "Fin d'extrait")
    }
}

struct RoundAction: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) { RoundActionLabel(title: title, systemImage: systemImage) }
            .buttonStyle(.plain)
    }
}

struct RoundActionLabel: View {
    let title: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: systemImage)
                .font(.title3.weight(.semibold))
                .frame(width: 48, height: 48)
                .background(Theme.surface, in: Circle())
            Text(title).font(.caption.weight(.semibold))
        }
        .foregroundStyle(Theme.coral)
    }
}

/// Vumètre en barres arrondies, comme l'onde de l'icône.
struct WaveMeter: View {
    let level: Float
    private let profile: [CGFloat] = [0.3, 0.5, 0.75, 0.55, 0.9, 1, 0.7, 0.95, 0.6, 0.8, 0.45, 0.65, 0.35]

    var body: some View {
        HStack(spacing: 5) {
            ForEach(profile.indices, id: \.self) { i in
                Capsule()
                    .fill(level > 0.9 ? Theme.rec : Theme.coral)
                    .frame(width: 7, height: max(7, 38 * profile[i] * CGFloat(level)))
            }
        }
        .frame(height: 38)
        .frame(maxWidth: .infinity)
        .animation(.easeOut(duration: 0.1), value: level)
    }
}

/// Résultat de la sauvegarde cloud ; toucher pour la masquer.
struct BackupStatusChip: View {
    let text: String
    let running: Bool

    var body: some View {
        HStack(spacing: 6) {
            if running {
                ProgressView().controlSize(.mini).tint(Theme.ink)
            } else {
                Image(systemName: text.contains("Échec") || text.contains("Aucun")
                      ? "exclamationmark.icloud" : "checkmark.icloud")
            }
            Text(text).lineLimit(3)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(Theme.ink)
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(Theme.surface, in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.card, lineWidth: 1))
    }
}

/// Pastille « REC » qui clignote doucement.
struct RecBadge: View {
    @State private var on = true

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(Theme.cream).frame(width: 7, height: 7).opacity(on ? 1 : 0.3)
            Text("REC").font(.caption.weight(.heavy))
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(Theme.rec, in: Capsule())
        .foregroundStyle(Theme.cream)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { on = false }
        }
    }
}
