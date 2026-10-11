import SwiftUI

/// Bottom-anchored text: two readable lines, with the older third line fading at the top.
struct TranscriptRibbon: View {
    let text: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var visibleText: String {
        text.isEmpty ? "Les mots apparaîtront ici…" : String(text.suffix(1600)).replacingOccurrences(of: "\n", with: " ")
    }
    var body: some View {
        Text(visibleText).font(.system(size: 14)).lineSpacing(4)
            .foregroundStyle(text.isEmpty ? Theme.muted : Theme.cream)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .frame(height: 54, alignment: .bottom)
            .clipped()
            .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .white, location: 0.28), .init(color: .white, location: 1)], startPoint: .top, endPoint: .bottom))
            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: visibleText)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
