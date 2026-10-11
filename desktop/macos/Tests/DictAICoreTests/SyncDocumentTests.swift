import Testing
@testable import DictAICore

struct SyncDocumentTests {
    let a = "11111111-1111-4111-8111-111111111111"
    let b = "22222222-2222-4222-8222-222222222222"
    @Test func testIndependentOfflineEditsSurviveMerge() throws {
        let left = try SyncDocument().recording(["pref:dictation_language": "fr"], actor: a)
        let right = try SyncDocument().recording(["pref:trailing_space": "true"], actor: b)
        let merged = try left.merging(right)
        #expect((merged.entries.count) == (2))
        #expect((merged) == (try right.merging(left)))
    }
    @Test func testDeletionSurvivesStaleReplica() throws {
        let original = try SyncDocument().recording(["pref:dictation_language": "fr"], actor: a)
        let deleted = try original.recording([:], actor: a)
        let merged = try deleted.merging(original)
        #expect((merged.entries["pref:dictation_language"]) != nil)
        #expect((merged.entries["pref:dictation_language"]?.value) == nil)
        #expect((merged.entries["pref:dictation_language"]?.stamp.counter) == (2))
    }
    @Test func testEqualClockWithDifferentBytesIsRejected() throws {
        let stamp = SyncStamp(counter: 1, actor: a)
        let local = SyncDocument(entries: ["pref:dictation_language": SyncEntry(value: "fr", stamp: stamp)])
        let remote = SyncDocument(entries: ["pref:dictation_language": SyncEntry(value: "en", stamp: stamp)])
        #expect(throws: (any Error).self) { try local.merging(remote) }
    }
    @Test func testInvalidKeyAndActorAreRejected() {
        #expect(throws: (any Error).self) { try SyncDocument().recording(["secret:token": "never"], actor: a) }
        #expect(throws: (any Error).self) { try SyncDocument().recording(["pref:dictation_language": "fr"], actor: "device") }
    }
    @Test func testClockAdvancesPastReceivedEventsAndNoOpDoesNotTick() throws {
        let received = SyncDocument(entries: ["pref:dictation_language": SyncEntry(value: "fr", stamp: SyncStamp(counter: 42, actor: b))])
        let changed = try received.recording(["pref:dictation_language": "en"], actor: a)
        #expect((changed.entries["pref:dictation_language"]?.stamp.counter) == (43))
        #expect((changed) == (try changed.recording(["pref:dictation_language": "en"], actor: a)))
    }
}
