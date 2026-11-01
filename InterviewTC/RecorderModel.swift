import AVFoundation
import Observation
import Speech

struct AudioInput: Identifiable, Hashable {
    let id: String      // uid du port (iPhone) ou du périphérique Core Audio (Mac)
    let name: String
    let isBuiltIn: Bool
}

@MainActor @Observable
final class RecorderModel {
    enum Phase { case idle, preparing, recording, finishing }

    var phase: Phase = .idle
    var status: String?
    var errorMessage: String?
    /// Interview en cours, ou la dernière terminée tant qu'on n'a pas effacé l'écran.
    var session: InterviewSession?
    var volatileText = ""
    var volatileStart: Double = 0
    var level: Float = 0
    var locales: [Locale] = []
    var projects: [String] = []
    var inputs: [AudioInput] = []
    var currentSpeaker = 0
    /// Résultat de la dernière sauvegarde cloud, affiché sous les commandes.
    var backupStatus: String?
    var backupRunning = false

    var localeID: String { didSet { defaults.set(localeID, forKey: "localeID") } }
    var fps: FrameRate { didSet { defaults.set(fps.rawValue, forKey: "fps") } }
    /// "" = sans projet.
    var project: String { didSet { defaults.set(project, forKey: "project") } }
    var speakersEnabled: Bool { didSet { defaults.set(speakersEnabled, forKey: "speakersEnabled") } }
    /// Index 0 : l'intervieweur ; ensuite 1 à `maxGuests` invités.
    var speakerNames: [String] { didSet { defaults.set(speakerNames, forKey: "speakerNames") } }
    static let maxGuests = 5

    var guestCount: Int {
        get { speakerNames.count - 1 }
        set {
            let n = min(max(newValue, 1), Self.maxGuests)
            var names = speakerNames
            if names.count - 1 > n { names.removeLast(names.count - 1 - n) }
            while names.count - 1 < n { names.append("Invité \(names.count)") }
            // Un seul invité : « Invité » tout court, comme avant.
            if n > 1, names[1] == "Invité" { names[1] = "Invité 1" }
            if n == 1, names[1] == "Invité 1" { names[1] = "Invité" }
            speakerNames = names
            if currentSpeaker > n { currentSpeaker = 0 }
        }
    }
    /// "" = entrée par défaut du système.
    var inputUID: String { didSet { defaults.set(inputUID, forKey: "inputUID") } }
    var stereo: Bool { didSet { defaults.set(stereo, forKey: "stereo") } }
    /// 16 bits par défaut : un tiers plus léger que 24 bits, largement assez pour la voix.
    var bitDepth: Int { didSet { defaults.set(bitDepth, forKey: "bitDepth") } }
    var speakerNamesInSRT: Bool {
        didSet { defaults.set(speakerNamesInSRT, forKey: SessionStore.speakerNamesInSRTKey) }
    }

    private let defaults = UserDefaults.standard
    private var pipeline: AudioPipeline?
    private var transcriber: LiveTranscriber?
    private var observers: [NSObjectProtocol] = []
    private var routeObserver: NSObjectProtocol?
    private var autosaveTask: Task<Void, Never>?
    private var recordingSince = Date.distantPast
    /// Changements de locuteur pendant l'enregistrement : (temps, index).
    private var speakerChanges: [(time: Double, speaker: Int)] = []

