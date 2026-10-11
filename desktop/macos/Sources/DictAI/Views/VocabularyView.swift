import SwiftUI
import DictAICore

struct VocabularyView: View {
    @ObservedObject var library: LibraryStore
    @State private var line = ""
    @State private var query = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            Text("Votre vocabulaire").font(Theme.handwriting(40))
            Text("Gardez vos noms propres et vos expressions. Les corrections mémorisées\ns’appliquent à vos prochaines dictées, sur le Mac comme sur Android.")
                .font(.system(size: 13)).lineSpacing(5).foregroundStyle(Theme.muted)
            HStack(spacing: 12) {
                TextField("Un terme, ou une correction : dicta => DictAI", text: $line).textFieldStyle(.plain).padding(14).background(Theme.surface, in: RoundedRectangle(cornerRadius: 9)).onSubmit(add)
                Button("Ajouter", systemImage: "plus", action: add).buttonStyle(AccentButtonStyle()).disabled(line.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            HStack { SmallLabel(text: "\(library.vocabulary.count) préférence\(library.vocabulary.count <= 1 ? "" : "s")"); Spacer(); TextField("Rechercher", text: $query).textFieldStyle(.roundedBorder).frame(width: 200) }
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(library.vocabulary.filter { query.isEmpty || $0.value.localizedCaseInsensitiveContains(query) }, id: \.key) { entry in
                        HStack(spacing: 14) {
                            Image(systemName: entry.value.contains("=>") ? "arrow.right" : "textformat.abc").frame(width: 30).foregroundStyle(Theme.accent)
                            Text(entry.value.replacingOccurrences(of: "=>", with: "→")).font(.system(size: 14)).textSelection(.enabled)
                            Spacer()
                            Button { library.removeVocabulary(entry.key) } label: { Image(systemName: "minus.circle").frame(width: 36, height: 36) }.buttonStyle(.plain).foregroundStyle(Theme.muted).help("Retirer cette préférence")
                        }.padding(.vertical, 8)
                        Rectangle().fill(Theme.line).frame(height: 1)
                    }
                    if library.vocabulary.isEmpty { Text("Ajoutez votre premier mot, ou connectez votre compte\npour retrouver le vocabulaire de votre téléphone.").font(.system(size: 13)).foregroundStyle(Theme.muted).multilineTextAlignment(.center).padding(.top, 70) }
                }
            }
            HStack(spacing: 10) { Image(systemName: "sparkle").foregroundStyle(Theme.accent); Text("Une retouche dans le panneau peut devenir une préférence. Vous gardez toujours le choix de la mémoriser.").font(.system(size: 11)).foregroundStyle(Theme.muted) }
        }.padding(44).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
    private func add() { library.perform { try library.addVocabulary(line); line = "" } }
}
