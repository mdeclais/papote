import SwiftUI
import UIKit

/// Couleurs de l'icône Papote, déclinées en clair et en sombre.
enum Theme {
    /// Fond de l'app : crème chaude / brun très foncé.
    static let background = dynamic(light: 0xFFF4DE, dark: 0x1F140D)
    /// Panneaux : le jaune de l'icône / brun chaud.
    static let card = dynamic(light: 0xFAC775, dark: 0x3A2416)
    /// Surfaces discrètes (chips, lignes de liste).
    static let surface = dynamic(light: 0xFFFBF2, dark: 0x2B1B12)
    /// Le corail de la bulle.
    static let coral = dynamic(light: 0xD85A30, dark: 0xE8774F)
    /// Le crème de l'onde, posé sur le corail.
    static let cream = Color(hex: 0xFAECE7)
    /// Le rouge du point REC.
    static let rec = Color(hex: 0xE24B4A)
    /// Texte posé sur le jaune ou le crème.
    static let ink = dynamic(light: 0x4A1B0C, dark: 0xFAECE7)
    static let inkSoft = dynamic(light: 0x993C1D, dark: 0xF0997B)
    /// Bulle de l'intervieweur : jaune pâle.
    static let bubbleAlt = dynamic(light: 0xFDE3B0, dark: 0x4A3020)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

extension Color {
    init(hex: UInt32) { self.init(uiColor: UIColor(hex: hex)) }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat(hex >> 16 & 255) / 255, green: CGFloat(hex >> 8 & 255) / 255,
                  blue: CGFloat(hex & 255) / 255, alpha: 1)
    }
}

/// Petite pastille crème façon Papote pour les menus de l'en-tête.
struct ChipLabel: View {
    let text: String
    let systemImage: String

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.subheadline.weight(.medium))
            .lineLimit(1)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Theme.surface, in: Capsule())
            .foregroundStyle(Theme.ink)
    }
}

/// Fond crème commun aux listes (interviews, réglages).
struct PapoteListStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .toolbarBackground(Theme.background, for: .navigationBar)
    }
}

extension View {
    func papoteList() -> some View { modifier(PapoteListStyle()) }
}
