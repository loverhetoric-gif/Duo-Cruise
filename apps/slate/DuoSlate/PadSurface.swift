#if os(iOS)
import UIKit

/// 指の動きを読むタッチパッドの面。
///
/// - 1本指でなぞる → カーソルを動かす。iPhone が読み取った細かい点(coalesced)をすべて使い、間引かない
/// - 速さに応じた加速(Mac のトラックパッドと同じ考え方): ゆっくりは細かく、速くは遠くまで
/// - 1px 未満の動きも捨てずに足していく(小数のまま送る)
/// - 1本指で軽く触れて離す → タップ(押す)。触れた直後の小さな揺れは動きとみなさない
/// - 1本指で 0.4 秒じっと押す → ドラッグ(押したまま動かす)。離すと終わる
/// - 2本指 → 2本の指としてそのまま OTTO へ(スクロール・ピンチ)
final class PadSurface: UIView {
    /// 1行ずつ送る先。
    var send: (String) -> Void = { _ in }
    /// 指が触れている場所(縁の光が指を追うため)。離したら nil。
    var onTouch: (CGPoint?) -> Void = { _ in }
    /// 感度(1＝標準)と加速の強さ(0＝なし)。
    var sensitivity: Double = 1.0
    var acceleration: Double = 1.0
    /// OTTO の画面の幅(px)。2本指の動きの大きさを合わせるのに使う。
    var screenWidth: Double = 1480
    var screenHeight: Double = 480
    /// 画面とパッドの大きさに合わせて自動で決める(CursorTune)。縦・横に向けても、その時の形で決め直す。
    var autoTune = true

    private let tapMaxTime: TimeInterval = 0.25
    private let tapSlop: CGFloat = 8       // これより小さい揺れはタップのまま
    private let holdTime: TimeInterval = 0.4

    private var first: UITouch?
    private var firstStart = CGPoint.zero, firstStartTime: TimeInterval = 0
    private var moved = false, dragging = false, twoActive = false
    private var last: [ObjectIdentifier: (CGPoint, TimeInterval)] = [:]
    private var holdTimer: Timer?
    private let tapHaptic = UIImpactFeedbackGenerator(style: .light)
    private let holdHaptic = UIImpactFeedbackGenerator(style: .medium)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
    }
    required init?(coder: NSCoder) { fatalError() }

    /// iPhone の 1pt を OTTO の何 px にするか(加速前)。
    private var tune: (sensitivity: Double, acceleration: Double) {
        autoTune ? CursorTune.auto(CGSize(width: screenWidth, height: screenHeight), pad: bounds.size)
                 : (sensitivity, acceleration)
    }
    private var base: Double { CursorTune.slowBase * tune.sensitivity }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        let all = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled } ?? Array(touches)
        for t in touches { last[ObjectIdentifier(t)] = (t.location(in: self), t.timestamp) }
        if all.count >= 2 {
            startTwo(all)
            return
        }
        guard let t = touches.first else { return }
        onTouch(t.location(in: self))
        first = t
        firstStart = t.location(in: self)
        firstStartTime = t.timestamp
        moved = false
        tapHaptic.prepare(); holdHaptic.prepare()
        holdTimer?.invalidate()
        holdTimer = Timer.scheduledTimer(withTimeInterval: holdTime, repeats: false) { [weak self] _ in
            guard let self, !self.moved, !self.twoActive, self.first != nil else { return }
            self.dragging = true
            self.holdHaptic.impactOccurred()
            self.send("d")
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if twoActive { moveTwo(event); return }
        guard let f = first, touches.contains(f) else { return }
        onTouch(f.location(in: self))
        let samples = event?.coalescedTouches(for: f) ?? [f]
        var dx = 0.0, dy = 0.0
        for s in samples {
            let id = ObjectIdentifier(f)
            let p = s.location(in: self)
            guard let (lp, lt) = last[id] else { last[id] = (p, s.timestamp); continue }
            last[id] = (p, s.timestamp)
            if !moved {
                if hypot(p.x - firstStart.x, p.y - firstStart.y) < tapSlop { continue } // 触れた直後の揺れ
                moved = true
                holdTimer?.invalidate()
            }
            let ddx = Double(p.x - lp.x), ddy = Double(p.y - lp.y)
            let dt = max(s.timestamp - lt, 0.001) * 1000           // ms
            let speed = hypot(ddx, ddy) / dt                         // pt/ms
            let t = tune
            let gain = CursorTune.slowBase * t.sensitivity * (1 + t.acceleration * min(speed, CursorTune.fastSpeed) * CursorTune.fastK)
            dx += ddx * gain; dy += ddy * gain
        }
        if dx != 0 || dy != 0 { send(String(format: "m %.2f %.2f", dx, dy)) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches, event) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches, event) }

    private func end(_ touches: Set<UITouch>, _ event: UIEvent?) {
        let left = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled }.count ?? 0
        if left == 0 { onTouch(nil) }
        for t in touches { last.removeValue(forKey: ObjectIdentifier(t)) }
        if twoActive {
            let left = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled }.count ?? 0
            if left < 2 { twoActive = false; send("p u"); first = nil }
            return
        }
        guard let f = first, touches.contains(f) else { return }
        holdTimer?.invalidate()
        if dragging {
            dragging = false
            send("u")
        } else if !moved && f.timestamp - firstStartTime <= tapMaxTime {
            tapHaptic.impactOccurred()
            send("c")
        }
        first = nil
    }

    // MARK: - 2本指

    /// OTTO 上の2本指の間隔・動きの大きさ。面の幅を OTTO の画面幅の約半分に合わせる。
    private var twoScale: Double { screenWidth * 0.5 / max(Double(bounds.width), 1) }

    private func startTwo(_ all: [UITouch]) {
        holdTimer?.invalidate()
        if dragging { dragging = false; send("u") }
        let a = all[0].location(in: self), b = all[1].location(in: self)
        twoPair = (all[0], all[1])
        twoActive = true
        send(String(format: "p d %.1f %.1f", Double(b.x - a.x) * twoScale, Double(b.y - a.y) * twoScale))
    }

    private var twoPair: (UITouch, UITouch)?

    private func moveTwo(_ event: UIEvent?) {
        guard let (ta, tb) = twoPair else { return }
        func delta(_ t: UITouch) -> (Double, Double) {
            let id = ObjectIdentifier(t)
            let p = t.location(in: self)
            let lp = last[id]?.0 ?? p
            last[id] = (p, t.timestamp)
            return (Double(p.x - lp.x) * twoScale, Double(p.y - lp.y) * twoScale)
        }
        let (ax, ay) = delta(ta), (bx, by) = delta(tb)
        if ax == 0 && ay == 0 && bx == 0 && by == 0 { return }
        send(String(format: "p m %.2f %.2f %.2f %.2f", ax, ay, bx, by))
    }
}
#endif
