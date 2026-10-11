import Foundation
import Testing
@testable import DictAICore

struct DraftHistoryIdentityTests {
    @Test func restoredDraftRetainsItsHistoryIdentityAcrossRetries() throws {
        var draft = DictationDraft()
        draft.receive("La dictée à récupérer")
        let restored = try JSONDecoder().decode(DictationDraft.self, from: JSONEncoder().encode(draft))
        #expect(restored.historyID == draft.historyID)
        #expect(restored.text == draft.text)
        #expect(DictationDraft().historyID != draft.historyID)
    }
    @Test func draftFromPreviousVersionStillLoadsWithoutHistoryIdentity() throws {
        let legacy = Data("{\"text\":\"Brouillon conservé\",\"recognized\":\"Brouillon conservé\",\"edited\":false}".utf8)
        let restored = try JSONDecoder().decode(DictationDraft.self, from: legacy)
        #expect(restored.text == "Brouillon conservé")
        #expect(!restored.historyID.isEmpty)
    }
}
