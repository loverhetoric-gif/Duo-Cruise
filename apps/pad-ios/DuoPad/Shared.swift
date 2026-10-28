import SwiftUI
#if os(iOS)
import UIKit
#endif

// iPhone と Mac で共通の見た目(配色・知らせる光・文字・ボタンの形)。

/// 昼・夜の配色。面・縁・ボタンはほぼ同じ明るさにして、ボタンが周りに溶け込むようにする。
/// 昼: 明るい面。光は白だと見えないため、柔らかい灰色。
/// 夜: ほぼ黒の面。光は白。眩しくならないよう全体に控えめ。
struct Palette {
    let surface, edge, key, icon, glow: Color
    /// 暗い地か(光は暗い地でだけにじませる)。
    var dark = false
    static let day = Palette(surface: Color(white: 0.95), edge: Color(white: 0.82), key: Color(white: 0.905),
                             icon: Color(white: 0.30), glow: Color(white: 0.42))
    static let night = Palette(surface: Color(white: 0.055), edge: Color(white: 0.15), key: Color(white: 0.09),
                               icon: Color(white: 0.52), glow: .white, dark: true)
    /// 汎用モード(ホーム等)の背景。Duo Cruise の中とひと目で見分けられるグレー。
    static let grey = Palette(surface: Color(white: 0.28), edge: Color(white: 0.42), key: Color(white: 0.34),
                              icon: Color(white: 0.85), glow: .white, dark: true)
}

/// 画面(窓)の角の丸み(pt)。iPhone は公開の方法では読めないため、画面の幅から近い値を選ぶ。Mac の窓は小さめ。
enum Screen {
    @MainActor static var cornerRadius: CGFloat {
        #if os(iOS)
        let w = min(UIScreen.main.bounds.width, UIScreen.main.bounds.height)
        return w >= 430 ? 62 : w >= 400 ? 58 : 50
        #else
        return 16
        #endif
    }
}

/// 下のボタン。枠も地も持たず、アイコンだけが淡く光る(縁の光と同じ「光で見せる」作り)。
/// ・普段: アイコンは光の色で、周りにうっすら光がにじむ(夜)。昼は濃いめの灰で読みやすく
/// ・押した瞬間: 光が強まり、少し沈む。離すとゆっくり戻る
struct KeyStyle: ButtonStyle {
    let c: Palette

    func makeBody(configuration: Configuration) -> some View {
        let down = configuration.isPressed
        let night = c.dark
        configuration.label
            .foregroundStyle(night ? Color.white.opacity(down ? 1 : 0.86) : c.icon.opacity(down ? 1 : 0.9))
            .shadow(color: (night ? Color.white : c.glow).opacity(night ? (down ? 0.85 : 0.45) : (down ? 0.35 : 0)),
                    radius: down ? 12 : 6)
            .scaleEffect(down ? 0.9 : 1)
            .animation(down ? .easeOut(duration: 0.08) : .easeOut(duration: 0.35), value: down)
    }
}

/// 知らせる出来事。色は「完了・切断の合図」で、普段は白。
/// - duo: Duo Cruise とつながった(青)  - bluetooth: CarPlay の Bluetooth がつながった(緑)
/// - lost: 切れた(赤。切れている間はうっすら赤が残る)  - switched: 操作する側を切り替えた(白)
struct Notice: Equatable {
    enum Kind { case duo, bluetooth, lost, switched }
    let kind: Kind
    let at = Date()

