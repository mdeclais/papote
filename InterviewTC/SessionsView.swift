import SwiftUI

struct SessionsView: View {
    @State private var sessions: [InterviewSession] = []

    private var groups: [(project: String, sessions: [InterviewSession])] {
        Dictionary(grouping: sessions) { $0.project ?? "" }
            .map { ($0.key, $0.value) }
            .sorted { a, b in
                // « Sans projet » en dernier.
                if a.project.isEmpty != b.project.isEmpty { return b.project.isEmpty }
                return a.project.localizedStandardCompare(b.project) == .orderedAscending
            }
    }

    var body: some View {
        List {
            ForEach(groups, id: \.project) { group in
                Section(group.project.isEmpty ? "Sans projet" : group.project) {
                    ForEach(group.sessions) { s in
                        NavigationLink { SessionDetailView(session: s) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "bubble.left.fill")
                                    .font(.title3)
                                    .foregroundStyle(Theme.coral)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(s.startDate.formatted(date: .abbreviated, time: .shortened))
                                        .fontWeight(.semibold)
                                        .foregroundStyle(Theme.ink)
                                    Text("\(s.tc(0)) · \(Timecode.duration(s.duration)) · \(s.segments.count) sous-titres")
                                        .font(.caption.monospacedDigit())
                                        .foregroundStyle(Theme.inkSoft)
                                }
                            }
                        }
                        .listRowBackground(Theme.surface)
                    }
                    .onDelete { offsets in
                        let doomed = offsets.map { group.sessions[$0] }
                        doomed.forEach(SessionStore.delete)
                        sessions.removeAll { s in doomed.contains { $0.id == s.id } }
                    }
                }
            }
        }
        .overlay {
            if sessions.isEmpty {
                ContentUnavailableView("Aucune interview", systemImage: "bubble.left",
                                       description: Text("Tes interviews apparaîtront ici."))
            }
        }
        .papoteList()
        .navigationTitle("Interviews")
        .onAppear { sessions = SessionStore.loadAll() }
    }
}

struct SessionDetailView: View {
    @State var session: InterviewSession
    @State private var editingSegment: Segment?
    @State private var editingMarker: Marker?
    @State private var backupStatus: String?
    @State private var backingUp = false
    @State private var initial: InterviewSession?

