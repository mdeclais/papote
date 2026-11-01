import AVFoundation
import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
import CoreAudio
#endif

/// Ce qui diffère entre l'iPhone et le Mac, rassemblé ici pour que le reste
/// du code reste commun.
enum Platform {
    #if os(macOS)
    private static var activity: NSObjectProtocol?
    #endif

    /// Empêche la mise en veille pendant l'enregistrement.
    @MainActor static func keepAwake(_ on: Bool) {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = on
        #else
        if on, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleSystemSleepDisabled], reason: "Enregistrement d'une interview")
        } else if !on, let a = activity {
            ProcessInfo.processInfo.endActivity(a)
            activity = nil
        }
        #endif
    }

    enum Feedback { case light, strong, selection }

    @MainActor static func feedback(_ kind: Feedback) {
        #if os(iOS)
        switch kind {
        case .light: UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .strong: UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        case .selection: UISelectionFeedbackGenerator().selectionChanged()
        }
        #else
        NSHapticFeedbackManager.defaultPerformer.perform(kind == .selection ? .alignment : .levelChange,
                                                         performanceTime: .now)
        #endif
    }

    /// Lance une copie qui doit aller au bout même si l'app passe en arrière-plan.
    static func backgroundTask<T>(_ name: String, _ body: () async -> T) async -> T {
        #if os(iOS)
        let task = await UIApplication.shared.beginBackgroundTask(withName: name) {}
        let result = await body()
        await UIApplication.shared.endBackgroundTask(task)
        return result
        #else
        return await body()
        #endif
    }

    static func color(light: UInt32, dark: UInt32) -> Color {
        #if os(iOS)
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
        #else
        Color(NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? NSColor(hex: dark) : NSColor(hex: light)
        })
        #endif
    }
}

#if os(iOS)
extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat(hex >> 16 & 255) / 255, green: CGFloat(hex >> 8 & 255) / 255,
                  blue: CGFloat(hex & 255) / 255, alpha: 1)
    }
}
#else
extension NSColor {
    convenience init(hex: UInt32) {
        self.init(srgbRed: CGFloat(hex >> 16 & 255) / 255, green: CGFloat(hex >> 8 & 255) / 255,
                  blue: CGFloat(hex & 255) / 255, alpha: 1)
    }
}
#endif

extension View {
    /// Titre compact et barre couleur crème sur iPhone ; rien à faire sur Mac.
    func papoteNavigationBar() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Theme.background, for: .navigationBar)
        #else
        self
        #endif
    }
}

extension ToolbarItemPlacement {
    static var papoteLeading: ToolbarItemPlacement {
        #if os(iOS)
        .topBarLeading
        #else
        .navigation
        #endif
    }

    static var papoteTrailing: ToolbarItemPlacement {
        #if os(iOS)
        .topBarTrailing
        #else
        .primaryAction
        #endif
    }
}

#if os(macOS)
/// Entrées audio du Mac, lues dans Core Audio : sur Mac, AVAudioSession n'existe pas.
enum MacAudio {
    private static let system = AudioObjectID(kAudioObjectSystemObject)

    private static func address(_ selector: AudioObjectPropertySelector,
                                _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    private static func string(_ device: AudioDeviceID, _ selector: AudioObjectPropertySelector) -> String? {
        var addr = address(selector)
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(device, &addr, 0, nil, &size, &value) == noErr,
              let value else { return nil }
        return value.takeRetainedValue() as String
    }

    private static func inputChannels(_ device: AudioDeviceID) -> Int {
        var addr = address(kAudioDevicePropertyStreamConfiguration, kAudioObjectPropertyScopeInput)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(device, &addr, 0, nil, &size) == noErr, size > 0 else { return 0 }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(device, &addr, 0, nil, &size, raw) == noErr else { return 0 }
        let list = UnsafeMutableAudioBufferListPointer(raw.assumingMemoryBound(to: AudioBufferList.self))
        return list.reduce(0) { $0 + Int($1.mNumberChannels) }
    }

    private static func devices() -> [AudioDeviceID] {
        var addr = address(kAudioHardwarePropertyDevices)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(system, &addr, 0, nil, &size) == noErr else { return [] }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(system, &addr, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    static func inputs() -> [AudioInput] {
        devices().compactMap { id in
            guard inputChannels(id) > 0, let uid = string(id, kAudioDevicePropertyDeviceUID) else { return nil }
            var addr = address(kAudioDevicePropertyTransportType)
            var transport: UInt32 = 0
            var size = UInt32(MemoryLayout<UInt32>.size)
            AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &transport)
            return AudioInput(id: uid, name: string(id, kAudioObjectPropertyName) ?? uid,
                              isBuiltIn: transport == kAudioDeviceTransportTypeBuiltIn)
        }
    }

    /// Branche le moteur sur l'entrée choisie ; "" garde l'entrée par défaut du Mac.
    static func select(_ uid: String, on engine: AVAudioEngine) throws {
        guard !uid.isEmpty,
              let device = devices().first(where: { string($0, kAudioDevicePropertyDeviceUID) == uid }),
              let unit = engine.inputNode.audioUnit else { return }
        var id = device
        let status = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                          &id, UInt32(MemoryLayout<AudioDeviceID>.size))
        guard status == noErr else { throw RecorderError.noInput }
    }

    /// Prévient quand un micro est branché ou débranché.
    static func onDevicesChanged(_ handler: @escaping @Sendable () -> Void) {
        var addr = address(kAudioHardwarePropertyDevices)
        AudioObjectAddPropertyListenerBlock(system, &addr, .main) { _, _ in handler() }
    }
}
#endif
