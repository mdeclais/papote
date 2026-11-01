import Foundation

struct Segment: Identifiable, Codable, Hashable {
    var id = UUID()
    /// Secondes depuis le premier échantillon enregistré.
    var start: Double
    var end: Double
    var text: String
    /// Index dans `InterviewSession.speakers`, nil si les locuteurs sont désactivés.
    var speaker: Int?
}

/// Extrait posé avec IN / OUT. `duration` nil : marqueur ponctuel (versions précédentes).
struct Marker: Identifiable, Codable, Hashable {
    var id = UUID()
    var time: Double
    var name: String
    var duration: Double?

    var end: Double { time + (duration ?? 0) }
}

/// Ligne affichée dans une transcription : un sous-titre ou un marqueur, triés par temps.
enum TranscriptItem: Identifiable {
    case segment(Segment)
    case marker(Marker)

    var id: UUID {
        switch self {
        case .segment(let s): s.id
        case .marker(let m): m.id
        }
    }

    var time: Double {
        switch self {
        case .segment(let s): s.start
        case .marker(let m): m.time
        }
    }
}

struct InterviewSession: Codable, Identifiable, Hashable {
    var id: String
    var startDate: Date
    /// Heure du premier échantillon, en secondes depuis minuit. Vaut exactement
    /// TimeReference / sampleRate du WAV, pour que texte et audio tombent au même TC.
    var startTOD: Double
    var sampleRate: Double
    var fps: FrameRate
    var localeID: String
    var duration: Double = 0
    var segments: [Segment] = []
    // Optionnels : les sessions enregistrées par la première version n'ont pas ces champs.
    var project: String?
    var speakers: [String]?
    var markers: [Marker]?
    var channels: Int?
    var bitDepth: Int?
    /// true une fois l'enregistrement arrêté normalement.
    var finished: Bool?
    /// true tant que la copie vers les dossiers de sauvegarde n'a pas réussi.
    var backupPending: Bool?

    func tc(_ offset: Double) -> String { Timecode.string(startTOD + offset, fps: fps) }

    /// Durée d'un marqueur en images, au moins 1.
    func frames(_ m: Marker) -> Int { max(1, Int(((m.duration ?? 0) * Double(fps.rawValue)).rounded())) }

    func speakerName(_ index: Int?) -> String? {
        guard let index, let speakers, speakers.indices.contains(index) else { return nil }
        return speakers[index]
    }

    var items: [TranscriptItem] {
        (segments.map(TranscriptItem.segment) + (markers ?? []).map(TranscriptItem.marker))
            .sorted { $0.time < $1.time }
    }

    var folder: URL { SessionStore.folder(for: id, project: project) }
    var audioURL: URL { folder.appending(path: "\(id).wav") }
    /// Pour une timeline qui démarre à 01:00:00:00 avec le WAV posé au début.
    var srtURL: URL { folder.appending(path: "\(id).srt") }
    /// Pour une timeline dont le TC de départ est celui du WAV (heure du jour).
    var srtTODURL: URL { folder.appending(path: "\(id)_TC-horaire.srt") }
    var txtURL: URL { folder.appending(path: "\(id).txt") }
    /// Pour une timeline qui démarre à 01:00:00:00 avec le WAV posé au début.
    var edlURL: URL { folder.appending(path: "\(id)_marqueurs.edl") }
    /// Pour une timeline dont le TC de départ est celui du WAV (heure du jour).
    var edlTODURL: URL { folder.appending(path: "\(id)_marqueurs_TC-horaire.edl") }

    /// Fichiers légers, envoyés en premier lors d'une sauvegarde.
    var textURLs: [URL] { exportURLs.filter { $0 != audioURL } }

    var exportURLs: [URL] {
        [audioURL, srtURL, srtTODURL, txtURL, edlURL, edlTODURL].filter { FileManager.default.fileExists(atPath: $0.path) }
    }
}

