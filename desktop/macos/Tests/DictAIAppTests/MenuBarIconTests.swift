import AppKit
import Foundation
import Testing
@testable import DictAI

@MainActor struct MenuBarIconTests {
    @Test func menuIconHasVisiblePixelsAndAdaptsToTheSystemAppearance() throws {
        let image = MenuBarIcon.image
        #expect(image.isTemplate)
        #expect(image.size == NSSize(width: 23, height: 18))
        let data = try #require(image.tiffRepresentation)
        let bitmap = try #require(NSBitmapImageRep(data: data))
        let visible = (0..<bitmap.pixelsHigh).reduce(0) { count, y in
            count + (0..<bitmap.pixelsWide).filter { (bitmap.colorAt(x: $0, y: y)?.alphaComponent ?? 0) > 0.5 }.count
        }
        #expect(visible > 20)
        guard let path = ProcessInfo.processInfo.environment["DICTAI_TEST_RENDER_DIR"] else { return }
        let preview = NSImage(size: NSSize(width: 230, height: 180))
        preview.lockFocus()
        NSColor.white.setFill(); NSRect(x: 0, y: 0, width: 230, height: 180).fill()
        image.draw(in: NSRect(x: 0, y: 0, width: 230, height: 180))
        preview.unlockFocus()
        let renderedData = try #require(preview.tiffRepresentation)
        let rendered = try #require(NSBitmapImageRep(data: renderedData))
        let directory = URL(fileURLWithPath: path)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try #require(rendered.representation(using: .png, properties: [:])).write(to: directory.appendingPathComponent("menu-bar-icon.png"))
    }
}
