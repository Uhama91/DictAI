import AppKit

@main struct RenderLoops {
    static func main() throws {
        let directory = URL(fileURLWithPath: CommandLine.arguments[1])
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for frame in 0..<90 {
            let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 300, pixelsHigh: 120, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            let context = NSGraphicsContext(bitmapImageRep: bitmap)!.cgContext
            context.setFillColor(NSColor(srgbRed: 43.0/255, green: 41.0/255, blue: 37.0/255, alpha: 1).cgColor)
            context.fill(CGRect(x: 0, y: 0, width: 300, height: 120))
            context.translateBy(x: 0, y: 120); context.scaleBy(x: 1, y: -1)
            let level = frame < 15 || frame > 70 ? 0.0 : 0.2 + 0.8 * pow(sin(Double(frame - 15) / 55 * .pi), 2)
            let path = CursiveLoopGeometry.path(time: Double(frame) / 30, level: level, in: CGRect(x: 0, y: 0, width: 300, height: 120))
            context.addPath(path); context.setLineWidth(6.6); context.setLineCap(.round); context.setLineJoin(.round)
            context.setStrokeColor(NSColor(srgbRed: 175.0/255, green: 208.0/255, blue: 173.0/255, alpha: 1).cgColor)
            context.strokePath()
            let data = bitmap.representation(using: .png, properties: [:])!
            try data.write(to: directory.appendingPathComponent(String(format: "loop-%03d.png", frame)))
        }
    }
}
