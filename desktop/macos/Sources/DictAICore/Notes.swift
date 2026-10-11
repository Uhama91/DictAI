import Foundation
import CryptoKit

public enum Wire {
    public static func quote(_ text: String) -> String {
        var result = "\""
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 34: result += "\\\""
            case 92: result += "\\\\"
            case 8: result += "\\b"
            case 12: result += "\\f"
            case 10: result += "\\n"
            case 13: result += "\\r"
            case 9: result += "\\t"
            case 0..<32: result += String(format: "\\u%04x", scalar.value)
            default: result.unicodeScalars.append(scalar)
            }
        }
        return result + "\""
    }
    public static func seen(_ seen: [String: Int64]) -> String {
        "{" + seen.keys.sorted().map { quote($0) + ":" + String(seen[$0]!) }.joined(separator: ",") + "}"
    }
    public static func acknowledge(_ history: inout [String: Int64], _ stamp: SyncStamp) {
        history[stamp.actor] = max(history[stamp.actor] ?? 0, stamp.counter)
    }
    public static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

public struct NoteImage: Codable, Equatable, Identifiable {
    public var id: String
    public var number: Int
    public var kind: String
    public var capturedAt: Int64
    public var width: Int
    public var height: Int
    public var canonical: String {
        "{\"id\":\(Wire.quote(id)),\"number\":\(number),\"kind\":\(Wire.quote(kind)),\"capturedAt\":\(capturedAt),\"width\":\(width),\"height\":\(height)}"
    }
}

public struct Note: Codable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var text: String
    public var updated: Int64
    public var renamed: Bool
    public var images: [NoteImage]
    public var folderId: String?
    public var folderChoicePrompted: Bool
    public var _seen: [String: Int64]
    public init(id: String = UUID().uuidString.lowercased(), title: String = "Nouvelle note", text: String = "", updated: Int64 = Wire.now, folderId: String? = nil) {
        self.id = id; self.title = title; self.text = text; self.updated = updated; self.folderId = folderId
        renamed = false; images = []; folderChoicePrompted = true; _seen = [:]
    }
    public var payload: String {
        "{\"id\":\(Wire.quote(id)),\"title\":\(Wire.quote(title)),\"text\":\(Wire.quote(text)),\"updated\":\(updated),\"renamed\":\(renamed),\"images\":[\(images.map(\.canonical).joined(separator: ","))],\"folderId\":\(folderId.map(Wire.quote) ?? "null"),\"folderChoicePrompted\":\(folderChoicePrompted)}"
    }
    public var canonical: String { String(payload.dropLast()) + ",\"_seen\":" + Wire.seen(_seen) + "}" }
    public static func decode(_ raw: String) throws -> Self {
        try require(raw.utf8.count <= 1_048_576, "Note trop volumineuse.")
        let note = try JSONDecoder().decode(Self.self, from: Data(raw.utf8))
        try require(isUUID(note.id) && !note.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && note.title.utf16.count <= 80 && note.updated >= 0 && (note.folderId == nil || isUUID(note.folderId!)), "Métadonnées de note invalides.")
        try require(note._seen.count <= 100 && note._seen.allSatisfy { isUUID($0.key) && $0.value > 0 && $0.value < Int64.max }, "Historique de note invalide.")
        try require(note.images.count <= 10 && Set(note.images.map(\.id)).count == note.images.count && Set(note.images.map(\.number)).count == note.images.count, "Images de note invalides.")
        for image in note.images {
            try require(isUUID(image.id) && image.number > 0 && ["CAMERA", "SCREENSHOT", "SCAN"].contains(image.kind) && (1...2048).contains(image.width) && (1...2048).contains(image.height) && image.capturedAt >= 0, "Métadonnées d’image invalides.")
        }
        try require(note.canonical == raw, "Encodage de note incompatible avec Android.")
        return note
    }
    public func conflictCopy(stamp: SyncStamp) -> Self {
        // Java UUID.nameUUIDFromBytes uses MD5 and sets RFC 4122 version 3 bits.
        var bytes = Array(Insecure.MD5.hash(data: Data((id + "\n" + Vocabulary.hash(payload)).utf8)))
        bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        var copy = self
        let chars = Array(hex)
        copy.id = [String(chars[0..<8]), String(chars[8..<12]), String(chars[12..<16]), String(chars[16..<20]), String(chars[20..<32])].joined(separator: "-")
        let suffix = " (version conservée)"
        copy.title = TextLimit.trimEnd(TextLimit.prefix(title, utf16: 80 - suffix.utf16.count)) + suffix
        copy.renamed = true
        Wire.acknowledge(&copy._seen, stamp)
        return copy
    }
}

public struct Folder: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var created: Int64
    public var updated: Int64
    public init(id: String = UUID().uuidString.lowercased(), name: String, created: Int64 = Wire.now) {
        self.id = id; self.name = name; self.created = created; updated = created
    }
    public var canonical: String { "{\"id\":\(Wire.quote(id)),\"name\":\(Wire.quote(name)),\"created\":\(created),\"updated\":\(updated)}" }
    public static func decode(_ raw: String) throws -> Self {
        let folder = try JSONDecoder().decode(Self.self, from: Data(raw.utf8))
        try require(isUUID(folder.id) && !folder.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && folder.name.utf16.count <= 80 && folder.created >= 0 && folder.updated >= 0 && folder.canonical == raw, "Dossier invalide.")
        return folder
    }
}

