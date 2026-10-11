import SwiftUI
import DictAICore

struct OverlayRootView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: LibraryStore
    var body: some View {
        Group {
            if model.presentation == .compact { CompactDictationView(model: model) }
            else { QuickDictationEditor(model: model, library: library) }
        }.foregroundStyle(Theme.cream).tint(Theme.accent)
            .preferredColorScheme(Theme.scheme(library.preference("theme_mode", default: "system")))
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
            .clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

struct QuickDictationEditor: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: LibraryStore
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 10) {
                DictationLoops(model: model, presentation: .editor)
                Text("Édition rapide").font(Theme.handwriting(25))
                Spacer(minLength: 0)
                OverlayIconButton("Réduire en pastille", symbol: "chevron.down") { model.showCompact() }
                OverlayIconButton("Ouvrir dans une fenêtre", symbol: "arrow.up.left.and.arrow.down.right") { model.showWorkspace() }
                Button("Annuler") { model.closeOverlay() }
                    .help("Arrêter sans envoyer. Le brouillon reste disponible.")
            }
            DictationContextRow(model: model, library: library)
            DictationTextEditor(model: model, spacious: false)
            DictationFeedback(model: model, compact: true)
            DictationActions(model: model, library: library)
            Text("⌥ terminer · ×2 pause ou reprise · ×3 annuler sans envoyer").font(.system(size: 10)).foregroundStyle(Theme.muted)
        }.padding(16).frame(width: 560, height: 330)
    }
}

struct DictationWorkspaceView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: LibraryStore
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 14) {
                DictationLoops(model: model, presentation: .window)
                Text(model.destination == .note ? "Votre note" : "Votre dictée").font(Theme.handwriting(36))
                Spacer()
                Button { model.showCompact() } label: { Label("Revenir à la pastille", systemImage: "rectangle.compress.vertical") }.buttonStyle(.borderless)
                Button("Annuler") { model.closeOverlay() }
                    .help("Arrêter sans envoyer. Le brouillon reste disponible.")
            }
            HStack {
                Label("Brouillon enregistré sur ce Mac", systemImage: "checkmark.circle").foregroundStyle(Theme.muted)
                Spacer()
                let count = model.draft.text.split(whereSeparator: \.isWhitespace).count
                Text("\(count) mot\(count > 1 ? "s" : "")").foregroundStyle(Theme.muted).monospacedDigit()
            }.font(.system(size: 11))
            DictationContextRow(model: model, library: library)
            Rectangle().fill(Theme.line).frame(height: 1)
            DictationTextEditor(model: model, spacious: true)
            DictationFeedback(model: model, compact: false)
            DictationActions(model: model, library: library)
            Text("⌥ terminer · ×2 pause ou reprise · ×3 annuler sans envoyer · Brouillon conservé")
                .font(.system(size: 11)).foregroundStyle(Theme.muted)
        }.padding(28).frame(maxWidth: 860).frame(maxWidth: .infinity, maxHeight: .infinity)
            .foregroundStyle(Theme.cream).tint(Theme.accent).background(Theme.surface)
            .preferredColorScheme(Theme.scheme(library.preference("theme_mode", default: "system")))
    }
}

struct DictationTextEditor: View {
    @ObservedObject var model: AppModel
    let spacious: Bool
    var body: some View {
        VStack(spacing: 6) {
            ZStack(alignment: .topLeading) {
                LiveDictationTextView(model: model, spacious: spacious)
                if model.draft.text.isEmpty {
                    Text(model.state == .listening ? "Parlez, les mots arrivent…" : "Votre texte reste ici. Vous pouvez aussi écrire.")
                        .font(.system(size: spacious ? 17 : 14)).foregroundStyle(Theme.muted).padding(12).allowsHitTesting(false)
                }
            }.frame(maxWidth: .infinity, minHeight: 70, maxHeight: .infinity)
            .background(Theme.background, in: RoundedRectangle(cornerRadius: spacious ? 12 : 8))
            .clipShape(RoundedRectangle(cornerRadius: spacious ? 12 : 8))
            TranscriptFollowControls(session: model.editorSession)
        }
    }
}

