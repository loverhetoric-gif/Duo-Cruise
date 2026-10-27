import SwiftUI
import UIKit

@main
struct DuoPadApp: App {
    @StateObject private var link = PadLink()
    @Environment(\.scenePhase) private var phase
    /// 配色: 0＝自動(iPhone の昼・夜に合わせる)、1＝昼、2＝夜。
    @AppStorage("theme") private var theme = 0

    var body: some Scene {
        WindowGroup {
            PadScreen(link: link)
                .preferredColorScheme(theme == 1 ? .light : theme == 2 ? .dark : nil)
                .environmentObject(link)
                .onAppear { link.start(); UIApplication.shared.isIdleTimerDisabled = true }
                .onChange(of: phase) { _, p in
                    // 前面に戻ったらすぐつなぎ直す。使っている間は画面を消さない
                    if p == .active { link.resume(); UIApplication.shared.isIdleTimerDisabled = true }
                    if p == .background { link.stop() }
                }
        }
    }
}

/// 昼・夜の配色。面・縁・ボタンはほぼ同じ明るさにして、ボタンが周りに溶け込むようにする。
/// 昼: 明るい面。光は白だと見えないため、柔らかい灰色。
/// 夜: ほぼ黒の面。光は白。眩しくならないよう全体に控えめ。
struct Palette {
    let surface, edge, key, icon, glow: Color
    static let day = Palette(surface: Color(white: 0.95), edge: Color(white: 0.82), key: Color(white: 0.905),
                             icon: Color(white: 0.30), glow: Color(white: 0.42))
    static let night = Palette(surface: Color(white: 0.055), edge: Color(white: 0.15), key: Color(white: 0.09),
                               icon: Color(white: 0.52), glow: .white)
}

/// iPhone の画面の角の丸み(pt)。公開の方法では読めないため、画面の幅から近い値を選ぶ。
enum Screen {
    @MainActor static var cornerRadius: CGFloat {
        let w = min(UIScreen.main.bounds.width, UIScreen.main.bounds.height)
        return w >= 430 ? 62 : w >= 400 ? 58 : 50
    }
}

/// 画面いっぱいのタッチパッド。
/// - 面は画面の端まで使い、時計・ステータスバーはその上に乗る
/// - 画面の縁から少し内側に、画面と同じ曲線の「縁」を描く。光はこの縁に沿って灯る
/// - 下のボタンは角丸の四角だが、面とほぼ同じ色で周りに溶け込む
struct PadScreen: View {
    @ObservedObject var link: PadLink
    @AppStorage("sensitivity") private var sensitivity = 1.0
    @AppStorage("acceleration") private var acceleration = 1.0
    @AppStorage("theme") private var theme = 0
    @Environment(\.colorScheme) private var scheme
    @State private var typing = false
    @State private var settings = false
    /// 指が触れている場所(画面の座標)と、その光の強さ。
    @State private var touch: CGPoint?
    @State private var touchGlow = 0.0
    /// いま知らせている出来事(青・緑・赤の光)。普段は nil(白)。
    @State private var notice: Notice?

    private var night: Bool { theme == 2 || (theme == 0 && scheme == .dark) }
    private var c: Palette { night ? .night : .day }

    var body: some View {
        // 時計・ステータスバーと下のホームバーの高さは GeometryReader で受け取る
        // (描くたびに UIKit の窓から直接読むと、SwiftUI の描き直しが止まるため)
        GeometryReader { geo in content(geo.safeAreaInsets) }
    }

    private var connected: Bool { if case .connected = link.state { return true }; return false }

    private func content(_ ins: EdgeInsets) -> some View {
        ZStack {
            c.surface
            Surface(sensitivity: sensitivity, acceleration: acceleration, screenWidth: link.screen.width,
                    send: { link.send($0) }, onTouch: touched)
            Lights(c: c, night: night, connected: connected, target: link.target,
                   touch: touch, touchGlow: touchGlow, notice: notice)
                .allowsHitTesting(false)
            VStack {
                HStack { Spacer(); statusLabel }
                    .padding(.top, ins.top)
                    .padding(.trailing, 18)
                Spacer()
                HStack(spacing: 8) {
                    key("arrow.uturn.backward") { link.send("k b") }
                    key("house") { link.send("k h") }
                    key("square.on.square") { link.send("k r") }
                    key(typing ? "keyboard.chevron.compact.down" : "keyboard") { typing.toggle() }
                }
                .frame(height: 50)
                .padding(.horizontal, 16)
                .padding(.bottom, max(ins.bottom, 12) + 4)
            }
            KeyField(active: $typing) { link.send($0) }
                .frame(width: 1, height: 1).opacity(0.01)
        }
        .ignoresSafeArea()
        .onChange(of: connected) { was, now in
            // 完了・切断を知らせる: Duo Cruise につながった＝青、切れた＝赤
            if now { notice = Notice(kind: .duo) } else if was { notice = Notice(kind: .lost) }
        }
        .onChange(of: link.bluetoothUps) { _, _ in notice = Notice(kind: .bluetooth) }
        .onChange(of: link.switches) { _, _ in notice = Notice(kind: .switched) }
        .sheet(isPresented: $settings) { SettingsSheet(sensitivity: $sensitivity, acceleration: $acceleration, theme: $theme) }
    }

