import Foundation

public struct AppUpdate: Equatable, Sendable {
    public let version: String
    public let downloadURL: URL
    public static let feedURL = URL(string: "https://github.com/Uhama91/DictAI/releases/latest/download/updates.json")!
    public static let maximumBytes = 65_536

    public static func macOS(_ data: Data, currentBuild: Int, systemVersion: String) throws -> AppUpdate? {
        try require(data.count <= maximumBytes, "Manifeste de mise à jour trop volumineux.")
        let feed = try JSONDecoder().decode(Feed.self, from: data)
        try require(feed.schemaVersion == 1, "Format de mise à jour inconnu.")
        try require(feed.testOnly != true, "Version de test exclue du canal public.")
        guard let item = feed.macos else { return nil }
        try require(item.buildNumber > 0 && item.buildNumber <= Int(Int32.max), "Numéro de version invalide.")
        try require(!item.versionName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && item.versionName.count <= 40 && !item.versionName.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains), "Nom de version invalide.")
        let pattern = #"^https://github\.com/Uhama91/DictAI/releases/download/[A-Za-z0-9][A-Za-z0-9._-]{0,127}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.pkg\z"#
        try require(item.downloadUrl.range(of: pattern, options: .regularExpression) != nil, "Adresse de téléchargement invalide.")
        guard let minimum = systemComponents(item.minSystemVersion), let current = systemComponents(systemVersion) else { throw DataError.invalid("Version de macOS invalide.") }
        guard item.bundleIdentifier == "com.uhama.dictai.desktop", item.buildNumber > currentBuild,
              !current.lexicographicallyPrecedes(minimum) else { return nil }
        guard let url = URL(string: item.downloadUrl) else { throw DataError.invalid("Adresse de téléchargement invalide.") }
        return AppUpdate(version: item.versionName, downloadURL: url)
    }

    private static func systemComponents(_ value: String) -> [Int]? {
        guard value.range(of: #"^[0-9]{1,2}\.[0-9]{1,2}(\.[0-9]{1,2})?\z"#, options: .regularExpression) != nil else { return nil }
        let parts = value.split(separator: ".").compactMap { Int($0) }
        return parts + Array(repeating: 0, count: 3 - parts.count)
    }
    private struct Feed: Decodable { let schemaVersion: Int; let testOnly: Bool?; let macos: MacRelease? }
    private struct MacRelease: Decodable {
        let bundleIdentifier: String
        let buildNumber: Int
        let versionName: String
        let minSystemVersion: String
        let downloadUrl: String
    }
}

public enum AppUpdateSchedule {
    public static func due(now: Date, lastAttempt: Date?, succeeded: Bool) -> Bool {
        guard let lastAttempt else { return true }
        let elapsed = now.timeIntervalSince(lastAttempt)
        return elapsed < 0 || elapsed >= (succeeded ? 86_400 : 3_600)
    }
}
