import SwiftUI
#if os(iOS)
import UIKit
#endif

// iPhone と Mac で共通の見た目(配色・知らせる光・文字・ボタンの形)。

/// 昼・夜の配色。面・縁・ボタンはほぼ同じ明るさにして、ボタンが周りに溶け込むようにする。
/// 昼: 明るい面。光は白だと見えないため、ロゴと同じ青で、色を重ねて見せる。
/// 夜: ほぼ黒の面。光は白。眩しくならないよう全体に控えめ。
struct Palette {
    let surface, edge, key, icon, glow: Color
    /// 暗い地か(光は暗い地でだけにじませる)。
    var dark = false
    /// ネオン(縁の光)の配色。設定で選ぶ。
    var neon: NeonPreset = .white

    /// 普段の光の色の流れ。配色のプリセットで決まる。
    var flow: [Color] { neon.colors(dark: dark) }

    /// 設定(面の色・ネオンの配色)から配色を作る。
    static func make(dark: Bool, tone: PadTone, neon: NeonPreset) -> Palette {
        let base = dark ? Palette.night : Palette.day
        return Palette(surface: tone.surface, edge: tone.edge, key: tone.key, icon: base.icon,
                       glow: base.glow, dark: dark, neon: neon)
    }

    /// アイコン(白地版)の色: 朱 → オレンジ → 桃 → 淡い水色 → 空色 → 青。
    static let iconFlow: [Color] = [
        Color(red: 0.94, green: 0.41, blue: 0.29), Color(red: 0.99, green: 0.55, blue: 0.33),
        Color(red: 1.00, green: 0.71, blue: 0.43), Color(red: 0.59, green: 0.82, blue: 1.00),
        Color(red: 0.35, green: 0.63, blue: 0.96), Color(red: 0.16, green: 0.35, blue: 0.82),
    ]
    static let day = Palette(surface: Color(white: 0.95), edge: Color(white: 0.82), key: Color(white: 0.905),
                             icon: Color(white: 0.30), glow: Color(red: 0.16, green: 0.38, blue: 0.94), neon: .duoNeon)
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
/// タッチパッドの面の色(昼・夜それぞれ 3 つ)。最初の 1 つが元の色。
enum PadTone: String, CaseIterable, Identifiable {
    case white, paper, mist      // 昼
    case black, sumi, slate      // 夜
    var id: String { rawValue }

    static let dayTones: [PadTone] = [.white, .paper, .mist]
    static let nightTones: [PadTone] = [.black, .sumi, .slate]

    var name: String {
        switch self {
        case .white: return String(localized: "ホワイト")
        case .paper: return String(localized: "ペーパー")
        case .mist: return String(localized: "ミスト")
        case .black: return String(localized: "ブラック")
        case .sumi: return String(localized: "スミ")
        case .slate: return String(localized: "スレート")
        }
    }
    /// 面の色。
    var surface: Color {
        switch self {
        case .white: return Color(white: 0.95)
        case .paper: return Color(red: 0.915, green: 0.905, blue: 0.885)   // わずかに暖かい灰(紙)
        case .mist: return Color(red: 0.875, green: 0.885, blue: 0.905)    // わずかに青い灰(霧)
        case .black: return Color(white: 0.055)
        case .sumi: return Color(red: 0.075, green: 0.080, blue: 0.095)    // 青みのある深い墨
        case .slate: return Color(red: 0.115, green: 0.120, blue: 0.135)   // 一段明るい石板
        }
    }
    /// 縁の線と、押したときの地(面から少しだけずらす)。
    var edge: Color {
        switch self {
        case .white: return Color(white: 0.82)
        case .paper: return Color(red: 0.80, green: 0.79, blue: 0.77)
        case .mist: return Color(red: 0.76, green: 0.77, blue: 0.80)
        case .black: return Color(white: 0.15)
        case .sumi: return Color(red: 0.17, green: 0.18, blue: 0.20)
        case .slate: return Color(red: 0.21, green: 0.22, blue: 0.24)
        }
    }
    var key: Color {
        switch self {
        case .white: return Color(white: 0.905)
        case .paper: return Color(red: 0.87, green: 0.86, blue: 0.84)
        case .mist: return Color(red: 0.83, green: 0.84, blue: 0.86)
        case .black: return Color(white: 0.09)
        case .sumi: return Color(red: 0.11, green: 0.115, blue: 0.13)
        case .slate: return Color(red: 0.15, green: 0.155, blue: 0.17)
        }
    }
}

/// ネオン(縁の光)の配色のプリセット。
enum NeonPreset: String, CaseIterable, Identifiable {
    case duoNeon, white, ocean, amber
    var id: String { rawValue }