    var body: some View {
        List {
            Section {
                LabeledContent("Projet", value: session.project ?? "Sans projet")
                LabeledContent("TC début", value: session.tc(0))
                LabeledContent("TC fin", value: session.tc(session.duration))
                LabeledContent("Durée", value: Timecode.duration(session.duration))
                LabeledContent("Cadence", value: "\(session.fps.rawValue) i/s")
                LabeledContent("Audio", value: (session.channels == 2 ? "stéréo" : "mono")
                               + " · \(session.bitDepth ?? 24) bits")
                LabeledContent("Langue", value: session.localeID)
            }
            .font(.body.monospacedDigit())
            .listRowBackground(Theme.surface)

            if session.speakers != nil {
                Section("Locuteurs") {
                    ForEach(session.speakers!.indices, id: \.self) { i in
                        TextField("Locuteur \(i + 1)", text: Binding(
                            get: { session.speakers?[i] ?? "" },
                            set: { session.speakers?[i] = $0 }))
                        .onSubmit(save)
                    }
                }
                .listRowBackground(Theme.surface)
            }

            Section("Fichiers") {
                ShareLink(item: session.audioURL) {
                    Label("Audio WAV (BWF, TC horaire)", systemImage: "waveform")
                }
                ShareLink(item: session.srtURL) {
                    Label("Sous-titres SRT · timeline 01:00:00:00", systemImage: "captions.bubble")
                }
                ShareLink(item: session.srtTODURL) {
                    Label("Sous-titres SRT · timeline au TC du WAV", systemImage: "captions.bubble")
                }
                ShareLink(item: session.txtURL) {
                    Label("Texte horodaté", systemImage: "doc.text")
                }
                if session.markers?.isEmpty == false {
                    ShareLink(item: session.edlURL) {
                        Label("Marqueurs EDL · timeline 01:00:00:00", systemImage: "bookmark")
                    }
                    ShareLink(item: session.edlTODURL) {
                        Label("Marqueurs EDL · timeline au TC du WAV", systemImage: "bookmark")
                    }
                }
                ShareLink(items: session.exportURLs) {
                    Label("Tout partager", systemImage: "square.and.arrow.up.on.square")
                        .fontWeight(.semibold)
                }
                Button {
                    Task { await backUp() }
                } label: {
                    HStack {
                        Label("Sauvegarder maintenant", systemImage: "icloud.and.arrow.up")
                            .fontWeight(.semibold)
                        if backingUp { Spacer(); ProgressView() }
                    }
                }
                .disabled(backingUp)
                if let backupStatus {
                    Text(backupStatus).font(.footnote).foregroundStyle(Theme.inkSoft)
                }
            }
            .listRowBackground(Theme.surface)

            Section {
                ForEach(session.items) { item in
                    Button {
                        switch item {
                        case .segment(let s): editingSegment = s
                        case .marker(let m): editingMarker = m
                        }
                    } label: {
                        TranscriptRow(item: item, session: session)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 5, leading: 4, bottom: 5, trailing: 4))
                }
            } header: {
                Text("Transcription")
            } footer: {
                Text("Touche une phrase ou un marqueur pour le corriger. Les fichiers SRT, texte et EDL sont réécrits à chaque modification.")
            }
        }
        .papoteList()
        .navigationTitle(session.id)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editingSegment) { seg in
            SegmentEditor(segment: seg, speakers: session.speakers, tc: session.tc(seg.start)) { result in
                if let i = session.segments.firstIndex(where: { $0.id == seg.id }) {
                    if let result { session.segments[i] = result } else { session.segments.remove(at: i) }
                    save()
                }
            }
        }
        .sheet(item: $editingMarker) { marker in
            MarkerEditor(marker: marker,
                         tc: marker.duration == nil ? session.tc(marker.time)
                             : "\(session.tc(marker.time)) → \(session.tc(marker.end))") { result in
                if let i = session.markers?.firstIndex(where: { $0.id == marker.id }) {
                    if let result { session.markers?[i] = result } else { session.markers?.remove(at: i) }
                    save()
                }
            }
        }
        .onAppear { if initial == nil { initial = session } }
        .onDisappear {
            save()
            // Corrections faites ici : on renvoie aussi la nouvelle version vers le cloud.
            if session != initial, Backup.automatic, !Backup.destinations.isEmpty {
                let edited = session
                initial = session
                Task { _ = await Backup.runInBackground(edited) }
            }
        }
    }

    private func save() {
        SessionStore.save(session)
        SessionStore.writeExports(session)
    }

    private func backUp() async {
        save()
        guard !Backup.destinations.isEmpty else {
            backupStatus = "Aucun dossier de sauvegarde : ajoute-en un dans les réglages (roue crantée)."
            return
        }
        backingUp = true
        let report = await Backup.runInBackground(session) { texts in
            if texts.failed.isEmpty { backupStatus = "Textes sauvegardés · envoi de l'audio…" }
        }
        backingUp = false
        backupStatus = report.summary
        if report.failed.isEmpty {
            session.backupPending = false
            SessionStore.save(session)
        }
    }
}

/// `onDone(nil)` = supprimer.
struct SegmentEditor: View {
    @State var segment: Segment
    let speakers: [String]?
    let tc: String
    let onDone: (Segment?) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section(tc) {
                    TextField("Texte", text: $segment.text, axis: .vertical)
                        .lineLimit(3...12)
                }
                if let speakers {
                    Picker("Locuteur", selection: $segment.speaker) {
                        Text("Aucun").tag(Int?.none)
                        ForEach(speakers.indices, id: \.self) { Text(speakers[$0]).tag(Int?.some($0)) }
                    }
                }
                Section {
                    Button("Supprimer la phrase", role: .destructive) {
                        onDone(nil)
                        dismiss()
                    }
                }
            }
            .papoteList()
            .navigationTitle("Corriger")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") {
                        onDone(segment)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct MarkerEditor: View {
    @State var marker: Marker
    let tc: String
    let onDone: (Marker?) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section(tc) {
                    TextField("Nom du marqueur", text: $marker.name)
                    if let duration = marker.duration {
                        LabeledContent("Durée", value: Timecode.duration(duration))
                            .monospacedDigit()
                    }
                }
                Section {
                    Button("Supprimer le marqueur", role: .destructive) {
                        onDone(nil)
                        dismiss()
                    }
                }
            }
            .papoteList()
            .navigationTitle("Marqueur")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") {
                        onDone(marker)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
