import Foundation

public enum PreferenceContract {
    private static let values: [String: Set<String>] = [
        "dictation_language": ["fr", "en"], "number_style": ["DIGITS", "WORDS", "UNCHANGED"],
        "theme_mode": ["system", "light", "dark"], "formatting_engine": ["off", "cloud"],
        "cloud_cleanup_model": ["mistral-small-3-2", "gpt-5-4-nano", "gemini-3-1-flash-lite", "deepseek-v4-flash-0731", "qwen3-5-flash-02-23"],
        "trailing_space": ["true", "false"], "show_transcript": ["true", "false"], "light_text_cleanup": ["true", "false"]
    ]
    public static func validate(key: String, value: String?) throws {
        if key.hasPrefix("pref:") {
            let name = String(key.dropFirst(5))
            if name == "selected_format" {
                if let value { try require(["cleanup", "corrected", "list", "email"].contains(value) || isUUID(value), "Format sélectionné invalide.") }
            } else {
                guard let allowed = values[name] else { throw DataError.invalid("Préférence non prise en charge par Android.") }
                if let value { try require(allowed.contains(value), "Valeur de préférence incompatible avec Android.") }
            }
        } else if key.hasPrefix("vocab:") {
            let pattern = #"^vocab:([0-9a-f]{64})(?::duplicate:([0-9a-f]{64}):(0|[1-9][0-9]{0,3}))?$"#
            let regex = try NSRegularExpression(pattern: pattern)
            guard let match = regex.firstMatch(in: key, range: NSRange(key.startIndex..., in: key)) else { throw DataError.invalid("Identifiant de vocabulaire invalide.") }
            if let value {
                try require(!value.isEmpty && value == value.trimmingCharacters(in: .whitespacesAndNewlines) && !value.contains("\n") && !value.contains("\r"), "Entrée de vocabulaire invalide.")
                let hash = (key as NSString).substring(with: match.range(at: 1))
                try require(Vocabulary.key(value) == "vocab:" + hash, "Le vocabulaire ne correspond pas à son identifiant.")
                if match.range(at: 2).location != NSNotFound {
                    try require((key as NSString).substring(with: match.range(at: 2)) == Vocabulary.hash(value), "Doublon de vocabulaire incohérent.")
                }
            }
        } else if key.hasPrefix("format:") {
            try require(isUUID(String(key.dropFirst(7))), "Identifiant de format invalide.")
            if let value {
                let item = try JSONDecoder().decode([String: String].self, from: Data(value.utf8))
                guard Set(item.keys) == Set(["name", "instructions"]), let name = item["name"], let instructions = item["instructions"] else { throw DataError.invalid("Format personnel invalide.") }
                try require(!name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name.utf16.count <= 60, "Le nom du format doit contenir entre 1 et 60 caractères UTF-16.")
                try require(!instructions.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && instructions.utf16.count <= 4000, "Les consignes du format doivent contenir entre 1 et 4 000 caractères UTF-16.")
                try require(value == "{\"name\":\(Wire.quote(name)),\"instructions\":\(Wire.quote(instructions))}", "Encodage de format incompatible avec Android.")
            }
        } else { throw DataError.invalid("Clé de synchronisation inconnue.") }
    }
}

public enum TextLimit {
    /// Mirrors Kotlin Char.isWhitespace / trimEnd, preserving leading spaces.
    public static func trimEnd(_ text: String) -> String {
        var result = text
        while let scalar = result.unicodeScalars.last {
            let category = scalar.properties.generalCategory
            guard category == .spaceSeparator || category == .lineSeparator || category == .paragraphSeparator || (9...13).contains(scalar.value) || (28...31).contains(scalar.value) else { break }
            result.unicodeScalars.removeLast()
        }
        return result
    }
    /// Android limits are UTF-16 code units, not Swift extended grapheme clusters.
    public static func prefix(_ text: String, utf16 limit: Int) -> String {
        var result = ""; var count = 0
        for scalar in text.unicodeScalars {
            let size = scalar.value > 0xffff ? 2 : 1
            if count + size > limit { break }
            result.unicodeScalars.append(scalar); count += size
        }
        return result
    }
}
