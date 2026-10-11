import Foundation
import Testing
@testable import DictAICore

struct PreferenceContractTests {
    let actor = "11111111-1111-4111-8111-111111111111"
    let format = "22222222-2222-4222-8222-222222222222"
    @Test func formatsOutsideAndroidLimitsAreRejectedBeforePublication() throws {
        let name = String(repeating: "é", count: 61)
        let value = "{\"name\":\(Wire.quote(name)),\"instructions\":\"Corriger\"}"
        let doc = try SyncDocument().setting("format:" + format, value: value, actor: actor)
        #expect(throws: (any Error).self) { try doc.validateContent() }
    }
    @Test func invalidPreferenceCannotPoisonAllAndroidReplicas() throws {
        let doc = try SyncDocument().setting("pref:dictation_language", value: "de", actor: actor)
        #expect(throws: (any Error).self) { try doc.validateContent() }
    }
    @Test func vocabularyHashMustIdentifyTheActualSource() throws {
        let doc = try SyncDocument().setting(Vocabulary.key("chat => chien"), value: "école => DictAI", actor: actor)
        #expect(throws: (any Error).self) { try doc.validateContent() }
    }
    @Test func unknownPreferenceIncludingTombstoneCannotBePublished() throws {
        let entry = SyncEntry(value: nil, stamp: SyncStamp(counter: 1, actor: actor))
        let doc = SyncDocument(entries: ["pref:api_key": entry])
        #expect(throws: (any Error).self) { try doc.validateContent() }
    }
    @Test func missingValueFieldIsNotAnImplicitDeletion() throws {
        let raw = #"{"version":1,"entries":{"pref:dictation_language":{"stamp":{"counter":1,"actor":"11111111-1111-4111-8111-111111111111"}}}}"#
        #expect(throws: (any Error).self) { try SyncDocument.decode(Data(raw.utf8)) }
    }
}
