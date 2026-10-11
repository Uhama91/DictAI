import Foundation
import Security
import DictAICore

enum Keychain {
    private static let service = "com.uhama.dictai.desktop"
    static func read(_ key: String) throws -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: key, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        try require(status == errSecSuccess, "Le Trousseau n’a pas pu être ouvert (\(status)).")
        return item as? Data
    }
    static func write(_ key: String, data: Data?) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        guard let data else {
            let status = SecItemDelete(query as CFDictionary)
            try require(status == errSecSuccess || status == errSecItemNotFound, "La déconnexion n’a pas pu être enregistrée.")
            return
        }
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var attributes = query
            attributes[kSecValueData as String] = data
            attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            try require(SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess, "Le secret n’a pas pu être enregistré dans le Trousseau.")
        } else { try require(status == errSecSuccess, "Le Trousseau n’a pas pu être mis à jour.") }
    }
}
