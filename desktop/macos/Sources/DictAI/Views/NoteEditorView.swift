import SwiftUI
import DictAICore
import UniformTypeIdentifiers

struct NoteEditorView: View {
    @ObservedObject var library: LibraryStore
    @State var note: Note
    @State private var base: String?
    @State private var confirmDelete = false
    @FocusState private var editing: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack {
                Picker("Dossier", selection: Binding(get: { note.folderId ?? "" }, set: { note.folderId = $0.isEmpty ? nil : $0; save() })) {
                    Text("Sans dossier").tag("")
                    ForEach(library.folders) { Text($0.name).tag($0.id) }
                }.labelsHidden().frame(maxWidth: 240)
                Spacer()
                Button { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(note.text, forType: .string) } label: { Image(systemName: "doc.on.doc") }.help("Copier la note")
                Menu {
                    Button("Exporter en texte…") { export() }
                    Button("Supprimer la note", role: .destructive) { confirmDelete = true }
                } label: { Image(systemName: "ellipsis") }.menuStyle(.borderlessButton).frame(width: 24)
            }.buttonStyle(.borderless).padding(.top, 13)
            VStack(alignment: .leading, spacing: 11) {
                TextField("Titre", text: Binding(get: { note.title }, set: { note.title = TextLimit.prefix($0, utf16: 80); note.renamed = true; if !note.title.trimmingCharacters(in: .whitespaces).isEmpty { save() } }))
                    .font(Theme.handwriting(36)).textFieldStyle(.plain).focused($editing)
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle").foregroundStyle(Theme.accent)
                    Text("Enregistrement automatique").foregroundStyle(Theme.muted)
                    Spacer()
                    Text("\(note.text.split(whereSeparator: \.isWhitespace).count) mot\(note.text.split(whereSeparator: \.isWhitespace).count > 1 ? "s" : "")").foregroundStyle(Theme.muted).monospacedDigit()
                }.font(.system(size: 10))
            }
            Rectangle().fill(Theme.line).frame(height: 1)
            TextEditor(text: Binding(get: { note.text }, set: { note.text = $0; save() }))
                .font(.system(size: 15)).lineSpacing(7).scrollContentBackground(.hidden).focused($editing)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            if !note.images.isEmpty {
                ScrollView(.horizontal) {
                    HStack(spacing: 12) {
                        ForEach(note.images) { image in
                            if let url = library.imageURL(image.id), let native = NSImage(contentsOf: url) {
                                Button { NSWorkspace.shared.open(url) } label: {
                                    VStack { Image(nsImage: native).resizable().scaledToFit().frame(height: 105); Text("Image \(image.number)").font(.caption2) }
                                }.buttonStyle(.plain)
                            } else { Label("Image \(image.number) en attente", systemImage: "photo").font(.caption) }
                        }
                    }
                }.frame(height: 136)
            }
            Text("DictAI · Vos notes restent disponibles hors connexion.").font(.system(size: 10)).foregroundStyle(Theme.muted).padding(.bottom, 7)
        }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.surface)
        .onAppear { base = library.document.values["note:" + note.id] }
        .onChange(of: library.document) { _, document in
            guard !editing, let raw = document.values["note:" + note.id], raw != base, let remote = try? Note.decode(raw) else { return }
            note = remote; base = raw
        }
        .alert("Supprimer cette note ?", isPresented: $confirmDelete) {
            Button("Annuler", role: .cancel) {}
            Button("Supprimer", role: .destructive) { library.deleteNote(note.id) }
        } message: { Text("La suppression sera synchronisée sur vos appareils connectés.") }
    }
    private func save() {
        library.perform { note = try library.save(note, basedOn: base); base = library.document.values["note:" + note.id] }
    }
    private func export() {
        let panel = NSSavePanel(); panel.allowedContentTypes = [.plainText]; panel.nameFieldStringValue = note.title + ".txt"
        if panel.runModal() == .OK, let url = panel.url { library.perform { try note.text.write(to: url, atomically: true, encoding: .utf8) } }
    }
}