/// Documents/<projet>/<interview>/ — ou Documents/<interview>/ sans projet.
/// Tout est visible dans l'app Fichiers. Sur Mac : ~/Documents/Papote/.
enum SessionStore {
    static let speakerNamesInSRTKey = "speakerNamesInSRT"

    static var root: URL {
        #if os(macOS)
        URL.documentsDirectory.appending(path: "Papote", directoryHint: .isDirectory)
        #else
        URL.documentsDirectory
        #endif
    }

    static func folder(for id: String, project: String?) -> URL {
        var url = root
        if let project, !project.isEmpty { url.append(path: project, directoryHint: .isDirectory) }
        return url.appending(path: id, directoryHint: .isDirectory)
    }

    private static func jsonURL(_ s: InterviewSession) -> URL { s.folder.appending(path: "session.json") }

    // MARK: Projets

    static func projects() -> [String] {
        subfolders(of: root)
            .filter { !FileManager.default.fileExists(atPath: $0.appending(path: "session.json").path) }
            .map(\.lastPathComponent)
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    /// Crée le dossier du projet ; renvoie le nom réellement utilisé.
    @discardableResult
    static func createProject(_ name: String) throws -> String {
        let clean = name
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, !clean.hasPrefix(".") else { throw CocoaError(.fileWriteInvalidFileName) }
        try FileManager.default.createDirectory(
            at: root.appending(path: clean, directoryHint: .isDirectory),
            withIntermediateDirectories: true)
        return clean
    }

    /// « Doc Ferme du Bec » → « Doc-Ferme-du-Bec », pour des noms de fichiers sûrs.
    static func slug(_ s: String) -> String {
        let folded = s.folding(options: .diacriticInsensitive, locale: .init(identifier: "fr"))
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let parts = folded.components(separatedBy: allowed.inverted).filter { !$0.isEmpty }
        return parts.joined(separator: "-")
    }

    // MARK: Sessions

    static func makeID(for date: Date, project: String?) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd_HHmmss"
        let prefix = project.map(slug).flatMap { $0.isEmpty ? nil : $0 } ?? "ITW"
        return prefix + "_" + f.string(from: date)
    }

    static func createFolder(_ id: String, project: String?) throws {
        try FileManager.default.createDirectory(at: folder(for: id, project: project),
                                                withIntermediateDirectories: true)
    }

