import CoreGraphics

/// The exact three-loop path from Android's ic_launcher_foreground.xml (108 × 108).
/// Shared by the native interface and the icon renderer.
enum DictAIEmblem {
    static var path: CGPath {
        let p = CGMutablePath()
        p.move(to: CGPoint(x: 18, y: 72))
        p.addCurve(to: CGPoint(x: 34.8, y: 36), control1: CGPoint(x: 31.2, y: 72), control2: CGPoint(x: 45.6, y: 36))
        p.addCurve(to: CGPoint(x: 42, y: 72), control1: CGPoint(x: 25.2, y: 36), control2: CGPoint(x: 34.8, y: 72))
        p.addCurve(to: CGPoint(x: 58.8, y: 36), control1: CGPoint(x: 55.2, y: 72), control2: CGPoint(x: 69.6, y: 36))
        p.addCurve(to: CGPoint(x: 66, y: 72), control1: CGPoint(x: 49.2, y: 36), control2: CGPoint(x: 58.8, y: 72))
        p.addCurve(to: CGPoint(x: 82.8, y: 36), control1: CGPoint(x: 79.2, y: 72), control2: CGPoint(x: 93.6, y: 36))
        p.addCurve(to: CGPoint(x: 90, y: 72), control1: CGPoint(x: 73.2, y: 36), control2: CGPoint(x: 82.8, y: 72))
        return p
    }
}
