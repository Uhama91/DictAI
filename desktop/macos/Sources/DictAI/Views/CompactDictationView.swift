import SwiftUI

struct CompactDictationView: View {
    @ObservedObject var model: AppModel
    private var hint: String {
        if !model.message.isEmpty { return model.message.components(separatedBy: ". ").first ?? model.message }
        if model.state == .listening { return "⌥ terminer · ×2 pause · ×3 annuler" }
        if model.state == .paused { return "⌥ reprendre · ×2 reprendre · ×3 annuler" }
        return "Cliquer sur le texte pour modifier"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                DictationLoops(model: model, presentation: .compact)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.state.title).font(.system(size: 12, weight: .semibold))
                    Text(hint).font(.system(size: 9)).foregroundStyle(Theme.muted)
                        .lineLimit(model.message.isEmpty ? 1 : 2).help(model.message.isEmpty ? hint : model.message)
                }
                Spacer(minLength: 0)
                if model.state == .listening {
                    OverlayIconButton("Mettre en pause · ⌥ deux fois", symbol: "pause.fill", size: 28) { model.pauseListening() }
                } else if model.busy { ProgressView().controlSize(.small).frame(width: 28) }
                else { OverlayIconButton("Reprendre la dictée · ⌥ une fois", symbol: "play.fill", size: 28) { model.resumeListening() } }
                OverlayIconButton("Ouvrir dans une fenêtre", symbol: "arrow.up.left.and.arrow.down.right", size: 28) { model.showWorkspace() }
                OverlayIconButton("Annuler sans envoyer · ⌥ trois fois ou Échap", symbol: "xmark", size: 28) { model.closeOverlay() }
            }
            Button { model.showDraft() } label: {
                TranscriptRibbon(text: model.draft.text).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("Modifier la transcription")
                .accessibilityValue(model.draft.text.isEmpty ? "En attente de parole" : model.draft.text)
                .help("Cliquer pour ouvrir l’éditeur rapide")
        }.padding(.horizontal, 14).padding(.vertical, 12)
            .frame(width: 440, height: 124)
    }
}

struct OverlayIconButton: View {
    let title: String
    let symbol: String
    let action: () -> Void
    let size: CGFloat
    init(_ title: String, symbol: String, size: CGFloat = 40, action: @escaping () -> Void) {
        self.title = title; self.symbol = symbol; self.size = size; self.action = action
    }
    var body: some View {
        Button(action: action) { Image(systemName: symbol).frame(width: size, height: size).contentShape(Rectangle()) }
            .buttonStyle(.plain).help(title).accessibilityLabel(title)
    }
}
