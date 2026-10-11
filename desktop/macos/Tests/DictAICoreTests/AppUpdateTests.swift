import Foundation
import Testing
@testable import DictAICore

struct AppUpdateTests {
    private let url = "https://github.com/Uhama91/DictAI/releases/download/v1/DictAI-macOS.pkg"
    private func feed(build: Int = 17, url: String? = nil, minimum: String = "14.0", id: String = "com.uhama.dictai.desktop") -> Data {
        Data("""
        {"schemaVersion":1,"macos":{"bundleIdentifier":"\(id)","buildNumber":\(build),"versionName":"0.4.7","minSystemVersion":"\(minimum)","downloadUrl":"\(url ?? self.url)"}}
        """.utf8)
    }
    @Test func onlyNewerCompatibleMacBuildOffersDownload() throws {
        #expect(try AppUpdate.macOS(feed(), currentBuild: 16, systemVersion: "14.0")?.downloadURL.absoluteString == url)
        #expect(try AppUpdate.macOS(feed(build: 16), currentBuild: 16, systemVersion: "14.0") == nil)
        #expect(try AppUpdate.macOS(feed(build: 15), currentBuild: 16, systemVersion: "14.0") == nil)
        #expect(try AppUpdate.macOS(feed(minimum: "15.0"), currentBuild: 16, systemVersion: "14.6") == nil)
        #expect(try AppUpdate.macOS(feed(id: "other.app"), currentBuild: 16, systemVersion: "14.0") == nil)
        #expect(try AppUpdate.macOS(feed(minimum: "14.0.0"), currentBuild: 16, systemVersion: "14.0") != nil)
        #expect(try AppUpdate.macOS(Data(#"{"schemaVersion":1,"android":{}}"#.utf8), currentBuild: 16, systemVersion: "14.0") == nil)
    }
    @Test func refusesUntrustedOrMisleadingDownloadURLs() {
        for bad in [url.replacingOccurrences(of: "https:", with: "http:"), url.replacingOccurrences(of: "github.com", with: "evil.example"),
                    url.replacingOccurrences(of: "/Uhama91/", with: "/someone/"), url.replacingOccurrences(of: "github.com", with: "github.com@evil.example"),
                    url + "?redirect=other", url + "#other", url + "\\n", url.replacingOccurrences(of: ".pkg", with: ".apk"),
                    url.replacingOccurrences(of: "/v1/", with: "/../"), url.replacingOccurrences(of: "github.com", with: "github.com:444"),
                    url.replacingOccurrences(of: "/v1/", with: "/%2e%2e/"), url.replacingOccurrences(of: "/v1/", with: "/v1/extra/")] {
            #expect(throws: (any Error).self) { try AppUpdate.macOS(feed(url: bad), currentBuild: 16, systemVersion: "14.0") }
        }
    }
    @Test func rejectsMalformedOversizedAndUnknownFeeds() {
        for bad in [Data("{}".utf8), Data("bad json".utf8), Data(#"{"schemaVersion":2}"#.utf8), Data(repeating: 32, count: 65537),
                    Data(#"{"schemaVersion":1,"testOnly":true}"#.utf8),
                    feed(minimum: "bad"), Data(String(decoding: feed(), as: UTF8.self).replacingOccurrences(of: "0.4.7", with: "bad\\nlabel").utf8)] {
            #expect(throws: (any Error).self) { try AppUpdate.macOS(bad, currentBuild: 16, systemVersion: "14.0") }
        }
    }
    @Test func backgroundChecksBackOffAndRecoverAfterClockChanges() {
        let now = Date(timeIntervalSince1970: 100000)
        #expect(AppUpdateSchedule.due(now: now, lastAttempt: nil, succeeded: false))
        #expect(!AppUpdateSchedule.due(now: now, lastAttempt: now.addingTimeInterval(-1), succeeded: true))
        #expect(AppUpdateSchedule.due(now: now, lastAttempt: now.addingTimeInterval(-86400), succeeded: true))
        #expect(!AppUpdateSchedule.due(now: now, lastAttempt: now.addingTimeInterval(-3599), succeeded: false))
        #expect(AppUpdateSchedule.due(now: now, lastAttempt: now.addingTimeInterval(-3600), succeeded: false))
        #expect(AppUpdateSchedule.due(now: now, lastAttempt: now.addingTimeInterval(1), succeeded: true))
    }
}
