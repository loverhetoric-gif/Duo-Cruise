#if os(iOS)
import SwiftUI
import UIKit

@main
struct DuoPadApp: App {
    @StateObject private var link = PadLink()
    @Environment(\.scenePhase) private var phase
    /// ダークモード: 0＝デバイスの設定に合わせる、1＝オフ(ライト)、2＝オン(ダーク)。
    @AppStorage("theme") private var theme = 0

    var body: some Scene {
        WindowGroup {
            PadScreen(link: link)
                .withSplash()
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

struct PadScreen: View {
    @ObservedObject var link: PadLink
    @AppStorage("sensitivity") private var sensitivity = 1.0
    @AppStorage("acceleration") private var acceleration = 1.0
    @AppStorage("theme") private var theme = 0
    /// 面の色とネオンの配色(昼・夜それぞれ)。設定で選ぶ。
    @AppStorage("dayTone") private var dayTone = PadTone.white.rawValue
    @AppStorage("nightTone") private var nightTone = PadTone.black.rawValue
    @AppStorage("dayNeon") private var dayNeon = NeonPreset.duoNeon.rawValue
    @AppStorage("nightNeon") private var nightNeon = NeonPreset.white.rawValue
    @Environment(\.colorScheme) private var scheme
    @State private var typing = false
    @State private var settings = false
    /// 指が触れている場所(画面の座標)と、その光の強さ。
    @State private var touch: CGPoint?
    @State private var touchGlow = 0.0
    /// いま知らせている出来事(青・緑・赤の光)。普段は nil(白)。
    @State private var notice: Notice?

    // 配色は設定のダークモード(オン/オフ/デバイスの設定)に従う。ホーム等を操作中だけはグレー。
    private var night: Bool {
        return theme == 2 || (theme == 0 && scheme == .dark)
    }
    private var c: Palette {
        let dayPal = Palette.make(dark: false, tone: PadTone(rawValue: dayTone) ?? .white, neon: NeonPreset(rawValue: dayNeon) ?? .duoNeon)
        let nightPal = Palette.make(dark: true, tone: PadTone(rawValue: nightTone) ?? .black, neon: NeonPreset(rawValue: nightNeon) ?? .white)
        #if DEBUG
        // 確認用: 環境変数 DUO_DAY=1 で、つながっていても昼の配色にする
        if ProcessInfo.processInfo.environment["DUO_DAY"] == "1" { return dayPal }
        #endif
        if connected && link.mode == "generic" { return .grey }
        return (theme == 2 || (theme == 0 && scheme == .dark)) ? nightPal : dayPal
    }

    var body: some View {
        // 時計・ステータスバーと下のホームバーの高さは GeometryReader で受け取る
        // (描くたびに UIKit の窓から直接読むと、SwiftUI の描き直しが止まるため)
        GeometryReader { geo in content(geo.safeAreaInsets) }
    }

    private var connected: Bool {
        #if DEBUG
        // 確認用: 環境変数 DUO_OFFLINE=1 で、つながっていない時の見た目(昼・夜)を出す
        if ProcessInfo.processInfo.environment["DUO_OFFLINE"] == "1" { return false }
        #endif
        if case .connected = link.state { return true }; return false
    }

    private func content(_ ins: EdgeInsets) -> some View {
        ZStack {
            c.surface
            Surface(sensitivity: sensitivity, acceleration: acceleration, screenWidth: link.screen.width,
                    send: { link.send($0) }, onTouch: touched)
            Lights(c: c, night: night, connected: connected, target: link.target,
                   touch: touch, touchGlow: touchGlow, notice: notice)
                .allowsHitTesting(false)
            VStack {
                // 角の丸みの内側に収める(横向きでは上の余白が 0 になるため、角の丸みから下げる)
                HStack(alignment: .center) { settingLabel; Spacer(); statusLabel }
                    .padding(.top, max(ins.top, Screen.cornerRadius * 0.45))
                    .padding(.trailing, max(ins.trailing, Screen.cornerRadius * 0.45))
                    .padding(.leading, max(ins.leading, Screen.cornerRadius * 0.45))
                Spacer()
                HStack(spacing: 8) {
                    key("arrow.uturn.backward") { link.send("k b") }
                    key("house") { link.send("k h") }
                    key("square.on.square") { link.send("k r") }
                        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in link.send("k m") }) // 長押しで設定メニュー
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
        .sheet(isPresented: $settings) {
            SettingsSheet(sensitivity: $sensitivity, acceleration: $acceleration, theme: $theme,
                          dayTone: $dayTone, nightTone: $nightTone, dayNeon: $dayNeon, nightNeon: $nightNeon)
        }
    }

    private func touched(_ p: CGPoint?) {
        if let p {
            touch = p
            if touchGlow < 1 { withAnimation(.easeOut(duration: 0.15)) { touchGlow = 1 } }
        } else {
            withAnimation(.easeInOut(duration: 0.6)) { touchGlow = 0 }
        }
    }

    /// 左上: 小さな SETTING。押すと設定。
    private var settingLabel: some View {
        Button { settings = true } label: {
            Text("SETTING")
                .font(.system(size: 11, weight: .semibold))
                .kerning(1.6)
                .foregroundStyle(c.icon.opacity(c.dark ? 0.9 : 0.85))
                .padding(.vertical, 8).padding(.horizontal, 4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("設定")
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
            Image(systemName: icon).font(.system(size: 22, weight: .light))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(KeyStyle(c: c))
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
    @Binding var dayTone: String
    @Binding var nightTone: String
    @Binding var dayNeon: String
    @Binding var nightNeon: String
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
                Section("ダークモード") {
                    Picker("ダークモード", selection: $theme) {
                        Text("オン").tag(2); Text("オフ").tag(1); Text("デバイスの設定").tag(0)
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    ToneRow(title: "ライト", tones: PadTone.dayTones, selection: $dayTone)
                    ToneRow(title: "ダーク", tones: PadTone.nightTones, selection: $nightTone)
                } header: { Text("タッチパッドの配色") }
                Section("ネオンの配色") {
                    NeonRow(title: "ライト", dark: false, selection: $dayNeon)
                    NeonRow(title: "ダーク", dark: true, selection: $nightNeon)
                }
                Section {
                    TextField("空欄のままで大丈夫です(例 192.168.43.1)", text: $host)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } header: { Text("接続先の指定(通常は不要)") } footer: {
                    Text("通常は空欄のままにしてください。車の Wi-Fi から OTTO を自動で見つけます。うまくつながらないときだけ、OTTO の IP アドレスを入力します。")
                }
                Section("接続") {
                    Button("OTTO に接続し直す") { link.restart() }
                }
            }
            .navigationTitle("Duo Slate の設定")
            .toolbar { Button("閉じる") { dismiss() } }
            // 下スワイプで閉じたときも接続先を保存する(変わっていなければ、つなぎ直さない)
            .onDisappear { link.manualHost = host.trimmingCharacters(in: .whitespaces) }
        }
    }
}
/// 面の色を選ぶ行: 小さな色見本を並べ、選んだものに輪を付ける。
struct ToneRow: View {
    let title: String
    let tones: [PadTone]
    @Binding var selection: String

    var body: some View {
        HStack(spacing: 14) {
            Text(title).font(.subheadline).frame(width: 52, alignment: .leading)
            ForEach(tones) { t in
                Button { selection = t.rawValue } label: {
                    VStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .fill(t.surface)
                            .frame(width: 54, height: 38)
                            .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .stroke(selection == t.rawValue ? Color.accentColor : Color.secondary.opacity(0.3),
                                        lineWidth: selection == t.rawValue ? 2.5 : 1))
                        Text(t.name).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title)の面 \(t.name)")
                .accessibilityAddTraits(selection == t.rawValue ? .isSelected : [])
            }
        }
        .padding(.vertical, 4)
    }
}

/// ネオンの配色を選ぶ行: 色の流れを細い帯で見せる。
struct NeonRow: View {
    let title: String
    let dark: Bool
    @Binding var selection: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(title).font(.subheadline).frame(width: 52, alignment: .leading).padding(.top, 8)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                ForEach(NeonPreset.allCases) { n in
                    Button { selection = n.rawValue } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(dark ? Color(white: 0.07) : Color(white: 0.95))
                                .frame(height: 30)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .strokeBorder(LinearGradient(colors: n.colors(dark: dark), startPoint: .leading, endPoint: .trailing), lineWidth: 3)
                                        .blur(radius: 1.5)
                                )
                                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .stroke(selection == n.rawValue ? Color.accentColor : .clear, lineWidth: 2.5).padding(-3))
                            Text(n.name).font(.caption).foregroundStyle(selection == n.rawValue ? .primary : .secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(title)のネオン \(n.name)")
                    .accessibilityAddTraits(selection == n.rawValue ? .isSelected : [])
                }
            }
        }
        .padding(.vertical, 4)
    }
}
#endif