    /// Siri のように、近い色を何色か混ぜる(どれも少し白寄り。濃い色は外側の細い線だけに出る)。
    func colors(_ c: Palette) -> [Color] {
        switch kind {
        case .duo: return [rgb(0.42, 0.66, 1.0), rgb(0.62, 0.84, 1.0), rgb(0.50, 0.52, 0.98), rgb(0.62, 0.95, 1.0)]
        case .bluetooth: return [rgb(0.46, 0.93, 0.74), rgb(0.36, 0.82, 0.78), rgb(0.72, 0.94, 0.50), rgb(0.70, 1.0, 0.86)]
        case .lost: return [rgb(1.0, 0.52, 0.48), rgb(1.0, 0.46, 0.62), rgb(1.0, 0.64, 0.48), rgb(1.0, 0.72, 0.74)]
        case .switched: return [c.glow, c.glow.opacity(0.7), c.glow, c.glow.opacity(0.8)]
        }
    }
    var label: String {
        switch kind {
        case .duo: return "Duo Cruise"
        case .bluetooth: return "Bluetooth"
        case .lost: return "未接続"
        case .switched: return "Duo Cruise"
        }
    }
    /// 知らせる長さ(秒)。広がる 0.35 秒 → 縁で流れる → ゆっくり消える。
    static let seconds = 2.4
    private func rgb(_ r: Double, _ g: Double, _ b: Double) -> Color { Color(red: r, green: g, blue: b) }
}

/// 出来事の光の強さ(0〜1)と、画面全体を一瞬染める強さ(0〜1)。文字と縁で同じ値を使う。
enum Envelope {
    static func at(_ e: Double) -> (edge: Double, wash: Double) {
        let d = Notice.seconds
        guard e >= 0, e < d else { return (0, 0) }
        let edge = e < 0.35 ? e / 0.35 : e < 1.3 ? 1 : max(0, 1 - (e - 1.3) / (d - 1.3))
        let wash = e < 0.12 ? e / 0.12 : max(0, 1 - (e - 0.12) / 0.45)  // すぐ染まって、縁へ引いていく
        return (edge, wash)
    }
}

/// つながりの文字。縁の光と同じ色・同じ明るさで動き、出来事のたびにふわっと入れ替わる。
struct StatusText: View {
    let c: Palette
    let connected: Bool
    let notice: Notice?

    var body: some View {
        TimelineView(.animation) { ctx in
            let e = notice.map { ctx.date.timeIntervalSince($0.at) } ?? 99
            let env = Envelope.at(e).edge
            let active = env > 0.02 ? notice : nil
            let base: Color = connected ? c.glow : Notice(kind: .lost).colors(c)[0]
            let color = active?.colors(c)[0] ?? base
            let text = active?.label ?? (connected ? "Duo Cruise" : "未接続")
            let level = active != nil ? 0.55 + 0.45 * env : (connected ? 0.55 : 0.45 + 0.2 * sin(ctx.date.timeIntervalSinceReferenceDate * 1.6))
            Text(text)
                .font(.system(size: 12, weight: .semibold))
                .kerning(0.6)
                .foregroundStyle(color.opacity(level))
                .overlay {
                    if let n = active { // 文字の上を光が流れる
                        let x = -0.3 + 1.6 * min(1, e / 1.3)
                        LinearGradient(colors: [.clear, .white.opacity(0.85), .clear],
                                       startPoint: UnitPoint(x: x - 0.3, y: 0.5), endPoint: UnitPoint(x: x + 0.3, y: 0.5))
                            .mask(Text(n.label).font(.system(size: 12, weight: .semibold)).kerning(0.6))
                    }
                }
                .shadow(color: color.opacity(0.5 * level), radius: 6)
                .id(text)
                .transition(.opacity.combined(with: .scale(scale: 0.94)).combined(with: .offset(y: 3)))
                .animation(.easeInOut(duration: 0.4), value: text)
        }
    }
}

