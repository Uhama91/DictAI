import Foundation
import Testing
@testable import DictAICore

struct InsertionReceiptTests {
    @Test func webEditorWhitespaceDoesNotTurnASuccessfulInsertionIntoAnError() {
        #expect(InsertionReceipt.check(original: "", expected: "Bonjour le monde. ", actual: "Bonjour le monde.\u{00a0}") == .confirmed)
        #expect(InsertionReceipt.check(original: "", expected: "Bonjour le monde. ", actual: "Bonjour le monde.") == .confirmed)
        #expect(InsertionReceipt.check(original: "", expected: "Bonjour\nla suite. ", actual: "Bonjour\r\nla suite. ") == .confirmed)
        #expect(InsertionReceipt.check(original: "", expected: "Bonjour le monde. ", actual: "Bonjour le") == .changed)
        #expect(InsertionReceipt.check(original: "", expected: "Bonjour\nla suite. ", actual: "Bonjour la suite.") == .changed)
    }
    @Test func acknowledgingAnUnchangedFieldIsNotSuccess() {
        #expect(InsertionReceipt.check(original: "", expected: "Ma dictée", actual: "") == .unchanged)
        #expect(InsertionReceipt.check(original: "", expected: "Ma dictée", actual: "Ma dictée") == .confirmed)
        #expect(InsertionReceipt.check(original: "", expected: "Ma dictée", actual: "Autre saisie") == .changed)
    }
    @Test func replacementUsesMacUTF16SelectionWithoutLosingSurroundingText() {
        #expect(InsertionReceipt.expected(original: "📚 Bonjour !", selection: NSRange(location: 3, length: 7), inserted: "Bonsoir") == "📚 Bonsoir !")
        #expect(InsertionReceipt.expected(original: "abc", selection: NSRange(location: 9, length: 0), inserted: "x") == nil)
        #expect(InsertionReceipt.expected(original: "abc", selection: nil, inserted: "x") == nil)
    }
}
