import Foundation
import CoreGraphics

/// The same connected, crossed cubic loops as Android's VIVID compact wave.
struct CursiveLoopGeometry {
    struct Loop {
        let start: Double
        let end: Double
        let top: Double
        let startValley: Double
        let endValley: Double
    }
    static let variations: [(width: Double, amplitude: Double)] = [(0.92, 0.94), (1.06, 1.04), (0.86, 1.08), (1.12, 0.91), (0.98, 1.06), (1.04, 0.97)]
    static let period = variations.reduce(0) { $0 + 28 * $1.width }
    static func loops(time: Double, level: Double) -> [Loop] {
        let seconds = time.isFinite ? max(0, time) : 0
        let voice = level.isFinite ? min(1, max(0, level)) : 0
        let offset = (seconds * 42).truncatingRemainder(dividingBy: period)
        let phase = (seconds * 6).truncatingRemainder(dividingBy: 2 * .pi)
        func activity(_ index: Int) -> Double {
            0.42 + 0.58 * (0.5 + 0.5 * sin(phase + Double(index % variations.count) * 1.08 + 0.42))
        }
        var result: [Loop] = []
        var x = offset - period - 56
        var index = 0
        while x < 156 && index < 48 {
            let variation = variations[index % variations.count]
            let width = 28 * variation.width
            let distance = abs(x + width / 2 - 50) / 50
            let envelope = max(0, 1 - pow(distance, 1.35))
            let pulse = min(1.15, max(0.65, 0.85 + 0.15 * sin(phase + Double(index % variations.count) * 1.08 - distance * 1.8)))
            let event = min(1, (activity(index) + activity(index + 1)) * 0.5 * 0.85 + pulse * 0.15)
            let peak = min(1, voice * variation.amplitude * (0.7 + 0.3 * envelope) * event)
            result.append(Loop(start: x, end: x + width, top: max(2, 12 - 9 * peak), startValley: 28 + 9 * voice * activity(index), endValley: 28 + 9 * voice * activity(index + 1)))
            x += width; index += 1
        }
        return result
    }
    static func path(time: Double, level: Double, in rect: CGRect) -> CGPath {
        let path = CGMutablePath()
        let loops = loops(time: time, level: level)
        if let first = loops.first { path.move(to: CGPoint(x: first.start, y: first.startValley)) }
        for loop in loops {
            let width = loop.end - loop.start
            path.addCurve(to: CGPoint(x: loop.start + width * 0.8, y: loop.top), control1: CGPoint(x: loop.start + width * 0.5, y: loop.startValley), control2: CGPoint(x: loop.start + width * 1.1, y: loop.top))
            path.addCurve(to: CGPoint(x: loop.end, y: loop.endValley), control1: CGPoint(x: loop.start + width * 0.2, y: loop.top), control2: CGPoint(x: loop.start + width * 0.8, y: loop.endValley))
        }
        let scale = min(rect.width / 100, rect.height / 40)
        var transform = CGAffineTransform(translationX: rect.midX - 50 * scale, y: rect.midY - 20 * scale).scaledBy(x: scale, y: scale)
        return path.copy(using: &transform)!
    }
}
