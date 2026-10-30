#if os(iOS)
import SwiftUI

/// 設定メニューを開いている間だけ出る「メニュー操作モード」。
///
/// タッチパッドを無理になぞらなくても、光で表した 4 つの操作——前へ／次へ／決定／戻る——に触れて、
/// Duo Cruise のメニューを動かせる。塗りつぶしの玉や事務的な矢印は使わず、細い発光の線だけで表す。
///
/// ・上下＝細い光のシェブロン(長押しで連続)。触れるとその向きがふっと灯る。
/// ・決定＝細い光のリングと、中心の小さな点。押すと波紋が外へ広がる。
/// ・戻る＝尾を引く光の弧。
struct MenuControls: View {
    let c: Palette
    let level: Double
    var send: (String) -> Void

    @State private var appear = false
    @State private var flash: Cmd? = nil
    @State private var pulse = false
    @State private var repeater: Timer?

    enum Cmd { case up, down, ok, back }

    /// 落ち着いた発光色(夜＝白、昼＝青)。けばけばしい塗りは使わない。
    private var accent: Color { c.glow }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let ring = min(w, h) * 0.165
            ZStack {
                // 入った感: 中央のごくほのかな光だまり
                RadialGradient(colors: [accent.opacity(0.06 * level), .clear],
                               center: .center, startRadius: 0, endRadius: max(w, h) * 0.5)
                    .allowsHitTesting(false)
                    .opacity(appear ? 1 : 0)

                VStack(spacing: 0) {
                    chevron(up: true).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                        .highPriorityGesture(hold(.up))
                    Color.clear.frame(height: ring * 2.2)
                    chevron(up: false).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                        .highPriorityGesture(hold(.down))
                }

                decide(size: ring)
                    .contentShape(Circle())
                    .onTapGesture { tap(.ok) }

                backArc
                    .frame(width: 60, height: 60)
                    .position(x: 54, y: h - 170)
                    .contentShape(Rectangle())
                    .onTapGesture { tap(.back) }
            }
            .onAppear { withAnimation(.spring(response: 0.55, dampingFraction: 0.82)) { appear = true } }
        }
    }

    // MARK: 形(すべて細い光の線)

    /// 前へ／次へ: 細い光のシェブロン。触れるとその向きが明るくなる。
    private func chevron(up: Bool) -> some View {
        let on = flash == (up ? .up : .down)
        return Chevron(up: up)
            .stroke(accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            .frame(width: 50, height: 19)
            .shadow(color: accent.opacity((on ? 0.8 : 0.45) * level), radius: on ? 12 : 7)
            .opacity((on ? 1.0 : 0.6) * (appear ? 1 : 0))
            .scaleEffect(on ? 1.16 : 1, anchor: up ? .bottom : .top)
            .offset(y: appear ? 0 : (up ? 12 : -12))
            .animation(.spring(response: 0.3, dampingFraction: 0.55), value: on)
    }

    /// 決定: 細い光のリングと中心の点。押すと波紋。塗りの玉にはしない。
    private func decide(size: CGFloat) -> some View {
        let on = flash == .ok
        return ZStack {
            Circle().stroke(accent, lineWidth: 1.8)
                .opacity(on ? 1 : 0.85)
            Circle().fill(accent).frame(width: 5.5, height: 5.5)
            // 波紋(押したときだけ外へ広がって消える)
            Circle().stroke(accent, lineWidth: 1.5)
                .scaleEffect(pulse ? 2.0 : 1).opacity(pulse ? 0 : 0.0)
        }
        .frame(width: size, height: size)
        .shadow(color: accent.opacity((on ? 0.7 : 0.4) * level), radius: on ? 16 : 10)
        .scaleEffect((on ? 0.92 : 1) * (appear ? 1 : 0.7))
        .opacity(appear ? 1 : 0)
        .overlay(
            Circle().stroke(accent, lineWidth: 1.5)
                .frame(width: size, height: size)
                .scaleEffect(pulse ? 2.0 : 1).opacity(pulse ? 0 : 0.35)
                .allowsHitTesting(false)
        )
        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: on)
        .animation(.easeOut(duration: 0.55), value: pulse)
    }

    /// 戻る: 尾を引く細い光の弧。
    private var backArc: some View {
        let on = flash == .back
        return ReturnArc()
            .stroke(accent, style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
            .shadow(color: accent.opacity((on ? 0.7 : 0.4) * level), radius: on ? 11 : 6)
            .opacity((on ? 1 : 0.55) * (appear ? 1 : 0))
            .scaleEffect(on ? 1.14 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.55), value: on)
    }

    // MARK: 操作

    private func tap(_ cmd: Cmd) { fire(cmd) }

    /// 長押しで連続(前へ／次へ)。押した瞬間に 1 回、0.35 秒後から 0.13 秒ごと。
    private func hold(_ cmd: Cmd) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in
                if repeater == nil {
                    fire(cmd)
                    repeater = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: false) { _ in
                        repeater = Timer.scheduledTimer(withTimeInterval: 0.13, repeats: true) { _ in sendOnly(cmd) }
                    }
                }
            }
            .onEnded { _ in repeater?.invalidate(); repeater = nil }
    }

    private func fire(_ cmd: Cmd) {
        sendOnly(cmd)
        UIImpactFeedbackGenerator(style: cmd == .ok ? .medium : .light).impactOccurred()
        flash = cmd
        if cmd == .ok { pulse = false; withAnimation { pulse = true }; DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) { pulse = false } }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { if flash == cmd { flash = nil } }
    }

    private func sendOnly(_ cmd: Cmd) {
        switch cmd {
        case .up:   send("k rot -1")
        case .down: send("k rot 1")
        case .ok:   send("k c")
        case .back: send("k b")
        }
    }
}

/// 前へ／次へのシェブロン(への字／逆への字)。矢印の軸は描かない。
private struct Chevron: Shape {
    var up: Bool
    func path(in r: CGRect) -> Path {
        var p = Path()
        if up {
            p.move(to: CGPoint(x: r.minX, y: r.maxY))
            p.addLine(to: CGPoint(x: r.midX, y: r.minY))
            p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        } else {
            p.move(to: CGPoint(x: r.minX, y: r.minY))
            p.addLine(to: CGPoint(x: r.midX, y: r.maxY))
            p.addLine(to: CGPoint(x: r.maxX, y: r.minY))
        }
        return p
    }
}

/// 戻るの、尾を引く弧(反時計回りに開いた輪＋短い払い)。
private struct ReturnArc: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let c = CGPoint(x: r.midX, y: r.midY), rad = min(r.width, r.height) * 0.36
        p.addArc(center: c, radius: rad, startAngle: .degrees(-35), endAngle: .degrees(215), clockwise: false)
        let tip = CGPoint(x: c.x + rad * cos(.pi * -35 / 180), y: c.y + rad * sin(.pi * -35 / 180))
        p.move(to: tip)
        p.addLine(to: CGPoint(x: tip.x - rad * 0.55, y: tip.y - rad * 0.05))
        p.move(to: tip)
        p.addLine(to: CGPoint(x: tip.x - rad * 0.05, y: tip.y - rad * 0.6))
        return p
    }
}
#endif
