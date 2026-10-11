import AppKit
import SwiftUI

/// MenuBarExtra extracts an Image label; a Shape label can produce an empty item.
@MainActor enum MenuBarIcon {
    static let image: NSImage = {
        let image = NSImage(size: NSSize(width: 23, height: 18), flipped: true) { _ in
            guard let context = NSGraphicsContext.current?.cgContext else { return false }
            context.setStrokeColor(NSColor.black.cgColor)
            context.setLineWidth(2)
            context.setLineCap(.round)
            context.setLineJoin(.round)
            context.addPath(EmblemShape().path(in: CGRect(x: 1, y: 3.2, width: 21, height: 11.6)).cgPath)
            context.strokePath()
            return true
        }
        image.isTemplate = true
        return image
    }()
}
