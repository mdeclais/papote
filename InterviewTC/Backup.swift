import Foundation
import UIKit

/// Dossier de sauvegarde choisi dans l'app Fichiers (iCloud Drive, Google Drive…).
/// On garde un signet pour y réécrire sans redemander l'accès.
struct BackupDestination: Identifiable, Codable, Hashable {
    var id = UUID()
    var bookmark: Data
    var folderName: String
    var provider: String

    var label: String { "\(provider) · \(folderName)" }
}

/// Services proposés dans les réglages. La « connexion » consiste à autoriser
/// Papote à écrire dans un dossier de ce service, choisi dans l'app Fichiers.
enum CloudService: String, CaseIterable, Identifiable {
    case iCloud = "iCloud Drive"
    case google = "Google Drive"

    var id: String { rawValue }
    var icon: String { self == .iCloud ? "icloud" : "externaldrive.badge.icloud" }

    var destination: BackupDestination? {
        Backup.destinations.first { $0.provider == rawValue }
    }
}

struct BackupReport: Sendable {
    var succeeded: [String] = []
    var failed: [(String, String)] = []

    var summary: String {
        var parts: [String] = []
        if !succeeded.isEmpty { parts.append("Sauvegardé : " + succeeded.joined(separator: ", ")) }
        for (name, reason) in failed { parts.append("Échec \(name) : \(reason)") }
        return parts.joined(separator: "\n")
    }
}

enum BackupError: LocalizedError {
    case notICloud, notGoogle

    var errorDescription: String? {
        switch self {
        case .notICloud: "Ce dossier n'est pas dans iCloud Drive. Dans le sélecteur, touche « Parcourir » puis « iCloud Drive »."
        case .notGoogle: "Ce dossier est dans iCloud Drive. Dans le sélecteur, touche « Parcourir » puis « Drive »."
        }
    }
}

/// Copie les fichiers d'une interview dans <dossier choisi>/<projet>/ sur
/// chaque destination. Le dossier du projet est créé s'il n'existe pas.
enum Backup {
    private static let key = "backupDestinations"
    static let autoKey = "backupAutomatically"

    static var destinations: [BackupDestination] {
        get {
            (UserDefaults.standard.data(forKey: key))
                .flatMap { try? JSONDecoder().decode([BackupDestination].self, from: $0) } ?? []
        }
        set { UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: key) }
    }

    static var automatic: Bool { UserDefaults.standard.object(forKey: autoKey) as? Bool ?? true }

    /// Relie un service au dossier choisi dans le sélecteur ; remplace l'ancien dossier du service.
    static func connect(_ service: CloudService, to url: URL) throws {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        switch service {
        case .iCloud where !isICloud(url):
            throw BackupError.notICloud
        case .google where isICloud(url):
            throw BackupError.notGoogle
        default:
            break
        }
        let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        let dest = BackupDestination(bookmark: bookmark, folderName: url.lastPathComponent, provider: service.rawValue)
        destinations = destinations.filter { $0.provider != service.rawValue } + [dest]
    }

    static func disconnect(_ service: CloudService) {
        destinations = destinations.filter { $0.provider != service.rawValue }
    }

    private static func isICloud(_ url: URL) -> Bool {
        if url.path.contains("/Mobile Documents/") { return true }
        return (try? url.resourceValues(forKeys: [.isUbiquitousItemKey]).isUbiquitousItem) == true
    }

    /// Compte iCloud actif sur l'iPhone (iCloud Drive peut quand même être désactivé).
    static var iCloudAccountAvailable: Bool { FileManager.default.ubiquityIdentityToken != nil }

    static func projectFolderName(_ session: InterviewSession) -> String {
        session.project ?? "Sans projet"
    }

    /// Copie bloquante de `files` : à lancer hors du thread principal.
    static func run(_ session: InterviewSession, files: [URL]) -> BackupReport {
        var report = BackupReport()
        for var dest in destinations {
            do {
                var stale = false
                let root = try URL(resolvingBookmarkData: dest.bookmark, bookmarkDataIsStale: &stale)
                guard root.startAccessingSecurityScopedResource() else {
                    throw CocoaError(.fileReadNoPermission)
                }
                defer { root.stopAccessingSecurityScopedResource() }
                if stale, let fresh = try? root.bookmarkData() {
                    dest.bookmark = fresh
                    destinations = destinations.map { $0.id == dest.id ? dest : $0 }
                }

                let folder = root.appending(path: projectFolderName(session), directoryHint: .isDirectory)
                try coordinate(writingAt: folder) { try FileManager.default.createDirectory(at: $0, withIntermediateDirectories: true) }
                for file in files {
                    let target = folder.appending(path: file.lastPathComponent)
                    try coordinate(writingAt: target) { url in
                        if FileManager.default.fileExists(atPath: url.path) {
                            try FileManager.default.removeItem(at: url)
                        }
                        try FileManager.default.copyItem(at: file, to: url)
                    }
                }
                report.succeeded.append(dest.provider)
            } catch {
                report.failed.append((dest.provider, error.localizedDescription))
            }
        }
        return report
    }

    /// Les fournisseurs de fichiers (iCloud, Google Drive) exigent des écritures coordonnées.
    private static func coordinate(writingAt url: URL, _ body: (URL) throws -> Void) throws {
        var coordinationError: NSError?
        var bodyError: Error?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forReplacing, error: &coordinationError) { url in
            do { try body(url) } catch { bodyError = error }
        }
        if let error = coordinationError ?? bodyError { throw error }
    }

    /// Textes d'abord (SRT, TXT, EDL : quelques Ko, disponibles tout de suite
    /// partout), puis le WAV. `onTextsDone` reçoit le bilan des textes.
    /// iOS accorde un délai si l'app passe en arrière-plan pendant la copie.
    static func runInBackground(_ session: InterviewSession,
                                onTextsDone: @MainActor @Sendable (BackupReport) -> Void = { _ in }) async -> BackupReport {
        let task = await UIApplication.shared.beginBackgroundTask(withName: "Papote backup") {}
        let texts = await Task.detached(priority: .userInitiated) { run(session, files: session.textURLs) }.value
        await onTextsDone(texts)
        let audio = await Task.detached(priority: .utility) { run(session, files: [session.audioURL]) }.value
        await UIApplication.shared.endBackgroundTask(task)

        // Une destination n'est réussie que si textes et audio y sont arrivés.
        var report = BackupReport()
        let failed = texts.failed + audio.failed
        report.failed = failed
        report.succeeded = texts.succeeded.filter { name in !failed.contains { $0.0 == name } }
        return report
    }
}
