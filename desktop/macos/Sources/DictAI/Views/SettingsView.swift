import SwiftUI
import ServiceManagement
import AVFoundation
import Speech
import DictAICore

struct SettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var library: LibraryStore
    @ObservedObject var account: AccountController
    @ObservedObject var sync: SyncCoordinator
    @State private var apiKey = ""
    @State private var keyMessage = ""
    @State private var permissionsRevision = 0
    @State private var loginEnabled = SMAppService.mainApp.status == .enabled
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("Réglages").font(Theme.handwriting(40))
                Button("Reprendre la configuration") { model.closeOverlay(); model.setup.restart() }
                group("Compte et synchronisation", icon: "arrow.triangle.2.circlepath") {
                    Text(account.session?.email ?? "Retrouvez vos notes et vos mots sur tous vos appareils.").font(.system(size: 14, weight: .medium))
                    Text("Notes, dossiers, images et préférences sont partagés avec Android. Sans compte, tout reste sur ce Mac.").font(.caption).foregroundStyle(Theme.muted)
                    HStack {
                        if account.session == nil {
                            Button(account.connecting ? "Connexion…" : "Se connecter avec Google") { account.connect() }.disabled(account.connecting)
                        } else {
                            Button(sync.busy ? "Synchronisation…" : "Synchroniser maintenant") { Task { await sync.sync() } }.disabled(sync.busy)
                            Spacer()
                            Button("Se déconnecter") { library.perform { try account.disconnect() } }
                        }
                    }
                    Text(account.session == nil ? account.status : sync.status).font(.caption).foregroundStyle(Theme.accent)
                }
                group("Dictée et raccourci", icon: "option") {
                    Picker("Apparence", selection: preference("theme_mode", fallback: "system")) {
                        Text("Comme le Mac").tag("system"); Text("Carnet clair").tag("light"); Text("Carnet sombre").tag("dark")
                    }
                    HStack { Text("Raccourci global"); Spacer(); Text("⌥ Option seule").fontWeight(.semibold).foregroundStyle(Theme.accent) }
                    Text("1 appui : démarrer, ou terminer et insérer. 2 appuis rapides : pause ou reprise. 3 appuis rapides : annuler sans envoyer ; le brouillon reste disponible. En mode Note, terminer enregistre la note.").font(.caption).foregroundStyle(Theme.muted)
                    Text("Le rythme suit le réglage de double-clic du Mac (\(Int(NSEvent.doubleClickInterval * 1_000)) ms). Un bref délai distingue les gestes avant d’agir. Appuyez puis relâchez ; maintenir Option, écrire ou cliquer en même temps ne déclenche aucune commande.").font(.caption).foregroundStyle(Theme.muted)
                    Text("Les mots récents apparaissent en bas de la pastille ; les lignes précédentes remontent et s’effacent progressivement en haut. Cliquez sur le texte pour le modifier, ou sur les flèches pour ouvrir une fenêtre. Pause et Reprendre conservent le même brouillon. Échap ferme sans effacer.").font(.caption).foregroundStyle(Theme.muted)
                    HStack { Text("Moteur vocal"); Spacer(); Text("Apple · sur cet appareil").foregroundStyle(Theme.muted) }
                    Picker("Langue", selection: preference("dictation_language", fallback: "fr")) { Text("Français").tag("fr"); Text("English").tag("en") }
                    Toggle("Ajouter une espace après l’insertion", isOn: boolean("trailing_space", fallback: true))
                    Toggle("Ouvrir DictAI à la connexion au Mac", isOn: Binding(get: { loginEnabled }, set: { enabled in
                        library.perform {
                            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                            loginEnabled = SMAppService.mainApp.status == .enabled
                        }
                    }))
                }
                group("Autorisations du Mac", icon: "hand.raised") {
                    permission("Microphone", allowed: AVCaptureDevice.authorizationStatus(for: .audio) == .authorized, key: "Privacy_Microphone")
                    permission("Reconnaissance vocale", allowed: SFSpeechRecognizer.authorizationStatus() == .authorized, key: "Privacy_SpeechRecognition")
                    HStack {
                        Label(TextInsertion.trusted ? "Accessibilité autorisée" : "Accessibilité à autoriser", systemImage: TextInsertion.trusted ? "checkmark.circle.fill" : "circle")
                        Spacer(); Button("Autoriser") { TextInsertion.requestPermission(); openPrivacy("Privacy_Accessibility") }
                    }
                    Text("Le micro et la reconnaissance sont demandés à la première dictée. L’accessibilité permet le raccourci global et l’insertion dans un autre logiciel.").font(.caption).foregroundStyle(Theme.muted)
                    Button("Vérifier à nouveau") { permissionsRevision += 1; model.shortcut.start() }.font(.caption)
                }.id(permissionsRevision)
                group("Correction cloud", icon: "wand.and.stars") {
                    Picker("Correction automatique", selection: preference("formatting_engine", fallback: "off")) {
                        Text("Désactivé").tag("off"); Text("OpenRouter").tag("cloud")
                    }
                    Text("La transcription reste locale. Si vous activez OpenRouter, le texte et le vocabulaire utile sont envoyés au modèle choisi pour correction.").font(.caption).foregroundStyle(Theme.muted)
                    Picker("Modèle", selection: preference("cloud_cleanup_model", fallback: CleanupModel.default.id)) {
                        ForEach(CleanupModel.all) { Text($0.name).tag($0.id) }
                    }
                    HStack {
                        SecureField("Clé OpenRouter", text: $apiKey).textFieldStyle(.roundedBorder)
                        Button("Enregistrer") {
                            library.perform {
                                let clean = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
                                try require(!clean.isEmpty, "Saisissez une clé OpenRouter.")
                                try Keychain.write("openrouter-key", data: Data(clean.utf8)); apiKey = ""; keyMessage = "Clé enregistrée dans le Trousseau de ce Mac."
                            }
                        }.disabled(apiKey.isEmpty)
                        Button("Retirer") { library.perform { try Keychain.write("openrouter-key", data: nil); keyMessage = "Clé retirée de ce Mac." } }
                    }
                    Text(keyMessage.isEmpty ? "La clé reste dans le Trousseau et n’est jamais synchronisée." : keyMessage).font(.caption).foregroundStyle(Theme.muted)
                }
                Text("DictAI pour macOS · Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "développement")\nLe modèle vocal local dépend des langues disponibles sur le Mac. Les modèles Android ne sont pas copiés sur cet appareil.")
                    .font(.system(size: 11)).foregroundStyle(Theme.muted).lineSpacing(5)
            }.padding(42).frame(maxWidth: 800, alignment: .leading).frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    private func group<Content: View>(_ title: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 15) {
            Label(title, systemImage: icon).font(Theme.handwriting(26)).foregroundStyle(Theme.accent).padding(.bottom, 3)
            content()
        }.font(.system(size: 12)).padding(23).frame(maxWidth: .infinity, alignment: .leading).background(Theme.surface, in: RoundedRectangle(cornerRadius: 13))
    }
    private func preference(_ key: String, fallback: String) -> Binding<String> { Binding(get: { library.preference(key, default: fallback) }, set: { library.setPreference(key, $0) }) }
    private func boolean(_ key: String, fallback: Bool) -> Binding<Bool> { Binding(get: { library.preference(key, default: String(fallback)) == "true" }, set: { library.setPreference(key, String($0)) }) }
    private func permission(_ title: String, allowed: Bool, key: String) -> some View {
        HStack { Label(title + (allowed ? (title == "Reconnaissance vocale" ? " autorisée" : " autorisé") : " à autoriser"), systemImage: allowed ? "checkmark.circle.fill" : "circle"); Spacer(); Button("Réglages Système") { openPrivacy(key) } }
    }
    private func openPrivacy(_ key: String) { if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?" + key) { NSWorkspace.shared.open(url) } }
}
