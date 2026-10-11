import AppKit
import DictAICore
import Foundation

@MainActor final class AppUpdateChecker: ObservableObject {
    enum Status { case idle, checking, complete, unavailable }
    @Published private(set) var offer: AppUpdate?
    @Published private(set) var status = Status.idle
    private let preferences: UserDefaults
    private let currentBuild: Int
    private let systemVersion: String
    private let clock: () -> Date
    private let fetch: () async throws -> Data
    private var timer: Timer?
    private var activation: NSObjectProtocol?

    init(preferences: UserDefaults = .standard,
         currentBuild: Int = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0,
         systemVersion: String = "\(ProcessInfo.processInfo.operatingSystemVersion.majorVersion).\(ProcessInfo.processInfo.operatingSystemVersion.minorVersion).\(ProcessInfo.processInfo.operatingSystemVersion.patchVersion)",
         clock: @escaping () -> Date = Date.init,
         fetch: @escaping () async throws -> Data = AppUpdateChecker.fetchManifest) {
        self.preferences = preferences; self.currentBuild = currentBuild; self.systemVersion = systemVersion
        self.clock = clock; self.fetch = fetch
        if let data = preferences.data(forKey: "appUpdate.manifest") {
            offer = try? AppUpdate.macOS(data, currentBuild: currentBuild, systemVersion: systemVersion)
        }
    }
    var statusText: String {
        switch status {
        case .idle: return "Vérification automatique des nouvelles versions."
        case .checking: return "Vérification en cours…"
        case .complete: return offer == nil ? "Aucune mise à jour compatible disponible." : "Une nouvelle version est disponible."
        case .unavailable: return "Vérification indisponible. Vous pouvez réessayer."
        }
    }
    func start() {
        guard timer == nil else { return }
        Task { await check() }
        timer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in await self?.check() }
        }
        activation = NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in await self?.check() }
        }
    }
    func check(force: Bool = false) async {
        guard status != .checking else { return }
        let last = preferences.object(forKey: "appUpdate.lastAttempt") as? Date
        guard force || AppUpdateSchedule.due(now: clock(), lastAttempt: last, succeeded: preferences.bool(forKey: "appUpdate.succeeded")) else { return }
        preferences.set(clock(), forKey: "appUpdate.lastAttempt")
        preferences.set(false, forKey: "appUpdate.succeeded")
        status = .checking
        do {
            let data = try await fetch()
            let update = try AppUpdate.macOS(data, currentBuild: currentBuild, systemVersion: systemVersion)
            preferences.set(data, forKey: "appUpdate.manifest")
            preferences.set(true, forKey: "appUpdate.succeeded")
            offer = update; status = .complete
        } catch { status = .unavailable }
    }
    @discardableResult func openDownload() -> Bool {
        guard let offer else { return false }
        return NSWorkspace.shared.open(offer.downloadURL)
    }
    private static func fetchManifest() async throws -> Data {
        var request = URLRequest(url: AppUpdate.feedURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForResource = 20
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let (stream, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              response.expectedContentLength <= AppUpdate.maximumBytes else { throw URLError(.badServerResponse) }
        var data = Data()
        for try await byte in stream {
            guard data.count < AppUpdate.maximumBytes else { throw URLError(.dataLengthExceedsMaximum) }
            data.append(byte)
        }
        return data
    }
    deinit {
        timer?.invalidate()
        if let activation { NotificationCenter.default.removeObserver(activation) }
    }
}