    init() {
        localeID = defaults.string(forKey: "localeID") ?? "fr-FR"
        fps = FrameRate(rawValue: defaults.integer(forKey: "fps")) ?? .fps25
        project = defaults.string(forKey: "project") ?? ""
        speakersEnabled = defaults.object(forKey: "speakersEnabled") as? Bool ?? true
        let names = defaults.stringArray(forKey: "speakerNames") ?? []
        speakerNames = names.count >= 2 ? Array(names.prefix(Self.maxGuests + 1)) : ["Intervieweur", "Invité"]
        inputUID = defaults.string(forKey: "inputUID") ?? ""
        stereo = defaults.bool(forKey: "stereo")
        bitDepth = defaults.object(forKey: "bitDepth") as? Int ?? 16
        speakerNamesInSRT = defaults.object(forKey: SessionStore.speakerNamesInSRTKey) as? Bool ?? true

        #if os(iOS)
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                if self?.phase == .idle { self?.refreshInputs() }
            }
        }
        #else
        MacAudio.onDevicesChanged { [weak self] in
            Task { @MainActor in
                if self?.phase == .idle { self?.refreshInputs() }
            }
        }
        #endif
    }

    var currentInputName: String {
        inputs.first { $0.id == inputUID }?.name ?? "Micro par défaut"
    }

    func displayName(_ locale: Locale) -> String {
        Locale.current.localizedString(forIdentifier: locale.identifier) ?? locale.identifier
    }

    func loadLocales() async {
        let all = await LiveTranscriber.supportedLocales()
        locales = all.sorted { displayName($0) < displayName($1) }
        if !locales.contains(where: { $0.identifier(.bcp47) == localeID }),
           let fallback = locales.first(where: { $0.identifier(.bcp47) == "fr-FR" }) ?? locales.first {
            localeID = fallback.identifier(.bcp47)
        }
    }

    // MARK: Projets

    func refreshProjects() {
        projects = SessionStore.projects()
        if !project.isEmpty, !projects.contains(project) { project = "" }
    }

    func createProject(_ name: String) {
        do {
            project = try SessionStore.createProject(name)
            refreshProjects()
        } catch {
            errorMessage = "Nom de projet invalide."
        }
    }

    // MARK: Micro

    func refreshInputs() {
        #if os(macOS)
        inputs = MacAudio.inputs()
        #else
        let av = AVAudioSession.sharedInstance()
        try? av.setCategory(.record, mode: .default, options: [.allowBluetoothHFP])
        inputs = (av.availableInputs ?? []).map {
            AudioInput(id: $0.uid, name: $0.portName, isBuiltIn: $0.portType == .builtInMic)
        }
        #endif
        if !inputUID.isEmpty, !inputs.contains(where: { $0.id == inputUID }) { inputUID = "" }
    }

    private func configureSession() throws {
        #if os(iOS)
        let av = AVAudioSession.sharedInstance()
        try av.setCategory(.record, mode: .default, options: [.allowBluetoothHFP])
        try? av.setPreferredSampleRate(48_000)
        let port = av.availableInputs?.first { $0.uid == inputUID }
        try av.setPreferredInput(port)

        // Micro intégré en stéréo : il faut choisir une source qui gère ce mode.
        if stereo, let mic = port ?? av.availableInputs?.first(where: { $0.portType == .builtInMic }),
           mic.portType == .builtInMic,
           let source = mic.dataSources?.first(where: { $0.supportedPolarPatterns?.contains(.stereo) == true }) {
            try? source.setPreferredPolarPattern(.stereo)
            try? mic.setPreferredDataSource(source)
            if port == nil { try? av.setPreferredInput(mic) }
        }
        try av.setActive(true)
        if stereo {
            try? av.setPreferredInputOrientation(.portrait)
            if av.maximumInputNumberOfChannels >= 2 { try? av.setPreferredInputNumberOfChannels(2) }
        }
        #endif
    }

    private func deactivateSession() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    // MARK: Enregistrement

    func toggle() async {
        switch phase {
        case .idle: await start()
        case .recording: await stop()
        default: break
        }
    }

    /// Efface l'écran ; les fichiers de l'interview restent dans la liste.
    func clearScreen() {
        guard phase == .idle else { return }
        session = nil
        volatileText = ""
    }

    func start() async {
        guard phase == .idle else { return }
        errorMessage = nil
        session = nil
        volatileText = ""
        openIn = nil
        phase = .preparing
        do {
            guard await AVAudioApplication.requestRecordPermission() else { throw RecorderError.micDenied }
            try configureSession()

            // Sans transcription (langue absente, appareil non compatible),
            // on enregistre quand même l'audio horodaté.
            let transcriber = LiveTranscriber()
            transcriber.onResult = { [weak self] result in self?.handle(result) }
            status = "Préparation de la transcription…"
            do {
                try await transcriber.prepare(locale: Locale(identifier: localeID))
                self.transcriber = transcriber
            } catch {
                self.transcriber = nil
                errorMessage = "Transcription désactivée : \(error.localizedDescription)\nL'audio est enregistré normalement."
            }
            status = nil

            let project = self.project.isEmpty ? nil : self.project
            let id = SessionStore.makeID(for: .now, project: project)
            try SessionStore.createFolder(id, project: project)
            let template = InterviewSession(id: id, startDate: .now, startTOD: 0, sampleRate: 0,
                                            fps: fps, localeID: localeID, project: project,
                                            speakers: speakersEnabled ? speakerNames : nil, markers: [])
            speakerChanges = [(0, currentSpeaker)]

            let pipeline = AudioPipeline(audioURL: template.audioURL,
                                         description: "Interview \(id)" + (project.map { " · \($0)" } ?? ""),
                                         channels: stereo ? 2 : 1,
                                         bitDepth: bitDepth,
                                         transcriber: self.transcriber)
            pipeline.onStart = { [weak self] info in
                Task { @MainActor in
                    var s = template
                    s.startDate = info.date
                    s.startTOD = Double(info.timeReference) / info.sampleRate
                    s.sampleRate = info.sampleRate
                    s.channels = info.channels
                    s.bitDepth = self?.bitDepth
                    self?.session = s
                    SessionStore.save(s)
                }
            }
            pipeline.onLevel = { [weak self] level in
                Task { @MainActor in self?.level = level }
            }
            pipeline.onError = { [weak self] error in
                Task { @MainActor in
                    self?.errorMessage = error.localizedDescription
                    await self?.stop()
                }
            }
            #if os(macOS)
            try MacAudio.select(inputUID, on: pipeline.engine)
            #endif
            try pipeline.start()
            self.pipeline = pipeline
            recordingSince = .now
            observe(pipeline.engine)
            Platform.keepAwake(true)
            phase = .recording
            autosaveTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(5))
                    self?.autosave()
                }
            }
        } catch {
            errorMessage = error.localizedDescription
            status = nil
            transcriber = nil
            phase = .idle
            deactivateSession()
        }
    }

    func stop() async {
        guard phase == .recording, let pipeline else { return }
        phase = .finishing
        status = "Finalisation…"
        autosaveTask?.cancel()
        autosaveTask = nil
        let duration = pipeline.stop()
        if let start = openIn { closeExtract(start: start, end: duration) }
        await transcriber?.finish()
        volatileText = ""

        if var s = session {
            s.duration = duration
            s.finished = true
            s.backupPending = Backup.automatic && !Backup.destinations.isEmpty
            session = s
            SessionStore.save(s)
            SessionStore.writeExports(s)
            if s.backupPending == true { Task { await backUp(s) } }
        }

        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        self.pipeline = nil
        transcriber = nil
        level = 0
        status = nil
        Platform.keepAwake(false)
        deactivateSession()
        phase = .idle
    }

    /// Toutes les 5 s : session.json, SRT, texte et EDL à jour. Le WAV, lui,
    /// remet ses tailles d'en-tête à jour au même rythme (BWFWriter).
    private func autosave() {
        guard phase == .recording, var s = session else { return }
        s.duration = now
        session = s
        SessionStore.save(s)
        SessionStore.writeExports(s)
    }

    /// Copie l'interview vers iCloud Drive, Google Drive… et note le résultat.
    func backUp(_ s: InterviewSession) async {
        guard !Backup.destinations.isEmpty else {
            backupStatus = "Aucun dossier de sauvegarde : ajoute-en un dans les réglages."
            return
        }
        backupRunning = true
        backupStatus = "Sauvegarde de \(s.id)…"
        let report = await Backup.runInBackground(s) { [weak self] texts in
            if texts.failed.isEmpty {
                self?.backupStatus = "Textes sauvegardés (\(texts.succeeded.joined(separator: ", "))) · envoi de l'audio…"
            }
        }
        backupRunning = false
        backupStatus = report.summary
        if report.failed.isEmpty {
            var done = s
            done.backupPending = false
            SessionStore.save(done)
            if session?.id == s.id { session?.backupPending = false }
        }
    }

    /// Au lancement : relance les sauvegardes restées en échec (pas de réseau,
    /// app fermée pendant la copie…).
    func retryPendingBackups() async {
        for s in SessionStore.loadAll() where s.backupPending == true && s.finished == true {
            await backUp(s)
        }
    }

    /// Au lancement : répare les interviews coupées par un plantage et
    /// réaffiche la plus récente.
    func recoverInterrupted() {
        let recovered = SessionStore.recoverInterrupted()
        guard let last = recovered.max(by: { $0.startDate < $1.startDate }), phase == .idle else { return }
        session = last
        errorMessage = "L'interview \(last.id) avait été interrompue. Elle a été récupérée jusqu'à \(last.tc(last.duration)) (fin de l'audio sur le disque)."
    }

    /// Position courante dans l'enregistrement, en secondes.
    private var now: Double {
        session.map { Date().timeIntervalSince($0.startDate) } ?? 0
    }

    /// Début de l'extrait en cours (IN posé, OUT pas encore), en secondes.
    var openIn: Double?

    /// IN : ouvre un extrait. Un second IN avant OUT le redémarre ici.
    func markIn() {
        guard phase == .recording, session != nil else { return }
        openIn = now
        Platform.feedback(.light)
    }

    /// OUT : ferme l'extrait ouvert et l'enregistre comme marqueur avec durée.
    func markOut() {
        guard let start = openIn else { return }
        closeExtract(start: start, end: now)
        Platform.feedback(.strong)
    }

    private func closeExtract(start: Double, end: Double) {
        openIn = nil
        // Lire `session` avant de l'écrire : les deux dans la même expression
        // violent l'exclusivité d'accès et Swift tue l'app.
        guard var s = session else { return }
        let marker = Marker(time: start, name: "Extrait \((s.markers?.count ?? 0) + 1)",
                            duration: max(end - start, 1 / Double(s.fps.rawValue)))
        s.markers = (s.markers ?? []) + [marker]
        session = s
        SessionStore.save(s)
    }

    func setSpeaker(_ index: Int) {
        guard index != currentSpeaker else { return }
        currentSpeaker = index
        if phase == .recording { speakerChanges.append((now, index)) }
        Platform.feedback(.selection)
    }

    private func speaker(at time: Double) -> Int? {
        guard session?.speakers != nil else { return nil }
        return speakerChanges.last { $0.time <= time }?.speaker ?? speakerChanges.first?.speaker
    }

    private func handle(_ result: SpeechTranscriber.Result) {
        if result.isFinal {
            volatileText = ""
            guard session != nil else { return }
            var cues = CueBuilder.cues(from: result.text, fallback: result.range)
            for i in cues.indices { cues[i].speaker = speaker(at: cues[i].start) }
            session?.segments += cues
            if let session { SessionStore.save(session) }
        } else {
            volatileText = String(result.text.characters)
            volatileStart = result.range.start.seconds
        }
    }

    /// Appel, alarme, ou micro branché/débranché : on arrête proprement plutôt
    /// que de perdre l'enregistrement ou de décaler le timecode.
    private func observe(_ engine: AVAudioEngine) {
        let nc = NotificationCenter.default
        #if os(iOS)
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification,
                                        object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            Task { @MainActor in
                self?.errorMessage = "Enregistrement interrompu (appel, alarme…). Les fichiers sont sauvegardés."
                await self?.stop()
            }
        })
        #endif
        observers.append(nc.addObserver(forName: .AVAudioEngineConfigurationChange,
                                        object: engine, queue: .main) { [weak self] _ in
            Task { @MainActor in
                // La configuration du micro au démarrage peut en déclencher un tardif.
                guard let self, Date().timeIntervalSince(self.recordingSince) > 1.5 else { return }
                self.errorMessage = "Entrée audio modifiée (micro branché ou débranché). Enregistrement arrêté, fichiers sauvegardés."
                await self.stop()
            }
        })
    }
}
