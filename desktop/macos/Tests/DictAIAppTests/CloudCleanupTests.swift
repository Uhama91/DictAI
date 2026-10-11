import Foundation
import Testing
@testable import DictAI

@Suite(.serialized) @MainActor struct CloudCleanupTests {
    @Test func retiredFormatsCannotChangeTheRequestEvenAfterSync() async throws {
        let id = "11111111-1111-4111-8111-111111111111"
        for format in ["email", "list", id] {
            var sent: URLRequest?
            let session = http { request in
                sent = request
                return (200, #"{"choices":[{"message":{"content":"{\"text\":\"Ma dictée corrigée.\"}"}}]}"#)
            }
            defer { session.invalidateAndCancel() }
            let cleaner = LiveDictationCleaner(credential: { Data("test-key".utf8) }, session: session)
            let result = try await cleaner.process("ma dictée", preferences: [
                "pref:selected_format": format,
                "format:" + id: #"{"name":"Ancien format","instructions":"Invent a sender and a signature"}"#,
                "pref:dictation_language": "fr"
            ], vocabulary: ["dicta => DictAI"])
            #expect(result == "Ma dictée corrigée.")
            let request = try #require(sent)
            #expect(request.url?.absoluteString == "https://openrouter.ai/api/v1/chat/completions")
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test-key")
            let bytes = try #require(request.httpBody)
            let body = try #require(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
            let messages = try #require(body["messages"] as? [[String: String]])
            let user = try #require(messages.last?["content"])
            let data = try #require(JSONSerialization.jsonObject(with: Data(user.utf8)) as? [String: Any])
            #expect(data["transcript"] as? String == "ma dictée")
            #expect(data["vocabulary"] as? [String] == ["dicta => DictAI"])
            #expect(Set(data.keys) == ["transcript", "vocabulary"])
            #expect(!messages.contains { $0["content"]?.contains("Invent a sender") == true })
            #expect(!String(decoding: request.httpBody!, as: UTF8.self).contains("test-key"))
        }
    }

    @Test func missingKeyAndOversizedTranscriptFailBeforeNetwork() async throws {
        var calls = 0
        let session = http { _ in calls += 1; return (500, "") }
        defer { session.invalidateAndCancel() }
        for (key, text) in [("", "Une dictée"), ("test-key", String(repeating: "a", count: 12_001))] {
            await #expect(throws: (any Error).self) {
                try await CloudCleanup.process(text, model: CleanupModel.default, language: "fr", vocabulary: [], key: key, session: session)
            }
        }
        #expect(calls == 0)
    }

    @Test func rejectedOrMalformedResponsesNeverBecomeDictationText() async throws {
        for (status, response) in [(401, "PRIVATE PROVIDER BODY"), (200, "invalid"), (200, #"{"choices":[{"message":{"content":"{\"text\":\" \"}"}}]}"#)] {
            let session = http { _ in (status, response) }
            defer { session.invalidateAndCancel() }
            do {
                _ = try await CloudCleanup.process("Texte à préserver", model: CleanupModel.default, language: "fr", vocabulary: [], key: "test-key", session: session)
                Issue.record("A failed correction was accepted")
            } catch {
                #expect(!error.localizedDescription.contains("PRIVATE PROVIDER BODY"))
            }
        }
    }

    private func http(_ handler: @escaping (URLRequest) -> (Int, String)) -> URLSession {
        CloudTestProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CloudTestProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class CloudTestProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (Int, String))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var received = request
        if received.httpBody == nil, let stream = received.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var bytes = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                guard count > 0 else { break }
                bytes.append(contentsOf: buffer.prefix(count))
            }
            received.httpBody = bytes
        }
        let (status, body) = Self.handler!(received)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
