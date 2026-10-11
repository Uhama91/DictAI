import AppKit

@main struct GenerateIcon {
    static func main() throws {
        let destination = URL(fileURLWithPath: CommandLine.arguments[1])
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        for base in [16, 32, 128, 256, 512] {
            for multiplier in [1, 2] {
                let size = base * multiplier
                let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
                let graphics = NSGraphicsContext(bitmapImageRep: bitmap)!
                NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = graphics
                let context = graphics.cgContext
                context.scaleBy(x: CGFloat(size) / 1024, y: CGFloat(size) / 1024)
                context.translateBy(x: 0, y: 1024); context.scaleBy(x: 1, y: -1)
                context.setFillColor(NSColor(srgbRed: 250/255, green: 248/255, blue: 243/255, alpha: 1).cgColor)
                context.addPath(CGPath(roundedRect: CGRect(x: 56, y: 56, width: 912, height: 912), cornerWidth: 206, cornerHeight: 206, transform: nil)); context.fillPath()
                context.translateBy(x: 80, y: 80); context.scaleBy(x: 8, y: 8)
                context.addPath(DictAIEmblem.path); context.setLineWidth(7)
                context.setStrokeColor(NSColor(srgbRed: 26/255, green: 26/255, blue: 46/255, alpha: 1).cgColor)
                context.setLineCap(.round); context.setLineJoin(.round); context.strokePath()
                NSGraphicsContext.restoreGraphicsState()
                let suffix = multiplier == 2 ? "@2x" : ""
                try bitmap.representation(using: .png, properties: [:])!.write(to: destination.appendingPathComponent("icon_\(base)x\(base)\(suffix).png"))
            }
        }
    }
}
