import SwiftUI

struct CursiveLoopShape: Shape {
    var time: Double
    var level: Double
    var animatableData: Double { get { level } set { level = newValue } }
    func path(in rect: CGRect) -> Path {
        Path(CursiveLoopGeometry.path(time: time, level: level, in: rect))
    }
}

struct CursiveLoopsView: View {
    let level: Float
    let active: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var visible = false
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !active || reduceMotion || !visible)) { timeline in
            let moving = active && !reduceMotion && visible
            CursiveLoopShape(time: moving ? timeline.date.timeIntervalSinceReferenceDate : 0, level: moving ? Double(level) : 0)
                .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                .animation(reduceMotion ? nil : .easeOut(duration: 0.10), value: level)
                .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .white, location: 0.14), .init(color: .white, location: 0.86), .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
        }.frame(width: 76, height: 32).clipped()
            .onAppear { visible = true }.onDisappear { visible = false }.accessibilityHidden(true)
    }
}

struct DictationLoops: View {
    @ObservedObject var model: AppModel
    let presentation: DictationPresentation
    var body: some View {
        CursiveLoopsView(level: model.level, active: model.state == .listening && model.panelVisible && model.presentation == presentation)
    }
}
