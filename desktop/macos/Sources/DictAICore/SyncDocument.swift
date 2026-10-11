import Foundation

public enum DataError: LocalizedError {
    case invalid(String)
    public var errorDescription: String? { if case let .invalid(message) = self { return message }; return nil }
}
public func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw DataError.invalid(message) }
}
public func isUUID(_ value: String) -> Bool { UUID(uuidString: value)?.uuidString.lowercased() == value }

public struct SyncStamp: Codable, Equatable, Comparable {
    public let counter: Int64
    public let actor: String
    public init(counter: Int64, actor: String) { self.counter = counter; self.actor = actor }
    public static func < (a: Self, b: Self) -> Bool {
        a.counter == b.counter ? a.actor < b.actor : a.counter < b.counter
    }
}
public struct SyncEntry: Codable, Equatable {
    public let value: String?
    public let stamp: SyncStamp
    public init(value: String?, stamp: SyncStamp) { self.value = value; self.stamp = stamp }
    enum CodingKeys: String, CodingKey { case value, stamp }
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try require(container.contains(.value), "Une suppression doit être déclarée explicitement.")
        value = try container.decodeIfPresent(String.self, forKey: .value)
        stamp = try container.decode(SyncStamp.self, forKey: .stamp)
    }
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        if let value { try container.encode(value, forKey: .value) } else { try container.encodeNil(forKey: .value) }
        try container.encode(stamp, forKey: .stamp)
    }
}
public struct SyncDocument: Codable, Equatable {
    public var version = 1
    public var entries: [String: SyncEntry]
    public init(entries: [String: SyncEntry] = [:]) { self.entries = entries }
    public var values: [String: String] { entries.compactMapValues(\.value) }
    public func validate() throws {
        try require(version == 1 && entries.count <= 10_000, "Format de synchronisation non pris en charge.")
        for (key, entry) in entries {
            try require(key.utf16.count <= 256 && !key.unicodeScalars.contains(where: { $0.value < 32 }) &&
                        ["pref:", "vocab:", "format:", "note:", "folder:", "asset:", "deletion:"].contains(where: { key.hasPrefix($0) && key.count > $0.count }), "Clé de synchronisation invalide.")
            try require(isUUID(entry.stamp.actor) && entry.stamp.counter > 0 && entry.stamp.counter < Int64.max, "Horloge de synchronisation invalide.")
            if let value = entry.value {
                let limit = key.hasPrefix("note:") ? 1_048_576 : 8192
                try require(value.utf16.count <= limit && (!key.hasPrefix("note:") || value.utf8.count <= limit), "Contenu trop volumineux.")
            }
        }
        try require(try JSONEncoder().encode(self).count <= 8_388_608, "Bibliothèque trop volumineuse pour la synchronisation.")
    }
    public static func decode(_ data: Data) throws -> Self {
        try require(data.count <= 8_388_608, "Document distant trop volumineux.")
        let result = try JSONDecoder().decode(Self.self, from: data)
        try result.validate()
        return result
    }
    public func merging(_ other: Self) throws -> Self {
        try validate(); try other.validate()
        var result = self
        for (key, incoming) in other.entries {
            if let own = entries[key] {
                try require(own.stamp != incoming.stamp || own.value == incoming.value, "Deux contenus portent la même horloge.")
                if own.stamp < incoming.stamp { result.entries[key] = incoming }
            } else { result.entries[key] = incoming }
        }
        try result.validate()
        return result
    }
    public func recording(_ snapshot: [String: String], actor: String) throws -> Self {
        try require(isUUID(actor), "Identifiant de cet appareil invalide.")
        let changed = Set(entries.keys).union(snapshot.keys).sorted().filter { entries[$0]?.value != snapshot[$0] }
        var counter = entries.values.map(\.stamp.counter).max() ?? 0
        try require(Int64(changed.count) < Int64.max - counter, "Horloge épuisée.")
        var result = self
        for key in changed {
            counter += 1
            result.entries[key] = SyncEntry(value: snapshot[key], stamp: SyncStamp(counter: counter, actor: actor))
        }
        try result.validate()
        return result
    }
    public func setting(_ key: String, value: String?, actor: String) throws -> Self {
        var snapshot = values; snapshot[key] = value
        return try recording(snapshot, actor: actor)
    }
}
