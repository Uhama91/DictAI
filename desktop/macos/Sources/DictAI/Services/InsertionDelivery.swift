import DictAICore

/// Separates delivery from the accessibility readback, which rich editors may invalidate.
@MainActor enum InsertionDelivery {
    static func send(canVerify: Bool, select: () -> Void,
                     read: () async throws -> InsertionReceipt.Result,
                     paste: () async throws -> Void) async throws -> String {
        if canVerify {
            select()
            let receipt = try await read()
            if receipt == .confirmed { return "Texte inséré" }
            try require(receipt == .unchanged, "Le champ a changé pendant l’insertion. Votre texte reste dans le brouillon ; vérifiez le champ avant de réessayer.")
        }
        try await paste()
        if canVerify {
            let receipt = try await read()
            if receipt == .confirmed { return "Texte inséré" }
        }
        // paste() has checked the destination focus and waited for consumption of our text.
        // A rich editor may replace its AX element or keep a stale value after that paste.
        // Do not turn this readback limitation into a retry that could insert twice.
        return "Texte transmis · copie conservée dans les notes"
    }
}
