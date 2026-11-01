import AVFoundation
import CoreMedia
import Speech

/// Transcription sur l'appareil avec SpeechAnalyzer (iOS 26+), sans limite de
/// durée. Les temps des résultats partent du premier buffer fourni, qui est
/// aussi le premier échantillon du WAV.
final class LiveTranscriber: @unchecked Sendable {
    private var analyzer: SpeechAnalyzer?
    private var inputBuilder: AsyncStream<AnalyzerInput>.Continuation?
    private var analyzerFormat: AVAudioFormat?
    private var converter: AVAudioConverter?
    private var resultsTask: Task<Void, Never>?

    var onResult: (@MainActor (SpeechTranscriber.Result) -> Void)?

    static func supportedLocales() async -> [Locale] {
        await SpeechTranscriber.supportedLocales
    }

    /// Télécharge le modèle de la langue si besoin, puis démarre l'analyseur.
    func prepare(locale requested: Locale) async throws {
        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: requested) else {
            throw RecorderError.localeUnsupported
        }
        let module = SpeechTranscriber(locale: locale,
                                       transcriptionOptions: [],
                                       reportingOptions: [.volatileResults],
                                       attributeOptions: [.audioTimeRange])
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            try await request.downloadAndInstall()
        }
        guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module]) else {
            throw RecorderError.transcriptionUnavailable
        }
        let analyzer = SpeechAnalyzer(modules: [module])
        let (stream, builder) = AsyncStream<AnalyzerInput>.makeStream()
        self.analyzer = analyzer
        analyzerFormat = format
        inputBuilder = builder

        resultsTask = Task { [weak self] in
            do {
                for try await result in module.results {
                    await self?.onResult?(result)
                }
            } catch {}
        }
        try await analyzer.start(inputSequence: stream)
    }

    /// Appelé depuis le thread audio.
    func feed(_ buffer: AVAudioPCMBuffer) {
        guard let format = analyzerFormat, let builder = inputBuilder else { return }
        if converter == nil {
            converter = AVAudioConverter(from: buffer.format, to: format)
            converter?.primeMethod = .none   // pas de décalage temporel introduit
            converter?.downmix = true        // stéréo → mono pour l'analyseur
        }
        guard let converter else { return }

        let ratio = format.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 1
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }

        var consumed = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if consumed {
                status.pointee = .noDataNow
                return nil
            }
            consumed = true
            status.pointee = .haveData
            return buffer
        }
        if error == nil, out.frameLength > 0 {
            builder.yield(AnalyzerInput(buffer: out))
        }
    }

    /// Vide la file, finalise le texte en cours et attend les derniers résultats.
    func finish() async {
        inputBuilder?.finish()
        try? await analyzer?.finalizeAndFinishThroughEndOfInput()
        await resultsTask?.value
    }
}

/// Découpe un résultat final en sous-titres courts (≤ 84 caractères, ≤ 6 s),
/// en coupant de préférence en fin de phrase.
enum CueBuilder {
    static let maxChars = 84
    static let maxDuration = 6.0

    static func cues(from text: AttributedString, fallback: CMTimeRange) -> [Segment] {
        var cues: [Segment] = []
        var current = ""
        var start: Double?
        var end = fallback.start.seconds

        func flush() {
            let t = current.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty, let s = start {
                cues.append(Segment(start: s, end: max(end, s), text: t))
            }
            current = ""
            start = nil
        }

        for run in text.runs {
            let piece = String(text[run.range].characters)
            guard let range = run.audioTimeRange else {
                if start == nil { start = end }
                current += piece
                continue
            }
            let s = range.start.seconds
            let e = range.end.seconds
            if let st = start, current.count + piece.count > maxChars || e - st > maxDuration {
                flush()
            }
            if start == nil { start = s }
            current += piece
            end = e
            if current.count > 30, let last = piece.trimmingCharacters(in: .whitespaces).last,
               ".?!…".contains(last) {
                flush()
            }
        }
        flush()

        if cues.isEmpty {
            let t = String(text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty {
                cues.append(Segment(start: fallback.start.seconds, end: fallback.end.seconds, text: t))
            }
        }
        return cues
    }
}
