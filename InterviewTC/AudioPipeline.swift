import AVFoundation

/// Hors main actor : reçoit les buffers du micro, les écrit dans le WAV et
/// les passe à la transcription. Le premier buffer fixe le timecode de départ.
final class AudioPipeline: @unchecked Sendable {
    struct StartInfo: Sendable {
        let date: Date
        let sampleRate: Double
        let channels: Int
        let timeReference: UInt64
    }

    let engine = AVAudioEngine()
    private let writeQueue = DispatchQueue(label: "InterviewTC.bwf")
    private let audioURL: URL
    private let fileDescription: String
    private let transcriber: LiveTranscriber?
    private let wantedChannels: Int
    private var channels = 1
    private var sampleRate = 48_000.0
    private var writer: BWFWriter?
    /// Délai entre le son qui entre dans le micro et le buffer reçu.
    private var inputLatency: TimeInterval = 0

    var onStart: (@Sendable (StartInfo) -> Void)?
    var onLevel: (@Sendable (Float) -> Void)?
    var onError: (@Sendable (Error) -> Void)?

    /// `channels` : 1 (mono) ou 2 (stéréo, si l'entrée en fournit deux).
    private let bitDepth: Int

    init(audioURL: URL, description: String, channels: Int, bitDepth: Int, transcriber: LiveTranscriber?) {
        self.audioURL = audioURL
        self.bitDepth = bitDepth
        self.wantedChannels = channels
        self.fileDescription = description
        self.transcriber = transcriber
    }

    func start() throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw RecorderError.noInput }
        channels = min(wantedChannels, Int(format.channelCount))
        sampleRate = format.sampleRate
        #if os(iOS)
        inputLatency = AVAudioSession.sharedInstance().inputLatency
        #else
        inputLatency = input.presentationLatency
        #endif
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, when in
            self?.process(buffer, at: when)
        }
        engine.prepare()
        try engine.start()
    }

    /// Arrête le micro et ferme le WAV ; renvoie la durée enregistrée en secondes.
    func stop() -> Double {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        return writeQueue.sync {
            writer?.close()
            guard let writer else { return 0 }
            return Double(writer.frames) / sampleRate
        }
    }

    private func process(_ buffer: AVAudioPCMBuffer, at when: AVAudioTime) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let frames = Int(buffer.frameLength)

        if writer == nil {
            let info = Self.startInfo(for: when, sampleRate: buffer.format.sampleRate, channels: channels,
                                      latency: inputLatency)
            do {
                let w = try BWFWriter(url: audioURL, sampleRate: info.sampleRate,
                                      channels: channels, bitDepth: bitDepth, timeReference: info.timeReference,
                                      date: info.date, description: fileDescription)
                writeQueue.sync { writer = w }
            } catch {
                onError?(error)
                return
            }
            onStart?(info)
        }

        guard let writer else { return }
        let pcm = writer.encode(buffer)
        writeQueue.async { writer.append(pcm) }
        transcriber?.feed(buffer)

        var sum: Float = 0
        for i in 0..<frames { sum += channel[i] * channel[i] }
        let rms = (sum / Float(max(frames, 1))).squareRoot()
        let db = 20 * log10(max(rms, 1e-6))
        onLevel?(max(0, min(1, (db + 60) / 60)))
    }

    /// Heure murale du premier échantillon du buffer : on remonte depuis
    /// « maintenant » de l'âge du buffer (hostTime) et de la latence d'entrée.
    private static func startInfo(for when: AVAudioTime, sampleRate: Double, channels: Int,
                                  latency: TimeInterval) -> StartInfo {
        let now = Date()
        var age = latency
        if when.isHostTimeValid {
            age += AVAudioTime.seconds(forHostTime: mach_absolute_time())
                - AVAudioTime.seconds(forHostTime: when.hostTime)
        }
        let start = now.addingTimeInterval(-age)
        let ref = UInt64((Timecode.secondsSinceMidnight(start) * sampleRate).rounded())
        return StartInfo(date: start, sampleRate: sampleRate, channels: channels, timeReference: ref)
    }
}

enum RecorderError: LocalizedError {
    case micDenied, noInput, localeUnsupported, transcriptionUnavailable

    var errorDescription: String? {
        switch self {
        #if os(macOS)
        case .micDenied: "Accès au micro refusé. Autorise-le dans Réglages Système > Confidentialité et sécurité > Micro."
        #else
        case .micDenied: "Accès au micro refusé. Autorise-le dans Réglages > Papote."
        #endif
        case .noInput: "Aucune entrée audio disponible."
        case .localeUnsupported: "Cette langue n'est pas prise en charge par la transcription sur cet appareil."
        case .transcriptionUnavailable: "La transcription n'est pas disponible sur cet appareil."
        }
    }
}
