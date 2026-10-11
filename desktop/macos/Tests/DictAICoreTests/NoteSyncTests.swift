import Testing
@testable import DictAICore

struct NoteSyncTests {
    let a = "11111111-1111-4111-8111-111111111111"
    let b = "22222222-2222-4222-8222-222222222222"
    let id = "33333333-3333-4333-8333-333333333333"
    @Test func testCanonicalNoteBytesMatchAndroid() throws {
        let raw = #"{"id":"33333333-3333-4333-8333-333333333333","title":"École","text":"Une ligne\n/ et 🐈","updated":12,"renamed":false,"images":[],"folderId":null,"folderChoicePrompted":true,"_seen":{}}"#
        let note = try Note.decode(raw)
        #expect((note.canonical) == (raw))
        #expect((note.text) == ("Une ligne\n/ et 🐈"))
    }
    @Test func unicodeConflictTitleStaysWithinAndroidLimit() throws {
        let base = try SyncDocument().saving(Note(id: id, title: String(repeating: "🐈", count: 40), text: "Base", updated: 1), actor: a)
        var one = try Note.decode(base.values["note:" + id]!)
        one.text = "Mac"
        var two = one; two.text = "Téléphone"
        let merged = try NoteMerge.merge(local: base.saving(one, actor: a), remote: [base.saving(two, actor: b)], actor: a)
        try merged.validateContent()
        #expect(try merged.notes().count == 2)
        #expect(try merged.notes().allSatisfy { $0.title.utf16.count <= 80 })
    }
    @Test func conflictCopyPreservesLeadingSpacesLikeAndroid() throws {
        let note = Note(id: id, title: "  Réunion  ", text: "Texte", updated: 1)
        let copy = note.conflictCopy(stamp: SyncStamp(counter: 1, actor: a))
        #expect(copy.title == "  Réunion (version conservée)")
    }
    @Test func testConcurrentNoteEditsKeepVisibleCopyAndConverge() throws {
        let base = try SyncDocument().saving(Note(id: id, title: "Note", text: "Base", updated: 1), actor: a)
        var one = try Note.decode(base.entries["note:\(id)"]!.value!)
        one.text = "Téléphone"
        var two = one; two.text = "Mac"
        let left = try base.saving(one, actor: a)
        let right = try base.saving(two, actor: b)
        let merged = try NoteMerge.merge(local: left, remote: [right], actor: a)
        let texts = try merged.notes().map(\.text)
        #expect((Set(texts)) == (Set(["Téléphone", "Mac"])))
        let again = try NoteMerge.merge(local: merged, remote: [left, right], actor: a)
        #expect((try again.notes().count) == (2))
    }
    @Test func testSequentialEditDoesNotCreateConflictCopy() throws {
        let base = try SyncDocument().saving(Note(id: id, title: "Note", text: "Base", updated: 1), actor: a)
        var note = try Note.decode(base.entries["note:\(id)"]!.value!); note.text = "Suite"
        let newer = try base.saving(note, actor: b)
        let merged = try NoteMerge.merge(local: base, remote: [newer], actor: a)
        #expect((try merged.notes().map(\.text)) == (["Suite"]))
    }
    @Test func testDeleteVersusConcurrentEditKeepsOriginalDeletedAndCopyVisible() throws {
        let base = try SyncDocument().saving(Note(id: id, title: "Note", text: "Base", updated: 1), actor: a)
        let deleted = try base.deletingNote(id, actor: a)
        var note = try Note.decode(base.entries["note:\(id)"]!.value!); note.text = "Version hors ligne"
        var edited = base
        edited = try edited.setting("pref:trailing_space", value: "true", actor: b)
        edited = try edited.saving(note, actor: b)
        let merged = try NoteMerge.merge(local: deleted, remote: [edited], actor: a)
        #expect((merged.entries["note:\(id)"]?.value) == nil)
        #expect((merged.entries["deletion:\(id)"]?.value) != nil)
        #expect((try merged.notes().map(\.text)) == (["Version hors ligne"]))
    }
}
