import AppKit
import Testing
@testable import DictAI

@MainActor struct TextInsertionTests {
    @Test func returningToAnExternalAppSelectsADestinationForARecoveredDraft() async throws {
        let external = try #require(NSWorkspace.shared.runningApplications.first {
            $0.activationPolicy == .regular &&
            $0.processIdentifier != ProcessInfo.processInfo.processIdentifier && $0.localizedName != nil
        })
        let delivery = TextInsertion()
        NSWorkspace.shared.notificationCenter.post(name: NSWorkspace.didActivateApplicationNotification,
            object: nil, userInfo: [NSWorkspace.applicationUserInfoKey: external])
        await Task.yield()
        #expect(delivery.targetName == external.localizedName)
    }
}