public struct Asset: Codable, Equatable {
    public var sha256: String
    public var size: Int
    public var canonical: String { "{\"sha256\":\(Wire.quote(sha256)),\"size\":\(size)}" }
    public static func decode(_ raw: String) throws -> Self {
        let asset = try JSONDecoder().decode(Self.self, from: Data(raw.utf8))
        try require(asset.sha256.count == 64 && asset.sha256.allSatisfy { "0123456789abcdef".contains($0) } && (1...8_388_608).contains(asset.size) && asset.canonical == raw, "Descripteur d’image invalide.")
        return asset
    }
}

public struct NoteDeletion: Codable {
    public var baseStamp: SyncStamp
    public var _seen: [String: Int64]
    public var canonical: String {
        "{\"baseStamp\":{\"counter\":\(baseStamp.counter),\"actor\":\(Wire.quote(baseStamp.actor))},\"_seen\":\(Wire.seen(_seen))}"
    }
    public static func decode(_ raw: String) throws -> Self {
        let deletion = try JSONDecoder().decode(Self.self, from: Data(raw.utf8))
        try require(deletion.canonical == raw && isUUID(deletion.baseStamp.actor) && deletion.baseStamp.counter > 0 && (deletion._seen[deletion.baseStamp.actor] ?? 0) >= deletion.baseStamp.counter && deletion._seen.count <= 100 && deletion._seen.allSatisfy { isUUID($0.key) && $0.value > 0 }, "Reçu de suppression invalide.")
        return deletion
    }
}

extension SyncDocument {
    public func notes() throws -> [Note] { try values.filter { $0.key.hasPrefix("note:") }.map { try Note.decode($0.value) }.sorted { $0.updated > $1.updated } }
    public func folders() throws -> [Folder] { try values.filter { $0.key.hasPrefix("folder:") }.map { try Folder.decode($0.value) }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending } }
    public func saving(_ note: Note, actor: String) throws -> Self {
        var value = note
        if let previous = entries["note:" + note.id] {
            if let raw = previous.value {
                let old = try Note.decode(raw)
                for (device, count) in old._seen { value._seen[device] = max(value._seen[device] ?? 0, count) }
            }
            Wire.acknowledge(&value._seen, previous.stamp)
        }
        _ = try Note.decode(value.canonical)
        return try setting("note:" + value.id, value: value.canonical, actor: actor)
    }
    public func deletingNote(_ id: String, actor: String) throws -> Self {
        guard let previous = entries["note:" + id], let raw = previous.value else { return self }
        var seen = try Note.decode(raw)._seen
        Wire.acknowledge(&seen, previous.stamp)
        var result = try setting("note:" + id, value: nil, actor: actor)
        result.entries["deletion:" + id] = SyncEntry(value: NoteDeletion(baseStamp: previous.stamp, _seen: seen).canonical, stamp: result.entries["note:" + id]!.stamp)
        return result
    }
    public func validateContent() throws {
        try validate()
        for (key, entry) in entries {
            let kind = String(key.prefix(while: { $0 != ":" }))
            guard ["note", "folder", "asset", "deletion"].contains(kind) else {
                try PreferenceContract.validate(key: key, value: entry.value)
                continue
            }
            let id = String(key.dropFirst(kind.count + 1))
            try require(isUUID(id), "Identifiant de contenu invalide.")
            switch (kind, entry.value) {
            case ("note", .some(let raw)):
                let note = try Note.decode(raw)
                try require(note.id == id && note._seen.values.allSatisfy { $0 < entry.stamp.counter }, "Historique causal invalide.")
                for image in note.images {
                    guard let descriptor = values["asset:" + image.id] else { throw DataError.invalid("Descripteur d’image manquant.") }
                    _ = try Asset.decode(descriptor)
                }
            case ("note", .none):
                try require(entries["deletion:" + id]?.stamp == entry.stamp && entries["deletion:" + id]?.value != nil, "Suppression sans reçu causal.")
            case ("folder", .some(let raw)): try require(try Folder.decode(raw).id == id, "Identifiant de dossier incohérent.")
            case ("asset", .some(let raw)): _ = try Asset.decode(raw)
            case ("deletion", .some(let raw)):
                let receipt = try NoteDeletion.decode(raw)
                guard let note = entries["note:" + id] else { throw DataError.invalid("Suppression sans note.") }
                try require(receipt._seen.values.allSatisfy { $0 < entry.stamp.counter } && entry.stamp <= note.stamp && (note.value != nil || note.stamp == entry.stamp), "Suppression incohérente.")
            case ("deletion", .none): throw DataError.invalid("Le reçu de suppression doit être conservé.")
            default: break
            }
        }
    }
}
