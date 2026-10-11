import Testing
import DictAICore
@testable import DictAI

@MainActor struct InsertionDeliveryTests {
    @Test func consumedPasteCompletesEvenWhenTheEditorsAccessibilityValueIsStale() async throws {
        for afterPaste in [InsertionReceipt.Result.unchanged, .unreadable, .changed] {
            var field = ""
            var pastes = 0
            let result = try await InsertionDelivery.send(canVerify: true, select: {}, read: {
                field.isEmpty ? .unchanged : afterPaste
            }, paste: {
                field += "Ma dictée corrigée."
                pastes += 1
            })
            #expect(field == "Ma dictée corrigée.")
            #expect(pastes == 1)
            #expect(result == "Texte transmis · copie conservée dans les notes")
        }
    }
    @Test func confirmedDirectInsertionNeverPastesTwice() async throws {
        var field = ""
        var pastes = 0
        _ = try await InsertionDelivery.send(canVerify: true, select: { field = "Ma dictée." }, read: {
            InsertionReceipt.check(original: "", expected: "Ma dictée.", actual: field)
        }, paste: { pastes += 1; field += "Ma dictée." })
        #expect(field == "Ma dictée.")
        #expect(pastes == 0)
    }
    @Test func aPartialDirectInsertionDoesNotTriggerAnotherPaste() async {
        var field = ""
        var pastes = 0
        await #expect(throws: (any Error).self) {
            try await InsertionDelivery.send(canVerify: true, select: { field = "Ma" }, read: {
                InsertionReceipt.check(original: "", expected: "Ma dictée.", actual: field)
            }, paste: { pastes += 1 })
        }
        #expect(field == "Ma" && pastes == 0)
    }
    @Test func unconsumedPasteRemainsAFailure() async {
        await #expect(throws: (any Error).self) {
            try await InsertionDelivery.send(canVerify: true, select: {}, read: { .unchanged }, paste: {
                throw DataError.invalid("Le collage n’a pas été reçu.")
            })
        }
    }
    @Test func unreadableEditorsUseOnlyThePastePath() async throws {
        var field = ""
        _ = try await InsertionDelivery.send(canVerify: false, select: { field = "wrong" }, read: { .unreadable }, paste: {
            field += "Ma dictée."
        })
        #expect(field == "Ma dictée.")
    }
}
