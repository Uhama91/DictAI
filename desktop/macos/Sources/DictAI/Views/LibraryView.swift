import SwiftUI
import DictAICore

struct LibraryView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: LibraryStore
    @ObservedObject var account: AccountController
    @ObservedObject var sync: SyncCoordinator
    private var page: String {
        get { model.libraryPage }
        nonmutating set { model.libraryPage = newValue }
    }
    @State private var query = ""
    @State private var newFolder = false
    @State private var folderName = ""
    @State private var folderParent = ""
    private var shownNotes: [Note] {
        library.notes.filter { note in
            (library.selectedFolder == nil || note.folderId == library.selectedFolder) &&
            (query.isEmpty || (note.title + " " + note.text).localizedCaseInsensitiveContains(query))
        }
    }
    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(Theme.line).frame(width: 1)
            if page == "notes" {
                noteList
                Rectangle().fill(Theme.line).frame(width: 1)
                if let note = library.notes.first(where: { $0.id == library.selectedNote }) {
                    NoteEditorView(library: library, note: note).id(note.id)
                } else { emptyEditor }
            } else if page == "vocabulary" {
                VocabularyView(library: library)
            } else if page == "history" {
                HistoryView(history: library.history)
            } else {
                SettingsView(model: model, library: library, account: account, sync: sync)
            }
        }.background(Theme.background).foregroundStyle(Theme.cream)
        .tint(Theme.accent)
        .preferredColorScheme(Theme.scheme(library.preference("theme_mode", default: "system")))
        .alert("DictAI", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) {
            Button("Fermer") { library.error = nil }
        } message: { Text(library.error ?? "") }
        .sheet(isPresented: $newFolder) {
            VStack(alignment: .leading, spacing: 20) {
                Text("Nouveau dossier").font(.title2.bold())
                Picker("Dans", selection: $folderParent) {
                    Text("À la racine").tag("")
                    ForEach(library.folders) { Text($0.name).tag($0.name) }
                }
                TextField("Nom du dossier", text: $folderName).textFieldStyle(.roundedBorder)
                Text("Les sous-dossiers sont partagés sous forme de chemins, par exemple École / Réunions.").font(.caption).foregroundStyle(Theme.muted)
                HStack { Spacer(); Button("Annuler") { newFolder = false }; Button("Créer") {
                    library.perform {
                        try library.createFolder((folderParent.isEmpty ? "" : folderParent + " / ") + folderName)
                        newFolder = false; folderName = ""; page = "notes"; library.selectedNote = nil
                    }
                }.buttonStyle(AccentButtonStyle()).disabled(folderName.trimmingCharacters(in: .whitespaces).isEmpty) }
            }.padding(28).frame(width: 420).background(Theme.surface)
        }
    }
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            Brand().padding(.top, 32).padding(.bottom, 4)
            Text("Le carnet de votre voix.").font(.system(size: 12)).foregroundStyle(Theme.muted).padding(.bottom, 34)
            nav("Mes notes", icon: "book.closed", target: "notes", count: library.notes.count)
            nav("Historique des dictées", icon: "clock.arrow.circlepath", target: "history")
            nav("Vocabulaire", icon: "textformat.abc", target: "vocabulary", count: library.vocabulary.count)
            nav("Réglages", icon: "slider.horizontal.3", target: "settings")
            HStack { SmallLabel(text: "Dossiers"); Spacer(); Button { folderParent = ""; newFolder = true } label: { Image(systemName: "plus").frame(width: 28, height: 28) }.buttonStyle(.plain).help("Créer un dossier") }.padding(.top, 30).padding(.bottom, 7)
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    Button { library.selectedFolder = nil; page = "notes" } label: {
                        Label("Toutes les notes", systemImage: "tray").font(.system(size: 12)).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 9)
                    }.buttonStyle(.plain).foregroundStyle(library.selectedFolder == nil ? Theme.accent : Theme.muted)
                    ForEach(library.folders) { folder in
                        Button { library.selectedFolder = folder.id; library.selectedNote = nil; page = "notes" } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "folder").foregroundStyle(Theme.accent.opacity(0.8))
                                Text(folder.name.components(separatedBy: " / ").last ?? folder.name).lineLimit(2)
                                Spacer(minLength: 0)
                                Text("\(library.notes.filter { $0.folderId == folder.id }.count)").foregroundStyle(Theme.muted).font(.system(size: 10)).monospacedDigit()
                            }.font(.system(size: 12)).padding(.vertical, 10).padding(.horizontal, 8)
                                .padding(.leading, CGFloat(min(folder.name.components(separatedBy: " / ").count - 1, 3) * 10))
                                .background(library.selectedFolder == folder.id ? Theme.elevated : .clear, in: RoundedRectangle(cornerRadius: 7))
                        }.buttonStyle(.plain).help(folder.name)
                            .contextMenu { Button("Créer un sous-dossier") { folderParent = folder.name; newFolder = true } }
                    }
                }
            }
            Spacer(minLength: 16)
            VStack(alignment: .leading, spacing: 10) {
                HStack { Text("⌥").font(.system(size: 22)).frame(width: 38, height: 34).background(Theme.elevated, in: RoundedRectangle(cornerRadius: 6)); Text("Démarrer, reprendre ou terminer.\n×2 Pause · ×3 Annuler").font(.system(size: 11)).fixedSize(horizontal: false, vertical: true).foregroundStyle(Theme.muted) }
                Button { model.state == .listening ? model.toggle() : model.begin(destination: .note) } label: { Label(model.state == .listening ? "Terminer la dictée" : "Dicter une note", systemImage: "mic").frame(maxWidth: .infinity).padding(.vertical, 7) }.buttonStyle(AccentButtonStyle())
                Button("Ouvrir le brouillon") { model.showDraft() }.font(.system(size: 11)).buttonStyle(.plain).foregroundStyle(Theme.muted).frame(maxWidth: .infinity)
                    .disabled(model.state == .idle && model.draft.text.isEmpty)
            }.padding(.bottom, 24)
            Button { page = "settings" } label: {
                HStack(alignment: .top, spacing: 8) {
                    Circle().fill(account.session == nil ? Theme.muted : Theme.accent).frame(width: 6, height: 6).padding(.top, 4)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(account.session?.email ?? "Sur ce Mac").font(.system(size: 11)).lineLimit(1)
                        Text(account.session == nil ? "Connexion facultative" : sync.busy ? "Synchronisation…" : "Compte connecté").font(.system(size: 10)).foregroundStyle(Theme.muted)
                    }
                }
            }.buttonStyle(.plain).padding(.bottom, 22)
        }.padding(.horizontal, 20).frame(width: 224).background(NotebookBackground(margin: 12))
    }
    private func nav(_ title: String, icon: String, target: String, count: Int? = nil) -> some View {
        Button { page = target; if target == "notes" { library.selectedFolder = nil } } label: {
            HStack(spacing: 10) {
                Image(systemName: icon).frame(width: 18)
                Text(title).font(.system(size: 12, weight: page == target ? .semibold : .regular))
                Spacer()
                if let count { Text("\(count)").font(.system(size: 10)).monospacedDigit().foregroundStyle(Theme.muted) }
            }.padding(.horizontal, 11).frame(height: 40).background(page == target ? Theme.elevated : .clear, in: RoundedRectangle(cornerRadius: 8))
        }.buttonStyle(.plain).foregroundStyle(page == target ? Theme.cream : Theme.muted).padding(.bottom, 3)
    }
    private var noteList: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(library.folders.first { $0.id == library.selectedFolder }?.name.components(separatedBy: " / ").last ?? "Mes notes")
                    .font(Theme.handwriting(32)).lineLimit(1)
                Spacer()
                Button { library.perform { _ = try library.createNote(folder: library.selectedFolder) } } label: { Image(systemName: "square.and.pencil").frame(width: 28, height: 32) }.buttonStyle(.plain).help("Nouvelle note")
            }.padding(.top, 35).padding(.bottom, 20)
            HStack { Image(systemName: "magnifyingglass").foregroundStyle(Theme.muted); TextField("Rechercher une note", text: $query).textFieldStyle(.plain).font(.system(size: 12)) }
                .padding(11).background(Theme.surface, in: RoundedRectangle(cornerRadius: 9)).padding(.bottom, 24)
            SmallLabel(text: "\(shownNotes.count) note\(shownNotes.count <= 1 ? "" : "s")").padding(.bottom, 10)
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(shownNotes) { note in
                        Button { library.selectedNote = note.id } label: {
                            VStack(alignment: .leading, spacing: 9) {
                                Text(note.title).font(.system(size: 13, weight: .semibold)).lineLimit(2)
                                Text(note.text.isEmpty ? "Commencez à écrire ou à dicter…" : note.text).font(.system(size: 12)).lineSpacing(3).foregroundStyle(Theme.muted).lineLimit(3)
                                HStack { Text(Date(timeIntervalSince1970: Double(note.updated) / 1000).formatted(date: .abbreviated, time: .omitted)); Spacer(); if !note.images.isEmpty { Image(systemName: "photo"); Text("\(note.images.count)") } }.font(.system(size: 10)).foregroundStyle(Theme.muted)
                            }.frame(maxWidth: .infinity, alignment: .leading).padding(15)
                                .background(library.selectedNote == note.id ? Theme.elevated : Theme.surface.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
                        }.buttonStyle(.plain)
                    }
                    if shownNotes.isEmpty { Text(query.isEmpty ? "Vos notes apparaîtront ici." : "Aucune note correspondante.").font(.caption).foregroundStyle(Theme.muted).padding(.top, 30) }
                }
            }
            Text(sync.status).font(.system(size: 10)).foregroundStyle(Theme.muted).lineLimit(3).padding(.vertical, 16)
        }.padding(.horizontal, 20).frame(width: 290)
    }
    private var emptyEditor: some View {
        VStack(spacing: 22) {
            BrandMark(width: 100)
            Text("Votre prochain mot,\nvotre prochaine idée.").font(Theme.handwriting(38)).multilineTextAlignment(.center)
            Text("Dans une autre application : ⌥ pour parler,\npuis ⌥ pour insérer. Ici, dictez et classez vos notes.").font(.system(size: 13)).lineSpacing(5).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            HStack(spacing: 14) {
                Button("Nouvelle note") { library.perform { _ = try library.createNote(folder: library.selectedFolder) } }
                Button("Dicter une note") { model.begin(destination: .note) }.buttonStyle(AccentButtonStyle())
            }
            if !model.draft.text.isEmpty { Button("Reprendre le brouillon") { model.showDraft() }.buttonStyle(.link) }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(NotebookBackground())
    }
}
