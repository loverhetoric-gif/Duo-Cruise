import SwiftUI
import UIKit

@main
struct DuoPadApp: App {
    @StateObject private var link = PadLink()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            PadScreen()
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

/// 昼・夜の配色。
/// 昼: 日差しの下でも読めるよう、明るい面に濃い記号(強いコントラスト)。
/// 夜: 車内で眩しくないよう、ほぼ黒の面に暗めの記号(明るさを抑え、運転の邪魔をしない)。
struct Palette {
    let bg, pad, padEdge, key, icon, glow: Color
    /// 昼の光は白だと明るい面で見えないため、柔らかい灰色にする。
    static let day = Palette(bg: Color(white: 0.86), pad: Color(white: 0.97), padEdge: Color(white: 0.70),
                             key: Color(white: 0.78), icon: Color(white: 0.08), glow: Color(white: 0.45))
    static let night = Palette(bg: .black, pad: Color(white: 0.07), padEdge: Color(white: 0.16),
                               key: Color(white: 0.12), icon: Color(white: 0.55), glow: .white)
}

/// iPhone の画面の角の丸み(pt)。面は画面の縁から 6pt 内側にあるので、その分だけ小さくすると同じ曲線に見える。
/// 公開の方法では読めないため、画面の幅から近い値を選ぶ(App Store に出せない非公開の方法は使わない)。
enum Screen {
    @MainActor static var cornerRadius: CGFloat {
        let w = min(UIScreen.main.bounds.width, UIScreen.main.bounds.height)
        return w >= 430 ? 62 : w >= 400 ? 58 : 50
    }
}

struct PadScreen: View {
    @EnvironmentObject var link: PadLink
    @AppStorage("sensitivity") private var sensitivity = 1.0
    @AppStorage("acceleration") private var acceleration = 1.0
    /// 配色: 0＝自動(iPhone の昼・夜に合わせる)、1＝昼、2＝夜。
    @AppStorage("theme") private var theme = 0
    @Environment(\.colorScheme) private var scheme
    @State private var typing = false
    @State private var settings = false
    /// 縁の光の強さ(0〜1)。操作している側が変わった直後だけ、ふわっと灯って消える。
    @State private var glow = 0.0
    private var radius: CGFloat { max(Screen.cornerRadius - 6, 20) }

    private var night: Bool { theme == 2 || (theme == 0 && scheme == .dark) }
    private var c: Palette { night ? .night : .day }

    var body: some View {
        VStack(spacing: 6) {
            Surface(sensitivity: sensitivity, acceleration: acceleration, screenWidth: link.screen.width) { link.send($0) }
                .background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(c.pad))
                .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(c.padEdge, lineWidth: 1))
                .overlay { sideMark.allowsHitTesting(false) }
                .overlay { edgeGlow.allowsHitTesting(false) }
                .overlay(alignment: .topTrailing) { corner.padding(.top, topInset) }
                .onChange(of: link.switches) { _, _ in flash() }
            KeyField(active: $typing) { link.send($0) }
                .frame(width: 1, height: 1).opacity(0.01)
            HStack(spacing: 6) {
                key("arrow.uturn.backward") { link.send("k b") }
                key("house") { link.send("k h") }
                key("square.on.square") { link.send("k r") }
                key(typing ? "keyboard.chevron.compact.down" : "keyboard") { typing.toggle() }
            }
            .frame(height: 50)
        }
        .padding(.horizontal, 6)
        .padding(.top, 6)    // 時計・ステータスバーは残し、その下まで面を伸ばして重ねる
        .padding(.bottom, 4) // 下は伸ばさない(iPhone のホームに戻る操作とぶつかるため)
        .ignoresSafeArea(.container, edges: .top)
        .background(c.bg.ignoresSafeArea())
        .preferredColorScheme(theme == 1 ? .light : theme == 2 ? .dark : nil)
        .sheet(isPresented: $settings) { SettingsSheet(sensitivity: $sensitivity, acceleration: $acceleration, theme: $theme) }
    }

    /// 操作している側の印: その側の縁だけが、うっすら白く灯る(1画面のときは全体をごく薄く)。
    private var sideMark: some View {
        let t = link.target
        let stops: [Gradient.Stop]
        switch t {
        case "L": stops = [.init(color: c.glow.opacity(0.42), location: 0), .init(color: c.glow.opacity(0), location: 0.42)]
        case "R": stops = [.init(color: c.glow.opacity(0), location: 0.58), .init(color: c.glow.opacity(0.42), location: 1)]
        case "F": stops = [.init(color: c.glow.opacity(0.16), location: 0), .init(color: c.glow.opacity(0.16), location: 1)]
        default: stops = [.init(color: .clear, location: 0), .init(color: .clear, location: 1)]
        }
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        let grad = LinearGradient(stops: stops, startPoint: .leading, endPoint: .trailing)
        return ZStack {
            shape.stroke(grad, lineWidth: 5).blur(radius: 5)   // 柔らかいにじみ
            shape.stroke(grad, lineWidth: 1.2)                 // 細い芯
        }
        .animation(.easeInOut(duration: 0.5), value: t)
    }

    /// 切り替えた直後の縁の光: 縁全体を白い光がふわっと囲って、ゆっくり消える(Duo Cruise の虹色の光の白版)。
    private var edgeGlow: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        return ZStack {
            shape.stroke(c.glow.opacity(0.55 * glow), lineWidth: 8).blur(radius: 8)
            shape.stroke(c.glow.opacity(0.75 * glow), lineWidth: 1.5)
        }
    }

    private func flash() {
        withAnimation(.easeOut(duration: 0.2)) { glow = 1 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            withAnimation(.easeInOut(duration: 0.9)) { glow = 0 }
        }
    }

    /// 時計・ステータスバーの高さ。設定ボタンはその下(右上)に置く。
    private var topInset: CGFloat {
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.keyWindow?.safeAreaInsets.top ?? 44
    }

    /// 右上の隅(ステータスバーのすぐ下): つながりの点(緑＝接続中、黄＝探し中、赤＝切れた)と設定。文字は出さない。
    private var corner: some View {
        Button { settings = true } label: {
            HStack(spacing: 8) {
                Circle().fill(dotColor).frame(width: 8, height: 8).opacity(night ? 0.55 : 1)
                Image(systemName: "slider.horizontal.3").font(.system(size: 13, weight: .medium))
            }
            .foregroundStyle(c.icon.opacity(0.7))
            .padding(12)
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

    private func key(_ icon: String, _ action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Image(systemName: icon).font(.system(size: 21, weight: .semibold))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(RoundedRectangle(cornerRadius: 16).fill(c.key))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(c.icon)
    }
}

/// UIKit のタッチパッド面を SwiftUI に置く。
struct Surface: UIViewRepresentable {
    var sensitivity: Double, acceleration: Double, screenWidth: Double
    var send: (String) -> Void
    func makeUIView(context: Context) -> PadSurface { PadSurface(frame: .zero) }
    func updateUIView(_ v: PadSurface, context: Context) {
        v.sensitivity = sensitivity
        v.acceleration = acceleration
        v.screenWidth = screenWidth
        v.send = send
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