    private func touched(_ p: CGPoint?) {
        if let p {
            touch = p
            if touchGlow < 1 { withAnimation(.easeOut(duration: 0.15)) { touchGlow = 1 } }
        } else {
            withAnimation(.easeInOut(duration: 0.6)) { touchGlow = 0 }
        }
    }

    /// 右上(ステータスバーのすぐ下): 縁の光と同じ色・同じ動きの文字だけ。押すと設定。
    private var statusLabel: some View {
        Button { settings = true } label: {
            StatusText(c: c, connected: connected, notice: notice)
                .padding(.vertical, 8).padding(.horizontal, 4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(statusText)
    }

    private var statusText: String {
        switch link.state {
        case .searching: return "Duo Cruise を探しています…"
        case .connecting(let n): return "\(n) につないでいます…"
        case .connected(let n): return "\(n) に接続中・往復 \(String(format: "%.0f", link.rttMs)) ms"
        case .failed(let w): return w
        }
    }

    /// 角丸の四角のボタン。塗りは無く、縁と同じ細い線だけ(面に溶け込む)。押している間だけ、ほのかに明るくなる。
    private func key(_ icon: String, _ action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Image(systemName: icon).font(.system(size: 19, weight: .regular))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(KeyStyle(c: c))
    }
}

struct KeyStyle: ButtonStyle {
    let c: Palette
    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        configuration.label
            .foregroundStyle(c.icon.opacity(configuration.isPressed ? 1 : 0.75))
            .background(shape.fill(c.key.opacity(configuration.isPressed ? 1 : 0)))
            .overlay(shape.stroke(c.edge, lineWidth: 1))
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
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

/// UIKit のタッチパッド面を SwiftUI に置く。
struct Surface: UIViewRepresentable {
    var sensitivity: Double, acceleration: Double, screenWidth: Double
    var send: (String) -> Void
    var onTouch: (CGPoint?) -> Void
    func makeUIView(context: Context) -> PadSurface { PadSurface(frame: .zero) }
    func updateUIView(_ v: PadSurface, context: Context) {
        v.sensitivity = sensitivity
        v.acceleration = acceleration
        v.screenWidth = screenWidth
        v.send = send
        v.onTouch = onTouch
    }
}

/// iPhone のキーボードを出すための、見えない入力欄。
/// 確定した文字だけを送る(日本語の変換中は送らない)。空のときの削除は「x」、改行は「e」。
struct KeyField: UIViewRepresentable {
    @Binding var active: Bool
    var send: (String) -> Void

    final class Field: UITextField, UITextFieldDelegate {
        var send: (String) -> Void = { _ in }
        override func deleteBackward() {
            if (text ?? "").isEmpty { send("x") }
            super.deleteBackward()
        }
        func textFieldShouldReturn(_ f: UITextField) -> Bool { flush(); send("e"); return false }
        @objc func changed() { if markedTextRange == nil { flush() } }
        func flush() {
            guard let t = text, !t.isEmpty else { return }
            send("t " + t.replacingOccurrences(of: "\n", with: " "))
            text = ""
        }
    }

    func makeUIView(context: Context) -> Field {
        let f = Field()
        f.delegate = f
        f.autocorrectionType = .no
        f.autocapitalizationType = .none
        f.spellCheckingType = .no
        f.returnKeyType = .search
        f.addTarget(f, action: #selector(Field.changed), for: .editingChanged)
        return f
    }

    func updateUIView(_ f: Field, context: Context) {
        f.send = send
        DispatchQueue.main.async {
            if active && !f.isFirstResponder { f.becomeFirstResponder() }
            if !active && f.isFirstResponder { f.resignFirstResponder() }
        }
    }
}

struct SettingsSheet: View {
    @EnvironmentObject var link: PadLink
    @Binding var sensitivity: Double
    @Binding var acceleration: Double
    @Binding var theme: Int
    @State private var host = UserDefaults.standard.string(forKey: "host") ?? ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("動きの速さ") {
                    VStack(alignment: .leading) {
                        Text("感度 \(String(format: "%.1f", sensitivity))")
                        Slider(value: $sensitivity, in: 0.3...3, step: 0.1)
                    }
                    VStack(alignment: .leading) {
                        Text("加速 \(String(format: "%.1f", acceleration))(0＝加速なし)")
                        Slider(value: $acceleration, in: 0...3, step: 0.1)
                    }
                }
                Section("配色") {
                    Picker("配色", selection: $theme) {
                        Text("自動").tag(0); Text("昼").tag(1); Text("夜").tag(2)
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    TextField("空欄なら自動で探す(例 192.168.43.1)", text: $host)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } header: { Text("OTTO の住所") } footer: { Text("いつもは空欄のままで、車の Wi-Fi で自動で見つけます。") }
                Section("つながり") {
                    Text("つなぎ直した回数 \(link.reconnects)")
                    Button("今すぐつなぎ直す") { link.restart() }
                }
            }
            .navigationTitle("Duo Pad の設定")
            .toolbar { Button("閉じる") { link.manualHost = host.trimmingCharacters(in: .whitespaces); dismiss() } }
        }
    }
}
