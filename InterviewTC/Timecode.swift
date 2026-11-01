import Foundation

enum FrameRate: Int, CaseIterable, Identifiable, Codable {
    case fps24 = 24
    case fps25 = 25
    case fps30 = 30
    case fps50 = 50
    case fps60 = 60

    var id: Int { rawValue }
}

enum Timecode {
    /// Secondes écoulées depuis minuit, heure locale.
    static func secondsSinceMidnight(_ date: Date, calendar: Calendar = .current) -> Double {
        date.timeIntervalSince(calendar.startOfDay(for: date))
    }

    /// HH:MM:SS:FF (non drop-frame), repli sur 24 h.
    static func string(_ seconds: Double, fps: FrameRate) -> String {
        let rate = fps.rawValue
        var s = seconds.truncatingRemainder(dividingBy: 86_400)
        if s < 0 { s += 86_400 }
        let totalFrames = Int((s * Double(rate) + 1e-6).rounded(.down))
        let secs = totalFrames / rate
        return String(format: "%02d:%02d:%02d:%02d",
                      secs / 3600, (secs / 60) % 60, secs % 60, totalFrames % rate)
    }

    /// HH:MM:SS,mmm pour les sous-titres SRT.
    static func srt(_ seconds: Double) -> String {
        let ms = Int((max(0, seconds) * 1000).rounded())
        return String(format: "%02d:%02d:%02d,%03d",
                      ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
    }

    static func duration(_ seconds: Double) -> String {
        let s = Int(max(0, seconds))
        return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}