struct TranscriptFollowControls: View {
    @ObservedObject var session: DictationEditorSession
    var body: some View {
        HStack(spacing: 8) {
            Text(session.policy.following ? "Suivi en direct" : "Position conservée").foregroundStyle(Theme.muted)
            Menu {
                ForEach([5.0, 8.0, 15.0, 0.0], id: \.self) { delay in
                    Button {
                        session.setDelay(delay)
                    } label: {
                        if session.policy.delay == delay { Label(title(delay), systemImage: "checkmark") }
                        else { Text(title(delay)) }
                    }
                }
            } label: { Text(session.policy.delay == 0 ? "Retour manuel" : "Retour après \(Int(session.policy.delay)) s") }
                .fixedSize().menuStyle(.borderlessButton)
                .help("En mode automatique, la vue rejoint les nouveaux mots après votre dernière action. Votre sélection reste à sa place.")
            Spacer(minLength: 0)
            if !session.policy.following {
                Button { session.follow() } label: { Label("Suivre la dictée", systemImage: "arrow.down") }
                    .buttonStyle(.plain).foregroundStyle(Theme.accent)
            }
        }.font(.system(size: 10)).frame(height: 18)
    }
    private func title(_ delay: Double) -> String { delay == 0 ? "Manuellement" : "Après \(Int(delay)) secondes" }
}

struct DictationContextRow: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: LibraryStore
    var body: some View {
        HStack(spacing: 8) {
            if model.state == .listening { Circle().fill(Theme.recording).frame(width: 6, height: 6) }
            else if model.busy { ProgressView().controlSize(.mini) }
            Text(model.state == .listening ? "À votre écoute" : model.state.title).font(.system(size: 11))
            Text("·").foregroundStyle(Theme.muted)
            Text(model.destination == .note ? "Note dans DictAI" : model.insertion.available ? "Vers " + model.insertion.targetName : "Brouillon")
                .font(.system(size: 11)).foregroundStyle(Theme.muted).lineLimit(1)
            Spacer(minLength: 0)
        }
    }
}

struct DictationFeedback: View {
    @ObservedObject var model: AppModel
    let compact: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.draft.edited && model.state == .listening {
                Text("Vos retouches sont protégées.").font(.system(size: 11)).foregroundStyle(Theme.muted)
            }
            if let rule = model.learningSuggestion {
                HStack(spacing: 8) {
                    Image(systemName: "text.badge.plus")
                    Text(rule).lineLimit(compact ? 1 : 3)
                    Spacer(minLength: 0)
                    Button("Mémoriser ce mot") { model.learn(rule) }.controlSize(.small)
                }.font(.system(size: 11)).foregroundStyle(Theme.accent)
            }
            if !model.message.isEmpty {
                Text(model.message).font(.system(size: 11)).foregroundStyle(Theme.accent)
                    .lineLimit(compact ? 2 : nil).help(model.message)
            }
        }
    }
}

struct DictationActions: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: LibraryStore
    private var listening: Bool { model.state == .listening }
    var body: some View {
        HStack(spacing: 8) {
            OverlayIconButton("Copier le texte", symbol: "doc.on.doc") { model.insertion.copy(model.draft.text); model.message = "Texte copié" }
                .disabled(model.draft.text.isEmpty)
            Menu {
                Button("Sans dossier") { library.selectedFolder = nil; model.saveNote() }
                ForEach(library.folders) { folder in Button(folder.name) { library.selectedFolder = folder.id; model.saveNote() } }
            } label: { Label("Classer", systemImage: "folder") }
                .disabled(model.draft.text.isEmpty || model.busy || listening)
            OverlayIconButton("Corriger avec OpenRouter", symbol: "wand.and.stars") { model.clean() }
                .disabled(model.busy || listening || model.draft.text.isEmpty)
            Spacer(minLength: 0)
            if listening {
                Button { model.pauseListening() } label: { Label("Pause", systemImage: "pause.fill") }
                    .help("Suspendre l’écoute pour modifier le texte, sans l’envoyer · ⌥ deux fois")
                Button(model.destination == .note ? "Terminer la note" : "Terminer et insérer") { model.toggle() }.buttonStyle(AccentButtonStyle())
            } else {
                Button { model.resumeListening() } label: { Label("Reprendre", systemImage: "play.fill") }
                    .help("Continuer la dictée dans le même brouillon · ⌥ deux fois").disabled(model.busy)
                Button(model.delivered ? (model.destination == .note ? "Note enregistrée" : "Texte envoyé") : model.deliveryLabel) { model.completeDictation() }
                    .buttonStyle(AccentButtonStyle())
                    .disabled(model.delivered || model.busy || model.draft.text.isEmpty)
            }
        }.controlSize(.regular)
    }
}

extension DictationState {
    var title: String {
        switch self {
        case .preparing: return "Préparation du micro…"
        case .listening: return "À votre écoute"
        case .finishing: return "Transcription…"
        case .cleaning: return "Correction cloud…"
        case .inserting: return "Insertion…"
        case .paused: return "En pause"
        case .idle, .review: return "Votre dictée"
        }
    }
}