    var name: String {
        switch self {
        case .duoNeon: return "DuoNEON"
        case .white: return String(localized: "ホワイト")
        case .ocean: return String(localized: "オーシャン")
        case .amber: return String(localized: "アンバー")
        }
    }
    /// 縁を巡る色の流れ。昼(明るい地)は色を重ねて見せるので、白は灰青に置き換える。
    func colors(dark: Bool) -> [Color] {
        switch self {
        case .duoNeon:
            return Palette.iconFlow
        case .white:
            let w = dark ? Color.white : Color(red: 0.42, green: 0.46, blue: 0.55)
            return [w, w.opacity(0.75), w, w.opacity(0.85)]
        case .ocean:
            return [Color(red: 0.36, green: 0.86, blue: 0.96), Color(red: 0.20, green: 0.62, blue: 0.98),
                    Color(red: 0.12, green: 0.38, blue: 0.92), Color(red: 0.30, green: 0.78, blue: 0.90)]
        case .amber:
            return [Color(red: 1.00, green: 0.78, blue: 0.40), Color(red: 1.00, green: 0.58, blue: 0.26),
                    Color(red: 0.94, green: 0.42, blue: 0.22), Color(red: 1.00, green: 0.70, blue: 0.36)]
        }
    }
}

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
        // 昼の白い地では、白寄りの淡い色は溶けて見えない。同じ色相の、濃く澄んだ色を使う
        if !c.dark {
            switch kind {
            // 接続完了: アイコンの青(やや紫寄りの落ち着いた青)と分けるため、澄んだ鮮やかな青 1 色
            case .duo: return [rgb(0.00, 0.45, 1.00), rgb(0.00, 0.50, 1.00), rgb(0.00, 0.45, 1.00), rgb(0.05, 0.52, 1.00)]
            case .bluetooth: return [rgb(0.05, 0.62, 0.42), rgb(0.02, 0.55, 0.55), rgb(0.30, 0.66, 0.15), rgb(0.05, 0.70, 0.52)]
            // 切断: アイコンの朱(オレンジ寄り)と分けるため、青みのある深い赤 1 色
            case .lost: return [rgb(0.80, 0.04, 0.22), rgb(0.76, 0.02, 0.26), rgb(0.80, 0.04, 0.22), rgb(0.84, 0.06, 0.24)]
            case .switched: return c.flow
            }
        }
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
        case .lost: return String(localized: "未接続")
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
            let text = active?.label ?? (connected ? "Duo Cruise" : String(localized: "未接続"))
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
                        band(shape, c.flow, spin: t * 8, strength: 0.55 * dim)
                            .mask(sideMask)
                    } else {
                        // 切れている間: うっすら赤が息をする
                        band(shape, Notice(kind: .lost).colors(c), spin: t * 10,
                             strength: (0.22 + 0.12 * sin(t * 1.6)) * dim)
                    }
                    if let p = touch, touchGlow > 0 {
                        band(shape, c.dark ? [c.glow, c.glow] : c.flow, spin: t * 8, strength: 0.6 * touchGlow * dim)
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
                                .drawingGroup()
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
        // 夜は光を「足す」(screen)。昼の白い地では足しても見えないので、色を「重ねる」(multiply)で見せ、少し強める
        let k = c.dark ? 1.0 : 2.0
        // GPU で描くと、太いにじみだけが画面の縁で 2 割ほど弱く出る(CPU で描いていた頃は縁で明るく補われていた)。
        // 1.35 倍で元の見た目に合わせる(時間を止めた同じ瞬間の画面を、直す前と画素で比べて決めた値)
        let wide = 0.38 * 1.35
        return ZStack {
            shape.stroke(soft, lineWidth: 46).gpu().blur(radius: 28).opacity(min(1, wide * strength * k)).blendMode(c.dark ? .screen : .multiply)
            shape.stroke(g, lineWidth: 10).gpu().blur(radius: 7).opacity(min(1, 0.6 * strength * k))
            shape.stroke(g, lineWidth: 1.6).gpu().opacity(min(1, 1.1 * strength * k))
        }
    }
}

extension View {
    /// 回る色(AngularGradient)を GPU で描かせる。
    /// そのままだと iOS は 1 コマごとに CPU で画面全体を塗り直し、アプリ全体が 1 秒 12 コマ程度に落ちる(実機で計測)。
    /// 縁からはみ出す光(太い線の外側)が切れないよう、少し広い板に描く。
    func gpu(margin: CGFloat = 40) -> some View {
        padding(margin).drawingGroup().padding(-margin)
    }
}