/// 縁と光(Siri の光の作り)。
/// - 外側の細い線だけが色が濃く、内側へは白寄りの淡い光が広くにじむ。色の違う光が縁をゆっくり回る
/// - 普段は白。操作している側の半分を強く、反対側を弱く光らせて、左右を見分ける(1画面は左右同じ)
/// - 触れている間は、指に近い縁がふわっと灯る
/// - 出来事(青・緑・赤・切り替え)は、面全体が一瞬その色に染まり、縁へ引いて流れ、ゆっくり消える
/// - 切れている間は、うっすら赤が息をするように残る
struct Lights: View {
    let c: Palette
    let night: Bool
    let connected: Bool
    let target: String
    let touch: CGPoint?
    let touchGlow: Double
    let notice: Notice?
    static let inset: CGFloat = 4

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let shape = RoundedRectangle(cornerRadius: max(Screen.cornerRadius - Self.inset, 20), style: .continuous)
            TimelineView(.animation) { ctx in
                let t = ctx.date.timeIntervalSinceReferenceDate
                let dim = night ? 0.75 : 1.0
                ZStack {
                    shape.stroke(c.edge, lineWidth: 1)
                    if connected {
                        // 普段: 白い光。操作している側の半分を強く
                        band(shape, [c.glow, c.glow.opacity(0.75), c.glow, c.glow.opacity(0.85)], spin: t * 8, strength: 0.55 * dim)
                            .mask(sideMask)
                    } else {
                        // 切れている間: うっすら赤が息をする
                        band(shape, Notice(kind: .lost).colors(c), spin: t * 10,
                             strength: (0.22 + 0.12 * sin(t * 1.6)) * dim)
                    }
                    if let p = touch, touchGlow > 0 {
                        band(shape, [c.glow, c.glow], spin: 0, strength: 0.6 * touchGlow * dim)
                            .mask(RadialGradient(colors: [.white, .clear],
                                                 center: UnitPoint(x: p.x / max(size.width, 1), y: p.y / max(size.height, 1)),
                                                 startRadius: 0, endRadius: 260))
                    }
                    if let n = notice {
                        let e = ctx.date.timeIntervalSince(n.at)
                        let env = Envelope.at(e)
                        if env.edge > 0 || env.wash > 0 {
                            let cols = n.colors(c)
                            // 面全体が一瞬染まる
                            Rectangle()
                                .fill(AngularGradient(colors: cols + [cols[0]], center: .center, angle: .degrees(e * 60)))
                                .opacity(0.22 * env.wash * dim)
                                .blur(radius: 30)
                            // 縁で流れる(切り替えのときは、操作している側を強く)
                            band(shape, cols, spin: e * 90, strength: env.edge * dim)
                                .mask(n.kind == .switched ? AnyView(sideMask) : AnyView(Color.white))
                        }
                    }
                }
                .padding(Self.inset)
            }
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.6), value: target)
    }

    /// 左右の強さ。操作している側の半分を 1、反対側を弱く。真ん中はなめらかにつなぐ。
    private var sideMask: some View {
        let lo = 0.18
        let (l, r): (Double, Double) = target == "L" ? (1, lo) : target == "R" ? (lo, 1) : (0.7, 0.7)
        return LinearGradient(stops: [
            .init(color: .white.opacity(l), location: 0), .init(color: .white.opacity(l), location: 0.38),
            .init(color: .white.opacity(r), location: 0.62), .init(color: .white.opacity(r), location: 1)],
            startPoint: .leading, endPoint: .trailing)
    }

    /// Siri の光の帯: 内側へ広くにじむ淡い光、中くらいの光、外側の細く濃い線。色の並びがゆっくり回る。
    private func band(_ shape: RoundedRectangle, _ colors: [Color], spin: Double, strength: Double) -> some View {
        let g = AngularGradient(colors: colors + [colors[0]], center: .center, angle: .degrees(spin))
        let soft = AngularGradient(colors: (colors + [colors[0]]).map { $0.opacity(0.85) }, center: .center, angle: .degrees(spin))
        return ZStack {
            shape.stroke(soft, lineWidth: 46).blur(radius: 28).opacity(0.38 * strength).blendMode(.screen)
            shape.stroke(g, lineWidth: 10).blur(radius: 7).opacity(0.6 * strength)
            shape.stroke(g, lineWidth: 1.6).opacity(min(1, 1.1 * strength))
        }
    }
}

