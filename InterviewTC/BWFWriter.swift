import AVFoundation

/// WAV « Broadcast Wave » 16 ou 24 bits, mono ou stéréo. Le chunk bext porte TimeReference =
/// échantillons écoulés depuis minuit : DaVinci Resolve (et Premiere, Avid…)
/// lisent ce champ comme timecode source du clip.
///
/// Les tailles d'en-tête sont remises à jour toutes les ~5 s, pour qu'un
/// fichier reste lisible même si l'app est tuée en plein enregistrement.
final class BWFWriter {
    private static let bextSize = 602
    private static let headerSize = 12 + (8 + bextSize) + (8 + 16) + 8

    private let handle: FileHandle
    private let channels: Int
    /// Octets par échantillon : 2 (16 bits) ou 3 (24 bits).
    private let bytes: Int
    private let flushEvery: Int
    private var dataBytes: UInt64 = 0
    private var sinceFlush = 0
    private(set) var frames: Int64 = 0

    init(url: URL, sampleRate: Double, channels: Int, bitDepth: Int,
         timeReference: UInt64, date: Date, description: String) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
        self.channels = channels
        bytes = bitDepth == 16 ? 2 : 3
        flushEvery = Int(sampleRate) * bytes * channels * 5

        let posix = DateFormatter()
        posix.locale = Locale(identifier: "en_US_POSIX")
        posix.dateFormat = "yyyy-MM-dd"
        let day = posix.string(from: date)
        posix.dateFormat = "HH:mm:ss"
        let time = posix.string(from: date)

        let rate = UInt32(sampleRate.rounded())
        var h = Data()
        h.ascii("RIFF"); h.le32(0); h.ascii("WAVE")

        h.ascii("bext"); h.le32(UInt32(Self.bextSize))
        h.fixed(description, 256)
        h.fixed("Papote (iPhone)", 32)         // Originator
        h.fixed("", 32)                              // OriginatorReference
        h.fixed(day, 10)                             // OriginationDate
        h.fixed(time, 8)                             // OriginationTime
        h.le32(UInt32(truncatingIfNeeded: timeReference))
        h.le32(UInt32(truncatingIfNeeded: timeReference >> 32))
        h.le16(1)                                    // Version
        h.append(Data(count: 64 + 190))              // UMID + réservé

        h.ascii("fmt "); h.le32(16)
        h.le16(1)                                    // PCM
        h.le16(UInt16(channels))
        h.le32(rate)
        h.le32(rate * UInt32(bytes * channels))      // octets / s
        h.le16(UInt16(bytes * channels))             // block align
        h.le16(UInt16(bytes * 8))

        h.ascii("data"); h.le32(0)
        assert(h.count == Self.headerSize)
        try handle.write(contentsOf: h)
    }

    /// Float [-1, 1] → PCM entier little-endian (16 ou 24 bits), canaux entrelacés.
    func encode(_ buffer: AVAudioPCMBuffer) -> Data {
        guard let data = buffer.floatChannelData else { return Data() }
        let frames = Int(buffer.frameLength)
        let available = Int(buffer.format.channelCount)
        let (bytes, channels) = (self.bytes, self.channels)
        let scale: Float = bytes == 2 ? 32_767 : 8_388_607
        var d = Data(count: frames * channels * bytes)
        d.withUnsafeMutableBytes { raw in
            let p = raw.bindMemory(to: UInt8.self)
            var o = 0
            for i in 0..<frames {
                for c in 0..<channels {
                    // Entrée mono et fichier stéréo : on duplique le canal.
                    let sample = data[min(c, available - 1)][i]
                    let v = Int32((max(-1, min(1, sample)) * scale).rounded())
                    p[o] = UInt8(truncatingIfNeeded: v)
                    p[o + 1] = UInt8(truncatingIfNeeded: v >> 8)
                    if bytes == 3 { p[o + 2] = UInt8(truncatingIfNeeded: v >> 16) }
                    o += bytes
                }
            }
        }
        return d
    }

    func append(_ pcm: Data) {
        try? handle.write(contentsOf: pcm)
        dataBytes += UInt64(pcm.count)
        frames += Int64(pcm.count / (bytes * channels))
        sinceFlush += pcm.count
        if sinceFlush >= flushEvery {
            patchSizes(pad: 0)
            sinceFlush = 0
        }
    }

    func close() {
        // RIFF impose un nombre pair d'octets par chunk.
        let pad: UInt64 = dataBytes % 2
        if pad == 1 { try? handle.write(contentsOf: Data([0])) }
        patchSizes(pad: pad)
        try? handle.synchronize()
        try? handle.close()
    }

    /// Remet les tailles d'en-tête d'un WAV coupé net (plantage, batterie) en
    /// accord avec ce qui est réellement sur le disque ; renvoie le nombre d'échantillons.
    static func repair(url: URL) -> Int64? {
        guard let handle = try? FileHandle(forUpdating: url) else { return nil }
        defer { try? handle.close() }
        guard let size = try? handle.seekToEnd(), size >= UInt64(headerSize),
              (try? handle.seek(toOffset: UInt64(12 + 8 + bextSize + 8 + 12))) != nil,  // block align du chunk fmt
              let raw = try? handle.read(upToCount: 2), raw.count == 2 else { return nil }
        let blockAlign = UInt64(max(1, Int(raw[0]) | Int(raw[1]) << 8))
        var dataBytes = size - UInt64(headerSize)
        dataBytes -= dataBytes % blockAlign
        var d = Data()
        d.le32(UInt32(clamping: UInt64(headerSize) - 8 + dataBytes + dataBytes % 2))
        try? handle.seek(toOffset: 4)
        try? handle.write(contentsOf: d)
        d = Data()
        d.le32(UInt32(clamping: dataBytes))
        try? handle.seek(toOffset: UInt64(headerSize - 4))
        try? handle.write(contentsOf: d)
        try? handle.truncate(atOffset: UInt64(headerSize) + dataBytes)
        if dataBytes % 2 == 1 {   // octet de bourrage RIFF
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: Data([0]))
        }
        return Int64(dataBytes / blockAlign)
    }

    private func patchSizes(pad: UInt64) {
        let riff = UInt64(Self.headerSize) - 8 + dataBytes + pad
        var d = Data()
        try? handle.seek(toOffset: 4)
        d.le32(UInt32(clamping: riff))
        try? handle.write(contentsOf: d)
        d = Data()
        try? handle.seek(toOffset: UInt64(Self.headerSize - 4))
        d.le32(UInt32(clamping: dataBytes))
        try? handle.write(contentsOf: d)
        _ = try? handle.seekToEnd()
    }
}

private extension Data {
    mutating func ascii(_ s: String) { append(contentsOf: Array(s.utf8)) }

    mutating func fixed(_ s: String, _ length: Int) {
        var bytes = Array(s.utf8.prefix(length))
        bytes += [UInt8](repeating: 0, count: length - bytes.count)
        append(contentsOf: bytes)
    }

    mutating func le16(_ v: UInt16) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
    mutating func le32(_ v: UInt32) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
}
