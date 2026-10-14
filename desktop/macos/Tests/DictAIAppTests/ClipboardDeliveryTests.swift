import AppKit
import Testing
@testable import DictAI

@MainActor struct ClipboardDeliveryTests {
    @Test func nativePasteActionConsumesTheTextExactlyOnceAndRestoresTheClipboard() async throws {
        let board = NSPasteboard(name: .init("dictai-paste-test-" + UUID().uuidString))
        board.setString("Previous clipboard", forType: .string)
        let delivery = ClipboardDelivery(board: board)
        var actions = 0
        var received: [String] = []
        try await delivery.paste("Single insertion", performPaste: {
            actions += 1
            received.append(board.string(forType: .string) ?? "")
        }, stillFocused: { true })
        #expect(actions == 1 && received == ["Single insertion"])
        #expect(board.string(forType: .string) == "Previous clipboard")
    }
    @Test func aChangedTargetNeverInvokesTheNativePasteAction() async {
        let board = NSPasteboard(name: .init("dictai-paste-test-" + UUID().uuidString))
        board.setString("Previous clipboard", forType: .string)
        let delivery = ClipboardDelivery(board: board)
        var actions = 0
        await #expect(throws: (any Error).self) {
            try await delivery.paste("Wrong destination", performPaste: { actions += 1 }, stillFocused: { false })
        }
        #expect(actions == 0 && board.string(forType: .string) == "Previous clipboard")
    }
    @Test func aFailedNativeActionIsNotRetriedWithAnotherPaste() async {
        let board = NSPasteboard(name: .init("dictai-paste-test-" + UUID().uuidString))
        board.setString("Previous clipboard", forType: .string)
        let delivery = ClipboardDelivery(board: board)
        var actions = 0
        await #expect(throws: (any Error).self) {
            try await delivery.paste("Single attempt", performPaste: {
                actions += 1
                throw NSError(domain: "dictai-test", code: 1)
            }, stillFocused: { true })
        }
        #expect(actions == 1 && board.string(forType: .string) == "Previous clipboard")
    }
}
