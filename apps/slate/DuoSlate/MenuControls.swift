#if os(iOS)
import SwiftUI

/// 設定メニューを開いている間だけ出る「メニュー操作モード」。
///
/// 画面の真ん中に、丸い光のネオンだけが浮かぶ(輪郭の線は描かない)。
/// ・上へフリック＝一つ上、下へフリック＝一つ下、丸をタップ＝決定。左へフリック＝戻る。
/// ・フリックの向きの矢印は、フリックしている間だけ、光としてふっと灯る。
/// ・丸はフリックに少しついていって、軌道の余韻を残しながら真ん中へ戻る。
/// すべて線ではなく光だけで、光らせ過ぎず、さりげなく。
struct MenuControls: View {
    let c: Palette
    let level: Double
    var send: (String) -> Void

    @State private var appear = false
    /// 丸の、真ん中からのずれ(フリックで少し動いて、余韻を残して戻る)。
    @State private var off = CGSize.zero
    /// 決定のとき、ふっと明るく。
    @State private var okGlow = false
    /// 今のフリックの向き(矢印を出す向き)。
    @State private var dir: Dir = .none

    enum Dir { case none, up, down, back, right }

    private var accent: Color { c.glow }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            Canvas { ctx, size in
                let base = (0.5 + (okGlow ? 0.35 : 0)) * level * (appear ? 1 : 0)
                let cx = size.width / 2, cy = size.height / 2 + (appear ? 0 : 26) // 下からふっと浮かぶ
                let ox = off.width, oy = off.height
                let r = min(size.width, size.height) * 0.072 * (appear ? 1 : 0.6) * (okGlow ? 1.18 : 1)

                // 軌道の余韻: 真ん中から今の位置へ、薄い光を点々と残す
                for f in stride(from: 0.28, through: 0.86, by: 0.29) {
                    glow(&ctx, CGPoint(x: cx + ox * f, y: cy + oy * f), r * (0.6 + 0.35 * f), base * 0.10)
                }
                // 本体の丸い光
                glow(&ctx, CGPoint(x: cx + ox, y: cy + oy), r, base)

                // フリック中の矢印(光だけ・ぼかして線に見せない)
                let mag = hypot(ox, oy)
                if mag > 1.5 {
                    let a = min(0.5, mag / 46) * level
                    arrow(&ctx, center: CGPoint(x: cx, y: cy), orb: r, dir: dir, alpha: a)
                }
            }
            .frame(width: w, height: h)
            .contentShape(Rectangle())
            .highPriorityGesture(
                DragGesture(minimumDistance: 10)
                    .onEnded { v in
                        let dx = v.translation.width + v.predictedEndTranslation.width * 0.2
                        let dy = v.translation.height + v.predictedEndTranslation.height * 0.2
                        if abs(dy) >= abs(dx) {
                            flick(dy < 0 ? .up : .down)
                        } else {
                            flick(dx < 0 ? .back : .right)
                        }
                    }
            )
            .onTapGesture { ok() }
            .onAppear { withAnimation(.spring(response: 0.6, dampingFraction: 0.8)) { appear = true } }
        }
    }

    // MARK: 光を描く(線は使わない)

    private func glow(_ ctx: inout GraphicsContext, _ p: CGPoint, _ r: CGFloat, _ a: Double) {
        guard a > 0.001, r > 0.5 else { return }
        let g = Gradient(stops: [
            .init(color: accent.opacity(a), location: 0),
            .init(color: accent.opacity(a * 0.42), location: 0.45),
            .init(color: .clear, location: 1),
        ])
        ctx.fill(Path(ellipseIn: CGRect(x: p.x - r * 2.2, y: p.y - r * 2.2, width: r * 4.4, height: r * 4.4)),
                 with: .radialGradient(g, center: p, startRadius: 0, endRadius: r * 2.2))
    }

    /// フリックの向きのシェブロンを、ぼかした光で(線の芯を見せない)。
    private func arrow(_ ctx: inout GraphicsContext, center: CGPoint, orb r: CGFloat, dir: Dir, alpha a: Double) {
        guard dir != .none, a > 0.001 else { return }
        let d = r * 2.6, s: CGFloat = r * 0.8
        var p = Path()
        switch dir {
        case .up:
            let y = center.y - d
            p.move(to: CGPoint(x: center.x - s, y: y + s * 0.7)); p.addLine(to: CGPoint(x: center.x, y: y - s * 0.3)); p.addLine(to: CGPoint(x: center.x + s, y: y + s * 0.7))
        case .down:
            let y = center.y + d
            p.move(to: CGPoint(x: center.x - s, y: y - s * 0.7)); p.addLine(to: CGPoint(x: center.x, y: y + s * 0.3)); p.addLine(to: CGPoint(x: center.x + s, y: y - s * 0.7))
        case .back:
            let x = center.x - d
            p.move(to: CGPoint(x: x + s * 0.7, y: center.y - s)); p.addLine(to: CGPoint(x: x - s * 0.3, y: center.y)); p.addLine(to: CGPoint(x: x + s * 0.7, y: center.y + s))
        case .right:
            let x = center.x + d
            p.move(to: CGPoint(x: x - s * 0.7, y: center.y - s)); p.addLine(to: CGPoint(x: x + s * 0.3, y: center.y)); p.addLine(to: CGPoint(x: x - s * 0.7, y: center.y + s))
        case .none: return
        }
        var g = ctx
        g.addFilter(.blur(radius: r * 0.5))
        g.stroke(p, with: .color(accent.opacity(a)), style: StrokeStyle(lineWidth: r * 0.42, lineCap: .round, lineJoin: .round))
    }

    // MARK: 操作

    private func flick(_ d: Dir) {
        switch d {
        case .up:    send("k rot -1")
        case .down:  send("k rot 1")
        case .back:  send("k b")
        case .right: send("k c") // 右フリックでも決定
        case .none:  return
        }
        UIImpactFeedbackGenerator(style: d == .right ? .medium : .light).impactOccurred()
        if d == .right { okGlow = true; DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { withAnimation(.easeOut(duration: 0.4)) { okGlow = false } } }
        dir = d
        let reach: CGFloat = 34
        let to: CGSize = d == .up ? .init(width: 0, height: -reach)
                        : d == .down ? .init(width: 0, height: reach)
                        : d == .right ? .init(width: reach, height: 0)
                        : .init(width: -reach, height: 0)
        withAnimation(.easeOut(duration: 0.13)) { off = to }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.13) {
            withAnimation(.spring(response: 0.55, dampingFraction: 0.55)) { off = .zero } // 余韻(少し行き過ぎて戻る)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { if off == .zero { dir = .none } }
    }

    private func ok() {
        send("k c")
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        okGlow = true
        withAnimation(.easeOut(duration: 0.14)) { okGlow = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { withAnimation(.easeOut(duration: 0.4)) { okGlow = false } }
    }
}
#endif
