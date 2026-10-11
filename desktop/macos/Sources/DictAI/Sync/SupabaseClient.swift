import Foundation
import DictAICore
import CryptoKit

struct AccountSession: Codable {
    let userId: String
    let email: String
    let accessToken: String
    let refreshToken: String
    let expiresAt: Date
}
struct SyncHTTPError: LocalizedError {
    let code: Int
    var errorDescription: String? { code == 401 ? "La session a expiré. Reconnectez-vous." : "Synchronisation indisponible (HTTP \(code))." }
}

final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

final class SupabaseClient {
    static let base = URL(string: "https://elircmwjepvyxbaczmuz.supabase.co")!
    static let publicKey = "sb_publishable_EIpRM6JvK1_nbhVqQE2vvQ_X-_CkE4w"
    private let http: URLSession
    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 120
        http = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    func request(_ path: String, query: [URLQueryItem] = [], token: String? = nil, method: String = "GET", body: Data? = nil,
                 headers: [String: String] = [:], limit: Int = 8_400_000) async throws -> (Data, HTTPURLResponse) {
        var url = URLComponents(url: Self.base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { url.queryItems = query }
        var request = URLRequest(url: url.url!)
        request.httpMethod = method; request.httpBody = body
        request.setValue(Self.publicKey, forHTTPHeaderField: "apikey")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (bytes, response) = try await http.bytes(for: request)
        guard let response = response as? HTTPURLResponse else { throw DataError.invalid("Réponse réseau invalide.") }
        guard (200..<300).contains(response.statusCode) else { throw SyncHTTPError(code: response.statusCode) }
        try require(response.expectedContentLength <= limit, "Réponse distante trop volumineuse.")
        var data = Data()
        for try await byte in bytes {
            try require(data.count < limit, "Réponse distante trop volumineuse.")
            data.append(byte)
        }
        return (data, response)
    }
    func token(grant: String, payload: [String: String]) async throws -> AccountSession {
        let (data, _) = try await request("auth/v1/token", query: [.init(name: "grant_type", value: grant)], method: "POST", body: JSONEncoder().encode(payload), limit: 65536)
        struct Response: Decodable {
            struct User: Decodable { let id: String; let email: String }
            let access_token: String; let refresh_token: String; let expires_in: Int; let user: User
        }
        let result = try JSONDecoder().decode(Response.self, from: data)
        try require(isUUID(result.user.id) && (1...604800).contains(result.expires_in) && !result.access_token.isEmpty && !result.refresh_token.isEmpty, "Session reçue invalide.")
        return AccountSession(userId: result.user.id, email: result.user.email, accessToken: result.access_token,
                              refreshToken: result.refresh_token, expiresAt: Date().addingTimeInterval(Double(result.expires_in)))
    }
    func replicas(_ account: AccountSession) async throws -> [SyncDocument] {
        struct Row: Decodable { let device_id: String; let document: SyncDocument }
        var documents: [SyncDocument] = []; var actors = Set<String>(); var total: Int?; var size = 0
        repeat {
            let offset = documents.count
            let (data, response) = try await request("rest/v1/dictai_preference_replicas", query: [
                .init(name: "select", value: "device_id,document"), .init(name: "user_id", value: "eq." + account.userId), .init(name: "order", value: "device_id.asc")
            ], token: account.accessToken, headers: ["Prefer": "count=exact", "Range-Unit": "items", "Range": "\(offset)-\(offset)"])
            size += data.count
            try require(size <= 33_554_432, "Compte trop volumineux.")
            let rows = try JSONDecoder().decode([Row].self, from: data)
            let range = response.value(forHTTPHeaderField: "Content-Range") ?? ""
            if offset == 0 && rows.isEmpty && range == "*/0" { return [] }
            let parts = range.split(separator: "/")
            guard parts.count == 2, let count = Int(parts[1]) else { throw DataError.invalid("La liste des appareils est incomplète.") }
            try require(count > 0 && count <= 100 && (total == nil || total == count) && rows.count == 1 && parts[0] == "\(offset)-\(offset)", "La liste des appareils a changé. Réessayez.")
            total = count
            for row in rows {
                try require(isUUID(row.device_id) && actors.insert(row.device_id).inserted, "Identifiant distant invalide.")
                try row.document.validateContent(); documents.append(row.document)
            }
        } while documents.count < total!
        return documents
    }
    func publish(_ document: SyncDocument, actor: String, account: AccountSession) async throws {
        try document.validateContent()
        struct Body: Encodable { let user_id: String; let device_id: String; let document: SyncDocument }
        _ = try await request("rest/v1/dictai_preference_replicas", query: [.init(name: "on_conflict", value: "user_id,device_id")], token: account.accessToken,
            method: "POST", body: JSONEncoder().encode(Body(user_id: account.userId, device_id: actor, document: document)), headers: ["Prefer": "resolution=merge-duplicates,return=minimal"])
    }
    func download(_ asset: Asset, account: AccountSession) async throws -> Data {
        let (data, _) = try await request("storage/v1/object/authenticated/dictai-note-images/\(account.userId)/\(asset.sha256).jpg", token: account.accessToken, limit: 8_388_608)
        try verify(data, asset: asset)
        return data
    }
    func upload(_ asset: Asset, data: Data, account: AccountSession) async throws {
        try verify(data, asset: asset)
        do {
            _ = try await request("storage/v1/object/dictai-note-images/\(account.userId)/\(asset.sha256).jpg", token: account.accessToken,
                                  method: "POST", body: data, headers: ["Content-Type": "image/jpeg", "x-upsert": "false"])
        } catch let error as SyncHTTPError where error.code == 400 || error.code == 409 {
            _ = try await download(asset, account: account)
        }
    }
    func verify(_ data: Data, asset: Asset) throws {
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        try require(data.count == asset.size && hash == asset.sha256, "L’image reçue ne correspond pas à son empreinte.")
    }
}
