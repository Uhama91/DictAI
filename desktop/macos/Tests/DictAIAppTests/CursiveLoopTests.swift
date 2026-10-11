import CoreGraphics
import Testing
@testable import DictAI

struct CursiveLoopTests {
    @Test func connectedLoopsNeverTearAtTheirJunctions() {
        for time in [0.0, 0.25, 2.0, 60.0] {
            let loops = CursiveLoopGeometry.loops(time: time, level: 0.8)
            for (a, b) in zip(loops, loops.dropFirst()) {
                #expect(abs(a.end - b.start) < 0.000001)
                #expect(abs(a.endValley - b.startValley) < 0.000001)
            }
            #expect(loops.first!.start < 0 && loops.last!.end > 100)
        }
    }
    @Test func voiceExpandsCrestsAndValleysWithinTheCanvas() {
        let resting = CursiveLoopGeometry.loops(time: 1, level: 0)
        let voiced = CursiveLoopGeometry.loops(time: 1, level: 1)
        for (rest, voice) in zip(resting, voiced) {
            #expect(voice.top < rest.top && voice.startValley > rest.startValley)
            #expect(voice.top >= 2 && voice.endValley <= 37)
        }
    }
    @Test func elapsedTimeMovesTheConnectedStroke() {
        let first = CursiveLoopGeometry.path(time: 0, level: 0.5, in: CGRect(x: 0, y: 0, width: 100, height: 40))
        let later = CursiveLoopGeometry.path(time: 0.4, level: 0.5, in: CGRect(x: 0, y: 0, width: 100, height: 40))
        #expect(first != later)
    }
    @Test func invalidAudioLevelsRemainFiniteAndBounded() {
        for value in [Double.nan, .infinity, -.infinity, -10, 20] {
            let loops = CursiveLoopGeometry.loops(time: .infinity, level: value)
            #expect(!loops.isEmpty && loops.count < 48)
            #expect(loops.allSatisfy { $0.start.isFinite && $0.top >= 2 && $0.endValley <= 37 })
        }
    }
}
