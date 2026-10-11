import SwiftUI
import CoreText

enum Theme {
    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let rgb = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255, alpha: 1)
        })
    }
    // Kept in step with Android ThemeTokens.LIGHT and ThemeTokens.DARK.
    static let background = adaptive(0xF8F4EB, 0x201F1C)
    static let surface = adaptive(0xFFFCF6, 0x2B2925)
    static let elevated = adaptive(0xF0EEE6, 0x35322D)
    static let accent = adaptive(0x55725E, 0xAFD0AD)
    static let cream = adaptive(0x253239, 0xF5EEE4)
    static let muted = adaptive(0x5B6870, 0xBDB2A5)
    static let onAccent = adaptive(0xFFFCF6, 0x201F1C)
    static let line = adaptive(0x7C8B84, 0xE8DCC9).opacity(0.12)
    static let recording = adaptive(0xB63F38, 0xF06B60)
    static func scheme(_ value: String) -> ColorScheme? { value == "dark" ? .dark : value == "light" ? .light : nil }
    static func handwriting(_ size: CGFloat) -> Font { .custom("Caveat-Regular", size: size) }
    static func registerFonts() {
        if let url = Bundle.main.url(forResource: "Caveat", withExtension: "ttf", subdirectory: "Fonts") {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}
struct AccentButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 12, weight: .semibold))
            .padding(.horizontal, 16).padding(.vertical, 10)
            .foregroundStyle(enabled ? Theme.onAccent : Theme.muted)
            .background(enabled ? Theme.accent : Theme.elevated, in: RoundedRectangle(cornerRadius: 10))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}
struct EmblemShape: Shape {
    func path(in rect: CGRect) -> Path {
        let bounds = CGRect(x: 14, y: 32, width: 80, height: 44)
        let scale = min(rect.width / bounds.width, rect.height / bounds.height)
        var transform = CGAffineTransform(translationX: rect.midX - bounds.midX * scale, y: rect.midY - bounds.midY * scale).scaledBy(x: scale, y: scale)
        return Path(DictAIEmblem.path.copy(using: &transform)!)
    }
}
struct BrandMark: View {
    var width: CGFloat = 48
    var body: some View {
        EmblemShape().stroke(style: StrokeStyle(lineWidth: width * 7 / 80, lineCap: .round, lineJoin: .round))
            .frame(width: width, height: width * 0.55).foregroundStyle(Theme.accent).accessibilityHidden(true)
    }
}
struct Brand: View {
    var body: some View {
        HStack(spacing: 12) {
            Text("DictAI").font(Theme.handwriting(42)).fixedSize()
            BrandMark(width: 47)
        }.foregroundStyle(Theme.cream).accessibilityElement(children: .ignore).accessibilityLabel("DictAI")
    }
}
struct NotebookBackground: View {
    var margin: CGFloat = 28
    var body: some View {
        Canvas { context, size in
            var lines = Path()
            for y in stride(from: CGFloat(34), to: size.height, by: 34) {
                lines.move(to: CGPoint(x: 0, y: y)); lines.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(lines, with: .color(Theme.line.opacity(0.5)), lineWidth: 0.5)
            var vertical = Path(); vertical.move(to: CGPoint(x: margin, y: 0)); vertical.addLine(to: CGPoint(x: margin, y: size.height))
            context.stroke(vertical, with: .color(Theme.accent.opacity(0.3)), lineWidth: 0.7)
        }.background(Theme.background).allowsHitTesting(false).accessibilityHidden(true)
    }
}
struct SmallLabel: View {
    let text: String
    var body: some View { Text(text).font(.system(size: 11, weight: .medium)).foregroundStyle(Theme.muted) }
}
struct Waveform: View {
    let level: Float
    let active: Bool
    var body: some View {
        Canvas { context, size in
            var wave = Path()
            for step in 0...120 {
                let x = CGFloat(step) / 120 * size.width
                let envelope = sin(CGFloat(step) / 120 * .pi)
                let amplitude = active ? 3 + CGFloat(level) * 15 : 3
                let y = size.height / 2 + sin(CGFloat(step) / 120 * .pi * 7) * envelope * amplitude
                if step == 0 { wave.move(to: CGPoint(x: x, y: y)) } else { wave.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(wave, with: .color(Theme.accent), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }.frame(width: 80, height: 32).accessibilityLabel(active ? "Microphone actif" : "Microphone arrêté")
    }
}
