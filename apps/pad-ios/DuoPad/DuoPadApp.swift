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
    /// 配色: 0＝自動(iPhone の昼・夜に合わせる)、1＝昼、2＝夜。
    @AppStorage("theme") private var theme = 0
    @Environment(\.colorScheme) private var scheme
    @State private var typing = false
    @State private var settings = false
    /// 指が触れている場所(画面の座標)と、その光の強さ。
    @State private var touch: CGPoint?
    @State private var touchGlow = 0.0
    /// 操作している側を切り替えた時刻(光が縁を流れる演出の始まり)。
    @State private var flashAt: Date?

    private var night: Bool { theme == 2 || (theme == 0 && scheme == .dark) }
    private var c: Palette { night ? .night : .day }

    var body: some View {
        // 時計・ステータスバーと下のホームバーの高さは GeometryReader で受け取る
        // (描くたびに UIKit の窓から直接読むと、SwiftUI の描き直しが止まるため)
        GeometryReader { geo in content(geo.safeAreaInsets) }
    }

    private func content(_ ins: EdgeInsets) -> some View {
        ZStack {
            c.surface
            Surface(sensitivity: sensitivity, acceleration: acceleration, screenWidth: link.screen.width,
                    send: { link.send($0) }, onTouch: touched)
            Lights(c: c, target: link.target, touch: touch, touchGlow: touchGlow, flashAt: flashAt)
                .allowsHitTesting(false)
            VStack {
                HStack { Spacer(); corner }
                    .padding(.top, ins.top)
                    .padding(.trailing, 14)
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
        .onChange(of: link.switches) { _, _ in flashAt = Date() }
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

    /// 右上(ステータスバーのすぐ下): つながりの点(緑＝接続中、黄＝探し中、赤＝切れた)と設定。文字は出さない。
    private var corner: some View {
        Button { settings = true } label: {
            HStack(spacing: 8) {
                Circle().fill(dotColor).frame(width: 7, height: 7).opacity(night ? 0.55 : 0.9)
                Image(systemName: "slider.horizontal.3").font(.system(size: 13, weight: .medium))
            }
            .foregroundStyle(c.icon.opacity(0.6))
            .padding(10)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(statusText)
    }

    private var dotColor: Color {
        switch link.state {
        case .connected: return .green
        case .connecting, .searching: return .yellow
        case .failed: return .red
        }
    }

    private var statusText: String {
        switch link.state {
        case .searching: return "Duo Cruise を探しています…"
        case .connecting(let n): return "\(n) につないでいます…"
        case .connected(let n): return "\(n) に接続中・往復 \(String(format: "%.0f", link.rttMs)) ms"
        case .failed(let w): return w
        }
    }

    /// 角丸の四角のボタン。面とほぼ同じ色で、縁取りはしない(周りに溶け込む)。
    private func key(_ icon: String, _ action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Image(systemName: icon).font(.system(size: 20, weight: .medium))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.key))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(c.icon)
    }
}

/// 縁と光。すべて控えめで柔らかく(太い・ぼかした線を重ねて、にじむように灯す)。
/// - 左右の光量: 操作している側の半分を明るく、反対側を暗く(1画面のときは左右同じ)
/// - 触れている間: 指に近い縁がふわっと灯り、指を追う(Siri のように反応する)
/// - 切り替えた直後: 白い光が縁を一周するように流れて、ゆっくり消える
struct Lights: View {
    let c: Palette
    let target: String
    let touch: CGPoint?
    let touchGlow: Double
    let flashAt: Date?
    static let inset: CGFloat = 5
    static let flashSeconds = 1.6

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let shape = RoundedRectangle(cornerRadius: max(Screen.cornerRadius - Self.inset, 20), style: .continuous)
            ZStack {
                shape.stroke(c.edge, lineWidth: 1)
                soft(shape, sideGradient, strength: 1)
                    .animation(.easeInOut(duration: 0.6), value: target)
                if let t = touch, touchGlow > 0 {
                    soft(shape, RadialGradient(colors: [c.glow.opacity(0.55), c.glow.opacity(0)],
                                               center: UnitPoint(x: t.x / max(size.width, 1), y: t.y / max(size.height, 1)),
                                               startRadius: 0, endRadius: 240), strength: touchGlow)
                }
                if let at = flashAt {
                    TimelineView(.animation) { ctx in
                        let e = ctx.date.timeIntervalSince(at)
                        if e < Self.flashSeconds {
                            let env = e < 0.25 ? e / 0.25 : max(0, 1 - (e - 0.25) / (Self.flashSeconds - 0.25))
                            soft(shape, AngularGradient(gradient: Gradient(colors: [
                                c.glow.opacity(0), c.glow, c.glow.opacity(0.15), c.glow.opacity(0.85), c.glow.opacity(0)]),
                                center: .center, angle: .degrees(e * 260)), strength: env)
                        }
                    }
                }
            }
            .padding(Self.inset)
        }
        .ignoresSafeArea()
    }

    /// 左右の光量。操作している側の半分を明るく。真ん中はなめらかにつなぐ。
    private var sideGradient: LinearGradient {
        let hi = 0.42, lo = 0.07
        let (l, r): (Double, Double) = target == "L" ? (hi, lo) : target == "R" ? (lo, hi) : target == "F" ? (0.25, 0.25) : (0, 0)
        return LinearGradient(stops: [
            .init(color: c.glow.opacity(l), location: 0), .init(color: c.glow.opacity(l), location: 0.4),
            .init(color: c.glow.opacity(r), location: 0.6), .init(color: c.glow.opacity(r), location: 1)],
            startPoint: .leading, endPoint: .trailing)
    }

    /// 柔らかい光: 広くぼかした線・中くらいの線・細い芯を重ねる。
    private func soft<S: ShapeStyle>(_ shape: RoundedRectangle, _ style: S, strength: Double) -> some View {
        ZStack {
            shape.stroke(style, lineWidth: 16).blur(radius: 14).opacity(0.55 * strength)
            shape.stroke(style, lineWidth: 5).blur(radius: 4).opacity(0.7 * strength)
            shape.stroke(style, lineWidth: 1.2).opacity(strength)
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
