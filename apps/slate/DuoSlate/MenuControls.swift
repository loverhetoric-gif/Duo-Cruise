#if os(iOS)
import SwiftUI

/// 丸い光のネオンをフリックで操作する部品。設定メニューと YouTube で使う。
///
/// 画面の真ん中に、丸い光だけが浮かぶ(輪郭の線は描かない)。
/// ・フリックの向きの矢印は、フリックしている間だけ、光としてふっと灯る。
/// ・丸はフリックに少しついていって、軌道の余韻を残しながら真ん中へ戻る。
/// すべて線ではなく光だけで、光らせ過ぎず、さりげなく。
///
/// プロファイルで中身が変わる:
/// ・メニュー: 上＝一つ上 / 下＝一つ下 / 右・タップ＝決定 / 左＝戻る
/// ・YouTube: 上下左右＝その向きへ / タップ＝決定 / 長押し＝戻る
struct FlickPad: View {
    enum Profile { case menu, youtube }
    let c: Palette
    let level: Double
    var profile: Profile = .menu
    var send: (String) -> Void

    @State private var appear = false
    @State private var off = CGSize.zero
    @State private var okGlow = false
    @State private var dir: Dir = .none

    enum Dir { case none, up, down, left, right }

    private var accent: Color { c.glow }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            Canvas { ctx, size in
                let base = (0.5 + (okGlow ? 0.35 : 0)) * level * (appear ? 1 : 0)
                let cx = size.width / 2, cy = size.height / 2 + (appear ? 0 : 26) // 下からふっと浮かぶ
                let ox = off.width, oy = off.height
                let r = min(size.width, size.height) * 0.072 * (appear ? 1 : 0.6) * (okGlow ? 1.18 : 1)
                for f in stride(from: 0.28, through: 0.86, by: 0.29) {
                    glow(&ctx, CGPoint(x: cx + ox * f, y: cy + oy * f), r * (0.6 + 0.35 * f), base * 0.10)
                }
                glow(&ctx, CGPoint(x: cx + ox, y: cy + oy), r, base)
                let mag = hypot(ox, oy)
                if mag > 1.5 { arrow(&ctx, center: CGPoint(x: cx, y: cy), orb: r, dir: dir, alpha: min(0.5, mag / 46) * level) }
            }
            .frame(width: w, height: h)
            .contentShape(Rectangle())
            .highPriorityGesture(
                DragGesture(minimumDistance: 10)
                    .onEnded { v in
                        let dx = v.translation.width + v.predictedEndTranslation.width * 0.2
                        let dy = v.translation.height + v.predictedEndTranslation.height * 0.2
                        flick(abs(dy) >= abs(dx) ? (dy < 0 ? .up : .down) : (dx < 0 ? .left : .right))
                    }
            )
            .onTapGesture { decide() }
            // YouTube は上下左右を使うので、戻るは長押し
            .onLongPressGesture(minimumDuration: 0.45) { if profile == .youtube { back() } }
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
        case .left:
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

    /// フリックの向きに応じた命令を送る(プロファイルで中身が変わる)。
    private func flick(_ d: Dir) {
        let cmd: String
        var isDecide = false
        switch profile {
        case .menu:
            switch d {
            case .up: cmd = "k rot -1"; case .down: cmd = "k rot 1"
            case .left: cmd = "k b"; case .right: cmd = "k c"; isDecide = true
            case .none: return
            }
        case .youtube:
            switch d {
            case .up: cmd = "k up"; case .down: cmd = "k dn"
            case .left: cmd = "k lf"; case .right: cmd = "k rt"
            case .none: return
            }
        }
        send(cmd)
        UIImpactFeedbackGenerator(style: isDecide ? .medium : .light).impactOccurred()
        if isDecide { pulseOk() }
        dir = d
        let reach: CGFloat = 34
        let to: CGSize = d == .up ? .init(width: 0, height: -reach)
                        : d == .down ? .init(width: 0, height: reach)
                        : d == .right ? .init(width: reach, height: 0)
                        : .init(width: -reach, height: 0)
        withAnimation(.easeOut(duration: 0.13)) { off = to }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.13) {
            withAnimation(.spring(response: 0.55, dampingFraction: 0.55)) { off = .zero }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { if off == .zero { dir = .none } }
    }

    private func decide() {
        send("k c")
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        pulseOk()
    }

    private func back() {
        send("k b")
        UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
    }

    private func pulseOk() {
        okGlow = true
        withAnimation(.easeOut(duration: 0.14)) { okGlow = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { withAnimation(.easeOut(duration: 0.4)) { okGlow = false } }
    }
}
#endif
