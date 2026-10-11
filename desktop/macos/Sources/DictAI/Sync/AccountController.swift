import AppKit
import AuthenticationServices
import CryptoKit
import DictAICore

@MainActor final class AccountController: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    @Published private(set) var session: AccountSession?
    @Published var status = "Sans compte · données sur ce Mac"
    @Published var connecting = false
    let client = SupabaseClient()
    private var browser: ASWebAuthenticationSession?
    private(set) var generation = UUID()
    var onChange: ((AccountSession?) throws -> Void)?
    override init() { super.init() }
    func restore() throws {
        try restore(data: Keychain.read("account-session"))
    }
    func restore(data: Data?) throws {
        session = try data.map { try JSONDecoder().decode(AccountSession.self, from: $0) }
        status = session?.email ?? "Sans compte · données sur ce Mac"
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        NSApp.keyWindow ?? NSApp.windows.first ?? NSWindow()
    }
    func connect() {
        guard !connecting else { return }
        connecting = true
        let verifier = nonce(); let flow = nonce(); let started = Date(); let epoch = generation
        let challenge = base64(Data(SHA256.hash(data: Data(verifier.utf8))))
        var parts = URLComponents(url: SupabaseClient.base.appendingPathComponent("auth/v1/authorize"), resolvingAgainstBaseURL: false)!
        parts.queryItems = [.init(name: "provider", value: "google"), .init(name: "scopes", value: "openid"),
            .init(name: "redirect_to", value: "com.uhama.whisperpin://auth/callback?flow=" + flow),
            .init(name: "code_challenge", value: challenge), .init(name: "code_challenge_method", value: "s256"), .init(name: "prompt", value: "select_account")]
        browser = ASWebAuthenticationSession(url: parts.url!, callbackURLScheme: "com.uhama.whisperpin") { [weak self] url, error in
            Task { @MainActor in
                guard let self, self.generation == epoch else { return }
                defer { self.connecting = false; self.browser = nil }
                do {
                    guard let url, error == nil else { throw DataError.invalid("Connexion annulée.") }
                    let callback = URLComponents(url: url, resolvingAgainstBaseURL: false)
                    let items = callback?.queryItems ?? []
                    try require(url.scheme == "com.uhama.whisperpin" && url.host == "auth" && url.path == "/callback" && url.port == nil && url.user == nil && url.fragment == nil && Date().timeIntervalSince(started) < 600 && Set(items.map(\.name)).count == items.count, "Retour de connexion invalide.")
                    try require(items.first(where: { $0.name == "flow" })?.value == flow && !items.contains(where: { $0.name == "error" }), "Connexion refusée.")
                    guard let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty, code.count <= 2048 else { throw DataError.invalid("Code de connexion absent.") }
                    let account = try await self.client.token(grant: "pkce", payload: ["auth_code": code, "code_verifier": verifier])
                    guard self.generation == epoch else { return }
                    try Keychain.write("account-session", data: JSONEncoder().encode(account))
                    do { try self.onChange?(account) }
                    catch {
                        try Keychain.write("account-session", data: self.session.map { try JSONEncoder().encode($0) })
                        throw error
                    }
                    self.session = account; self.generation = UUID(); self.status = account.email
                } catch { self.status = error.localizedDescription }
            }
        }
        browser?.presentationContextProvider = self
        browser?.prefersEphemeralWebBrowserSession = true
        if browser?.start() != true { connecting = false; status = "Impossible d’ouvrir la connexion Google." }
    }
    func validSession() async throws -> AccountSession? {
        guard let session else { return nil }
        if session.expiresAt.timeIntervalSinceNow > 90 { return session }
        let epoch = generation
        let refreshed = try await client.token(grant: "refresh_token", payload: ["refresh_token": session.refreshToken])
        try require(generation == epoch && refreshed.userId == session.userId, "Le compte a changé pendant la connexion.")
        try Keychain.write("account-session", data: JSONEncoder().encode(refreshed))
        self.session = refreshed
        return refreshed
    }
    func disconnect() throws {
        browser?.cancel(); browser = nil; connecting = false
        try Keychain.write("account-session", data: nil)
        do { try onChange?(nil) }
        catch { try Keychain.write("account-session", data: session.map { try JSONEncoder().encode($0) }); throw error }
        generation = UUID(); session = nil; status = "Sans compte · données sur ce Mac"
    }
    private func base64(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
    private func nonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        precondition(SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess)
        return base64(Data(bytes))
    }
}
