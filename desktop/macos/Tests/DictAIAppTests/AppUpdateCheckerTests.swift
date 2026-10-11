import Foundation
import Testing
@testable import DictAI

@MainActor struct AppUpdateCheckerTests {
    private let feed = Data(#"{"schemaVersion":1,"macos":{"bundleIdentifier":"com.uhama.dictai.desktop","buildNumber":100,"versionName":"1.0","minSystemVersion":"14.0","downloadUrl":"https://github.com/Uhama91/DictAI/releases/download/v1/DictAI-macOS.pkg"}}"#.utf8)
    @Test func cachesOffersThrottlesAndDiscardsInstalledUpdates() async throws {
        let preferences = try #require(UserDefaults(suiteName: "dictai-update-test-" + UUID().uuidString))
        var requests = 0
        var now = Date(timeIntervalSince1970: 100000)
        let checker = AppUpdateChecker(preferences: preferences, currentBuild: 16, systemVersion: "14.0", clock: { now }, fetch: { requests += 1; return self.feed })
        await checker.check()
        #expect(checker.offer?.version == "1.0")
        await checker.check()
        #expect(requests == 1)
        await checker.check(force: true)
        #expect(requests == 2)
        let restored = AppUpdateChecker(preferences: preferences, currentBuild: 16, systemVersion: "14.0")
        #expect(restored.offer?.version == "1.0")
        let updated = AppUpdateChecker(preferences: preferences, currentBuild: 100, systemVersion: "14.0")
        #expect(updated.offer == nil)
        now = now.addingTimeInterval(86400)
        await checker.check()
        #expect(requests == 3)
    }
    @Test func failureRemainsVisibleAndKeepsLastKnownOffer() async throws {
        let preferences = try #require(UserDefaults(suiteName: "dictai-update-error-" + UUID().uuidString))
        var fail = false
        let checker = AppUpdateChecker(preferences: preferences, currentBuild: 16, systemVersion: "14.0", fetch: {
            if fail { throw URLError(.notConnectedToInternet) }
            return self.feed
        })
        await checker.check(force: true)
        fail = true
        await checker.check(force: true)
        #expect(checker.status == .unavailable)
        #expect(checker.offer?.version == "1.0")
    }
}
