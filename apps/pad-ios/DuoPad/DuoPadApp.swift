#if os(iOS)
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
    @Environment(\.colorScheme) private var scheme
    @State private var typing = false
    @State private var settings = false
    /// 指が触れている場所(画面の座標)と、その光の強さ。
    @State private var touch: CGPoint?
    @State private var touchGlow = 0.0
    /// いま知らせている出来事(青・緑・赤の光)。普段は nil(白)。
    @State private var notice: Notice?

    // 接続中は、モードで色を決める: Duo Cruise の中＝昼夜問わず暗く / ホーム等＝グレー。
    // つながっていない間は、いつもの昼・夜に合わせる。
    private var night: Bool {
        if connected { return true }
        return theme == 2 || (theme == 0 && scheme == .dark)
    }
    private var c: Palette {
        if connected { return link.mode == "generic" ? .grey : .night }
        return (theme == 2 || (theme == 0 && scheme == .dark)) ? .night : .day
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
                HStack { Spacer(); statusLabel }
                    .padding(.top, max(ins.top, Screen.cornerRadius * 0.45))
                    .padding(.trailing, max(ins.trailing, Screen.cornerRadius * 0.45))
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
            .navigationTitle("Duo Slate の設定")
            .toolbar { Button("閉じる") { dismiss() } }
            // 下スワイプで閉じたときも住所を保存する(変わっていなければ、つなぎ直さない)
            .onDisappear { link.manualHost = host.trimmingCharacters(in: .whitespaces) }
        }
    }
}
#endif
