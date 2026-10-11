import AppKit
import SwiftUI

struct HistoryView: View {
    @ObservedObject var history: DictationHistoryStore
    @State private var selectedID: String?
    @State private var confirmClear = false
    @State private var failure: String?
    @State private var copied = false
    private var selected: RecentDictation? {
        history.entries.first { $0.id == selectedID } ?? history.entries.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Historique des dictées").font(Theme.handwriting(36))
                    Text("Les 10 dernières dictées sur ce Mac. La suivante remplace la plus ancienne.")
                        .font(.system(size: 12)).foregroundStyle(Theme.muted)
                }
                Spacer()
                Button("Vider l’historique") { confirmClear = true }
                    .disabled(history.entries.isEmpty && history.error == nil)
            }.padding(28)
            Rectangle().fill(Theme.line).frame(height: 1)
            if let error = history.error {
                Text(error).foregroundStyle(Theme.recording).padding(28)
            } else if history.entries.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "clock.arrow.circlepath").font(.system(size: 34)).foregroundStyle(Theme.accent)
                    Text("Aucune dictée récente").font(Theme.handwriting(32))
                    Text("Vos prochaines dictées apparaîtront ici, même si leur insertion échoue.")
                        .font(.system(size: 13)).foregroundStyle(Theme.muted)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                HStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: 16) {
                        SmallLabel(text: "\(history.entries.count) / 10 · Les plus récentes d’abord")
                        ScrollView {
                            LazyVStack(spacing: 8) {
                                ForEach(history.entries) { entry in
                                    Button { selectedID = entry.id; copied = false } label: {
                                        VStack(alignment: .leading, spacing: 9) {
                                            Text(entry.date.formatted(date: .abbreviated, time: .shortened))
                                                .font(.system(size: 11)).foregroundStyle(Theme.muted)
                                            Text(entry.text).font(.system(size: 13)).lineSpacing(3).lineLimit(3)
                                                .frame(maxWidth: .infinity, alignment: .leading)
                                        }.padding(16).background(selected?.id == entry.id ? Theme.elevated : Theme.surface, in: RoundedRectangle(cornerRadius: 9))
                                    }.buttonStyle(.plain)
                                }
                            }
                        }
                    }.padding(20).frame(width: 300)
                    Rectangle().fill(Theme.line).frame(width: 1)
                    if let selected {
                        VStack(alignment: .leading, spacing: 22) {
                            HStack {
                                Text(selected.date.formatted(date: .abbreviated, time: .shortened))
                                    .font(.system(size: 12)).foregroundStyle(Theme.muted)
                                Spacer()
                                Button {
                                    NSPasteboard.general.clearContents()
                                    copied = NSPasteboard.general.setString(selected.text, forType: .string)
                                } label: { Label(copied ? "Texte copié" : "Copier le texte", systemImage: copied ? "checkmark" : "doc.on.doc") }
                                    .buttonStyle(AccentButtonStyle())
                            }
                            ScrollView {
                                Text(selected.text).font(.system(size: 16)).lineSpacing(7).textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .topLeading)
                            }
                            Text("Tampon local · aucun audio conservé · aucune synchronisation")
                                .font(.system(size: 11)).foregroundStyle(Theme.muted)
                        }.padding(28).frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
            }
        }.background(Theme.background).foregroundStyle(Theme.cream).tint(Theme.accent)
            .onChange(of: history.entries) { _, _ in copied = false }
            .confirmationDialog("Vider l’historique ?", isPresented: $confirmClear) {
                Button("Vider l’historique", role: .destructive) {
                    do { try history.clear(); selectedID = nil; copied = false }
                    catch { failure = error.localizedDescription }
                }
            } message: { Text("Les dictées récentes seront effacées de ce Mac. Vos notes sont conservées.") }
            .alert("Historique", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
                Button("Fermer") { failure = nil }
            } message: { Text(failure ?? "") }
    }
}
