#if os(iOS)
import SwiftUI
import UIKit

/// Duo Cruise の画面の並び(OTTO が「l 1画面 入れ替え 操作側 YouTube%」で知らせる)。
struct DuoLayout: Equatable {
    var single = false
    var swapped = false
    /// 操作している側("L"＝左 / "R"＝右)。1 画面のときは、出している中身を決める。
    var side = "L"
    /// YouTube の幅の割合(%)。
    var yt = 50

    /// 画面上のその側にあるのは YouTube か(Duo Cruise の SplitGeometry.tvOn と同じ)。
    func tvOn(_ s: String) -> Bool { swapped ? s == "L" : s == "R" }
}

/// 画面の縮図。Duo Cruise の画面の形(1480×480)の中に、CarPlay と YouTube の枠を今の並びで描く。
/// タップ＝その側をターゲットに、ダブルタップ＝その側の 1 画面 ⇔ 2 画面、左右へなぞる＝ウィンドウの切り替え。
/// 見た目は縁の光と同じ考え方: 塗りは無く、細い線だけ。ターゲットの枠だけがネオンの色で光る。角は丸めない。
struct LayoutMap: View {
    let c: Palette
    let layout: DuoLayout
    let aspect: CGFloat
    var send: (String) -> Void
    var touched: () -> Void

    @State private var dragX: CGFloat = 0
    /** 境目をつかんで動かしている間の YouTube の割合(0〜100)。nil なら動かしていない。 */
    @State private var dragYt: CGFloat?
    @State private var grabbedEdge = false

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            ZStack(alignment: .topLeading) {
                Rectangle().stroke(c.icon.opacity(0.18), lineWidth: 1)
                ForEach(panes(w), id: \.name) { p in
                    pane(p, h: h)
                }
                if !layout.single, let bx = boundaryX(w) { divider(bx, h: h) }
            }
            .contentShape(Rectangle())
            .offset(x: dragX * 0.25)
            .gesture(
                DragGesture(minimumDistance: 6)
                    .onChanged { v in
                        touched()
                        // 境目の近く(±28pt)でつかんだら分割比、それ以外はウィンドウの切り替え
                        if dragYt == nil && !grabbedEdge, !layout.single, let bx = boundaryX(w),
                           abs(v.startLocation.x - bx) < 28 { grabbedEdge = true }
                        if grabbedEdge {
                            let x = min(max(0, v.location.x), w)
                            dragYt = (layout.swapped ? x / w : 1 - x / w) * 100
                        } else if abs(v.translation.width) > 18 {
                            dragX = v.translation.width
                        }
                    }
                    .onEnded { v in
                        if grabbedEdge, let y = dragYt {
                            haptic(); send("k yt \(Int(y.rounded()))")
                        } else if abs(v.translation.width) > w * 0.18 && !layout.single { haptic(); send("k sw") }
                        grabbedEdge = false
                        withAnimation(.spring(response: 0.45, dampingFraction: 0.7)) { dragX = 0; dragYt = nil }
                    }
            )
            .simultaneousGesture(
                SpatialTapGesture(count: 2).onEnded { v in
                    haptic(); touched(); send("k sg " + (v.location.x < w / 2 ? "L" : "R"))
                }
            )
            .simultaneousGesture(
                SpatialTapGesture(count: 1).onEnded { v in
                    let s = v.location.x < w / 2 ? "L" : "R"
                    touched()
                    if !layout.single && s != layout.side { haptic(); send("k tg " + s) }
                }
            )
        }
        .aspectRatio(aspect, contentMode: .fit)
        .animation(.spring(response: 0.5, dampingFraction: 0.72), value: layout)
    }

    private struct Pane { let name: String; let x: CGFloat; let width: CGFloat; let target: Bool }

    /** 今の(動かしている間はその)YouTube の割合。 */
    private var ytNow: CGFloat { dragYt ?? CGFloat(min(100, max(0, layout.yt))) }

    /**
     * 左右の枠の境目の x。片方だけの全画面(0% / 100%)のときは縮図の端に置く
     * (端のつまみをつかんで引き戻せるように。端に寄せたまま消すと戻せなかった)。
     */
    private func boundaryX(_ w: CGFloat) -> CGFloat? {
        let yt = ytNow / 100
        let x = layout.swapped ? w * yt : w * (1 - yt)
        return min(max(x, 4), w - 4)
    }

    /** 境目のつまみ: 細い光の線と、真ん中の小さな四角。動かしている間は割合を出す。 */
    private func divider(_ x: CGFloat, h: CGFloat) -> some View {
        let glow = c.flow.first ?? c.glow
        return ZStack {
            Rectangle().fill(glow.opacity(dragYt == nil ? 0.55 : 1)).frame(width: 2, height: h + 12)
            Rectangle().fill(glow).frame(width: 8, height: 22)
            if let y = dragYt {
                Text("\(Int((100 - y).rounded()))  |  \(Int(y.rounded()))")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(c.icon)
                    .offset(y: -h / 2 - 16)
            }
        }
        .frame(width: 44, height: h)
        .offset(x: x - 22)
        .allowsHitTesting(false)
    }

    /// 今の並びの枠(画面の外に出ているものは描かない)。
    private func panes(_ w: CGFloat) -> [Pane] {
        if layout.single {
            let tv = layout.tvOn(layout.side)
            return [Pane(name: tv ? "tv" : "car", x: 0, width: w, target: true)]
        }
        let yt = ytNow / 100
        let tvW = w * yt, carW = w - tvW
        let tvX: CGFloat = layout.swapped ? 0 : carW
        let carX: CGFloat = layout.swapped ? tvW : 0
        var out: [Pane] = []
        if carW > 1 { out.append(Pane(name: "car", x: carX, width: carW, target: !layout.tvOn(layout.side))) }
        if tvW > 1 { out.append(Pane(name: "tv", x: tvX, width: tvW, target: layout.tvOn(layout.side))) }
        return out
    }

    private func pane(_ p: Pane, h: CGFloat) -> some View {
        let glow = c.flow.first ?? c.glow
        return ZStack {
            Rectangle().fill(c.icon.opacity(p.target ? 0.06 : 0.02))
            Rectangle().stroke(p.target ? glow : c.icon.opacity(0.35), lineWidth: p.target ? 1.6 : 1)
            if p.target {
                // 光のにじみ(縁の光と同じく、細い線の外へ柔らかく)
                Rectangle().stroke(glow.opacity(0.55), lineWidth: 6).blur(radius: 6)
            }
            Image(systemName: p.name == "car" ? "car" : "play.rectangle")
                .font(.system(size: min(28, h * 0.22), weight: .light))
                .foregroundStyle(p.target ? glow : c.icon.opacity(0.6))
        }
        .frame(width: max(0, p.width - 6), height: max(0, h - 6))
        .offset(x: p.x + 3, y: 3)
        .accessibilityLabel(p.name == "car" ? "CarPlay" : "YouTube")
    }

    private func haptic() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
}
#endif