    static func save(_ session: InterviewSession) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(session) {
            try? data.write(to: jsonURL(session), options: .atomic)
        }
    }

    static func loadAll() -> [InterviewSession] {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let top = subfolders(of: root)
        let folders = top + top.flatMap(subfolders(of:))
        return folders
            .compactMap { try? Data(contentsOf: $0.appending(path: "session.json")) }
            .compactMap { try? decoder.decode(InterviewSession.self, from: $0) }
            .sorted { $0.startDate > $1.startDate }
    }

    /// Au lancement : termine les interviews coupées par un plantage ou une
    /// coupure de batterie — WAV réparé, durée recalculée, exports réécrits.
    static func recoverInterrupted() -> [InterviewSession] {
        // markers == nil : session de la première version, toujours close
        // proprement (elle n'avait pas encore le champ `finished`).
        loadAll().filter { $0.finished != true && $0.markers != nil }.map { s in
            var s = s
            if let frames = BWFWriter.repair(url: s.audioURL), s.sampleRate > 0 {
                s.duration = Double(frames) / s.sampleRate
            }
            s.finished = true
            s.backupPending = !Backup.destinations.isEmpty
            save(s)
            writeExports(s)
            return s
        }
    }

    static func delete(_ session: InterviewSession) {
        try? FileManager.default.removeItem(at: session.folder)
    }

    private static func subfolders(of url: URL) -> [URL] {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: url, includingPropertiesForKeys: [.isDirectoryKey], options: .skipsHiddenFiles)) ?? []
        return urls.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
    }

    // MARK: Exports

    static func writeExports(_ session: InterviewSession) {
        let names = UserDefaults.standard.object(forKey: speakerNamesInSRTKey) as? Bool ?? true
        try? srt(session, timelineStart: 3600, speakerNames: names)
            .write(to: session.srtURL, atomically: true, encoding: .utf8)
        try? srt(session, timelineStart: nil, speakerNames: names)
            .write(to: session.srtTODURL, atomically: true, encoding: .utf8)
        try? text(session).write(to: session.txtURL, atomically: true, encoding: .utf8)
        if session.markers?.isEmpty == false {
            try? edl(session, timelineStart: 3600).write(to: session.edlURL, atomically: true, encoding: .utf8)
            try? edl(session, timelineStart: nil).write(to: session.edlTODURL, atomically: true, encoding: .utf8)
        } else {
            try? FileManager.default.removeItem(at: session.edlURL)
            try? FileManager.default.removeItem(at: session.edlTODURL)
        }
    }

    /// Temps absolus, alignés sur le TC de la timeline : 3600 = timeline à
    /// 01:00:00:00 avec le WAV posé au début, nil = heure du jour du WAV.
    static func srt(_ s: InterviewSession, timelineStart: Double?, speakerNames: Bool) -> String {
        let base = timelineStart ?? s.startTOD
        var out = ""
        for (i, seg) in s.segments.enumerated() {
            let end = max(seg.end, seg.start + 0.5)
            let name = speakerNames ? s.speakerName(seg.speaker).map { "\($0) : " } ?? "" : ""
            out += "\(i + 1)\n"
            out += "\(Timecode.srt(base + seg.start)) --> \(Timecode.srt(base + end))\n"
            out += "\(name)\(seg.text)\n\n"
        }
        return out
    }

    static func text(_ s: InterviewSession) -> String {
        let date = s.startDate.formatted(date: .complete, time: .shortened)
        var out = "Interview \(s.id)\n"
        if let project = s.project { out += "Projet : \(project)\n" }
        out += "\(date)\n"
        out += "Début \(s.tc(0)) · fin \(s.tc(s.duration)) · \(s.fps.rawValue) i/s · \(s.localeID)\n\n"
        for item in s.items {
            switch item {
            case .segment(let seg):
                let name = s.speakerName(seg.speaker).map { "\($0) : " } ?? ""
                out += "[\(s.tc(seg.start))] \(name)\(seg.text)\n"
            case .marker(let m):
                out += m.duration == nil
                    ? "[\(s.tc(m.time))] ★ \(m.name)\n"
                    : "[\(s.tc(m.time)) → \(s.tc(m.time + Double(s.frames(m)) / Double(s.fps.rawValue)))] ★ \(m.name) (\(Timecode.duration(m.duration ?? 0)))\n"
            }
        }
        return out
    }

    /// Marqueurs au format EDL de Resolve, avec leur durée (|D: en images) :
    /// Timeline > Import > Timeline Markers from EDL. Resolve place chaque
    /// marqueur au TC d'enregistrement : il doit tomber dans la timeline, et la
    /// cadence de l'app doit être celle de la timeline.
    /// `timelineStart` : TC de départ de la timeline en secondes (3600 =
    /// 01:00:00:00, WAV posé au début), ou nil pour l'heure du jour du WAV.
    static func edl(_ s: InterviewSession, timelineStart: Double?) -> String {
        var out = "TITLE: \(s.id)\nFCM: NON-DROP FRAME\n\n"
        let fps = Double(s.fps.rawValue)
        for (i, m) in (s.markers ?? []).sorted(by: { $0.time < $1.time }).enumerated() {
            // Fin calculée en images entières depuis l'entrée, pour que IN/OUT et |D: concordent.
            let inFrames = (((timelineStart ?? s.startTOD) + m.time) * fps + 1e-6).rounded(.down)
            let d = s.frames(m)
            let a = Timecode.string(inFrames / fps, fps: s.fps)
            let b = Timecode.string((inFrames + Double(d)) / fps, fps: s.fps)
            out += String(format: "%03d  001      V     C        ", i + 1) + "\(a) \(b) \(a) \(b)  \n"
            out += " |C:ResolveColorBlue |M:\(m.name) |D:\(d)\n\n"
        }
        return out
    }
}
