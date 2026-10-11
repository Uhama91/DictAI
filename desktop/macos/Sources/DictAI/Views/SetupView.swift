import SwiftUI

struct WorkspaceView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var setup: SetupState
    var body: some View {
        VStack(spacing: 0) {
            AppUpdateBanner(updates: model.appUpdates)
            if setup.presented {
                SetupView(model: model, setup: setup, library: model.library, account: model.account, sync: model.sync)
            } else {
                LibraryView(model: model, library: model.library, account: model.account, sync: model.sync)
            }
        }.onAppear { model.start() }
    }
}

struct SetupView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var setup: SetupState
    @ObservedObject var library: LibraryStore
    @ObservedObject var account: AccountController
    @ObservedObject var sync: SyncCoordinator
    @StateObject private var permissions = SetupPermissions()
    @State private var error: String?
    private let steps = ["Bienvenue", "Ce Mac", "Votre compte", "Première dictée"]
    private var language: String { library.preference("dictation_language", default: "fr") }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack { Brand(); Spacer(); Text("CONFIGURATION · \(setup.step + 1) / 4").font(.system(size: 11, weight: .semibold)).tracking(1.5).foregroundStyle(Theme.muted) }
                .padding(.bottom, 26)
            HStack(spacing: 22) {
                ForEach(Array(steps.enumerated()), id: \.offset) { index, title in
                    HStack(spacing: 7) {
                        Text("\(index + 1)").font(.system(size: 11, weight: .semibold)).frame(width: 24, height: 24)
                            .background(index == setup.step ? Theme.accent.opacity(0.18) : Theme.surface, in: Circle())
                        Text(title).font(.system(size: 12))
                    }.foregroundStyle(index == setup.step ? Theme.accent : Theme.muted)
                }
            }.padding(.bottom, 28)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    switch setup.step {
                    case 0: welcome
                    case 1: device
                    case 2: accountStep
                    default: firstDictation
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 18)
            }
            Divider().padding(.vertical, 18)
            HStack {
                if setup.step > 0 { Button("Retour") { setup.step -= 1 } }
                Button("Configurer plus tard") { finish() }.buttonStyle(.plain).foregroundStyle(Theme.muted)
                Spacer()
                if setup.step < 3 {
                    Button(setup.step == 0 ? "Commencer" : "Continuer") { setup.step += 1; refresh() }
                        .buttonStyle(AccentButtonStyle()).disabled(account.connecting)
                } else {
                    Button("Ouvrir DictAI") { finish() }.buttonStyle(AccentButtonStyle())
                }
            }.font(.system(size: 13))
        }.padding(42).frame(maxWidth: 880).frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(NotebookBackground()).foregroundStyle(Theme.cream).tint(Theme.accent)
            .preferredColorScheme(Theme.scheme(library.preference("theme_mode", default: "system")))
            .onAppear { refresh() }
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in refresh() }
            .onChange(of: account.session?.userId) { _, _ in refresh(); Task { await sync.sync() } }
            .alert("Configuration", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("Fermer") { error = nil }
            } message: { Text(error ?? "") }
    }
    private var welcome: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Votre voix,\ndans vos applications.").font(Theme.handwriting(42)).lineSpacing(3)
            Text("Dictez avec la touche Option, relisez si besoin, puis insérez votre texte. Retrouvez vos mots et vos notes sur vos autres appareils avec votre compte Google.")
                .font(.system(size: 15)).lineSpacing(6).fixedSize(horizontal: false, vertical: true)
            feature("mic", title: "La voix reste sur ce Mac", detail: "La transcription utilise le modèle local d’Apple. Aucun repli vers un serveur de transcription.")
            feature("person.crop.circle", title: "Un compte, seulement si vous le souhaitez", detail: "DictAI reste utilisable sans compte. Google sert à retrouver vos données DictAI, pas vos dossiers Finder.")
            Text("macOS 14 ou plus récent · Apple Silicon recommandé. La dictée locale sur Intel reste à valider. L’étape suivante vérifie les fonctions disponibles sur ce Mac.")
                .font(.system(size: 12)).foregroundStyle(Theme.muted).lineSpacing(4)
        }
    }
    private var device: some View {
        VStack(alignment: .leading, spacing: 12) {
            heading("Préparer ce Mac", detail: "Ces autorisations se règlent sur chaque ordinateur. Elles ne sont jamais synchronisées.")
            HStack {
                Picker("Langue de dictée", selection: Binding(get: { language }, set: { library.setPreference("dictation_language", $0); refresh() })) {
                    Text("Français").tag("fr"); Text("English").tag("en")
                }.frame(width: 290)
                Spacer()
                Text("\(Int(ProcessInfo.processInfo.physicalMemory / 1_073_741_824)) Go de mémoire").foregroundStyle(Theme.muted)
            }.font(.system(size: 12))
            status("Microphone", ready: permissions.readiness.microphone, action: "Réglages") { permissions.openPrivacy("Privacy_Microphone") }
            status("Reconnaissance vocale", ready: permissions.readiness.speech, action: "Réglages") { permissions.openPrivacy("Privacy_SpeechRecognition") }
            Button(permissions.requesting ? "Autorisation en cours…" : "Autoriser le micro et la reconnaissance") {
                Task { await permissions.requestAudio(language: language) }
            }.disabled(permissions.requesting || (permissions.readiness.microphone && permissions.readiness.speech))
            status("Accessibilité · raccourci et insertion", ready: permissions.readiness.accessibility, action: "Autoriser") { permissions.requestAccessibility() }
            status("Modèle vocal local", ready: permissions.readiness.localRecognition, action: "Réglages Clavier") { permissions.openKeyboard() }
            Text(permissions.readiness.localRecognition
                 ? "Le modèle local est disponible. Un essai vocal permettra de vérifier la qualité et la réactivité sur ce Mac."
                 : "Si le modèle manque, activez la dictée dans les réglages Clavier et choisissez cette langue, puis vérifiez à nouveau. S’il reste indisponible, la dictée de DictAI ne peut pas fonctionner localement sur cette configuration.")
                .font(.system(size: 12)).foregroundStyle(Theme.muted).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
            Button("Vérifier à nouveau") { refresh() }
        }
    }
    private var accountStep: some View {
        VStack(alignment: .leading, spacing: 23) {
            heading("Retrouvez vos mots, partout", detail: "Choisissez le même compte Google que dans DictAI sur votre téléphone et votre tablette.")
            if let session = account.session {
                Label(session.email, systemImage: "checkmark.circle.fill").foregroundStyle(Theme.accent)
                Text(sync.status).font(.system(size: 13)).foregroundStyle(Theme.muted)
                Text("\(library.notes.count) notes · \(library.vocabulary.count) entrées de vocabulaire").font(.system(size: 15, weight: .medium))
                HStack {
                    Button(sync.busy ? "Synchronisation…" : "Synchroniser maintenant") { Task { await sync.sync() } }.disabled(sync.busy)
                    Button("Changer de compte") { attempt { try account.disconnect() } }
                }
            } else {
                Button(account.connecting ? "Connexion…" : "Se connecter avec Google") { account.connect() }
                    .buttonStyle(AccentButtonStyle()).disabled(account.connecting)
                Text(account.status).font(.system(size: 12)).foregroundStyle(Theme.muted)
                Text("Vous préférez rester sans compte ? Cliquez sur Continuer.").font(.system(size: 13))
            }
            feature("arrow.triangle.2.circlepath", title: "Ce qui est partagé", detail: "Vos notes, dossiers, images, mots de vocabulaire et préférences DictAI.")
            feature("lock", title: "Ce qui reste sur ce Mac", detail: "Les autorisations, les clés privées et les fichiers audio. Les notes créées sans compte restent dans leur espace local ; la connexion ne les transfère pas automatiquement.")
        }
    }
    private var firstDictation: some View {
        VStack(alignment: .leading, spacing: 24) {
            heading("Faites un premier essai", detail: "Ouvrez DictAI, puis placez le curseur dans un document TextEdit vide.")
            feature("1.circle", title: "Appuyez puis relâchez Option", detail: "La pastille apparaît. Prononcez une phrase avec quelques mots de votre vocabulaire.")
            feature("2.circle", title: "Appuyez à nouveau sur Option", detail: "La dictée se termine, le texte est inséré et la pastille se ferme. Une copie est conservée dans vos notes.")
            Text("Deux appuis rapides : pause ou reprise. Trois appuis : annuler sans envoyer. Échap ferme la fenêtre en conservant le brouillon.")
                .font(.system(size: 13)).foregroundStyle(Theme.muted).lineSpacing(4)
            Label(permissions.readiness.canInsert ? "Les prérequis sont réunis pour cet essai." : "La configuration reste à compléter dans l’étape Ce Mac.",
                  systemImage: permissions.readiness.canInsert ? "checkmark.circle" : "info.circle")
                .font(.system(size: 13)).foregroundStyle(Theme.accent)
            Text("Vous pouvez rouvrir cet assistant depuis Réglages → Reprendre la configuration. Vos notes et votre compte sont conservés.")
                .font(.system(size: 12)).foregroundStyle(Theme.muted)
        }
    }
    private func heading(_ title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(Theme.handwriting(38))
            Text(detail).font(.system(size: 14)).foregroundStyle(Theme.muted).lineSpacing(4)
        }
    }
    private func feature(_ icon: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 15) {
            Image(systemName: icon).font(.system(size: 23)).foregroundStyle(Theme.accent).frame(width: 32)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.system(size: 14, weight: .semibold))
                Text(detail).font(.system(size: 12)).foregroundStyle(Theme.muted).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
    private func status(_ title: String, ready: Bool, action: String, perform: @escaping () -> Void) -> some View {
        HStack {
            Label(title, systemImage: ready ? "checkmark.circle.fill" : "circle").foregroundStyle(ready ? Theme.accent : Theme.cream)
            Spacer()
            Text(ready ? "Disponible" : "À vérifier").foregroundStyle(Theme.muted)
            Button(action, action: perform)
        }.font(.system(size: 12))
    }
    private func refresh() { permissions.refresh(language: language); if !PreviewMode.enabled { model.shortcut.start() } }
    private func attempt(_ action: () throws -> Void) { do { try action() } catch { self.error = error.localizedDescription } }
    private func finish() { attempt { try setup.complete() } }
}
