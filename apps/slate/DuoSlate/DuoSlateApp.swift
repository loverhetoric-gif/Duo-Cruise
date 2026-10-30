#if os(iOS)
import SwiftUI
import UIKit

@main
struct DuoSlateApp: App {
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
    @AppStorage("autoSpeed") private var autoSpeed = true
    @AppStorage("theme") private var theme = 0
    /// 面の色とネオンの配色(昼・夜それぞれ)。設定で選ぶ。
    @AppStorage("dayTone") private var dayTone = PadTone.white.rawValue
    @AppStorage("nightTone") private var nightTone = PadTone.black.rawValue
    @AppStorage("dayNeon") private var dayNeon = NeonPreset.duoNeon.rawValue
    @AppStorage("nightNeon") private var nightNeon = NeonPreset.white.rawValue
    @Environment(\.colorScheme) private var scheme
    @State private var typing = false
    @State private var settings = false
    /// 画面の縮図を出しているか。触らずに 6 秒で消える。
    @State private var mapShown = false
    @State private var mapTouchedAt = Date()
    /// ネオンの発光の強さ(設定。1＝標準)。
    @AppStorage("neonLevel") private var neonLevel = 1.0
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
            .onAppear {
                #if DEBUG
                if ProcessInfo.processInfo.environment["DUO_AUDIT"] == "1" { print("[AUDIT] " + DuoAudit.run()) }
                #endif
            }
    }

    private var connected: Bool {
        #if DEBUG
        // 確認用: 環境変数 DUO_OFFLINE=1 で、つながっていない時の見た目(昼・夜)を出す
        if ProcessInfo.processInfo.environment["DUO_OFFLINE"] == "1" { return false }
        #endif
        if case .connected = link.state { return true }; return false
    }

    /// メニュー操作モードを出すか(つながっていて、Duo Cruise が設定を開いている)。
    private var menuMode: Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["DUO_MENU"] == "1" { return true }
        #endif
        return link.menuOpen && connected
    }

    /// 操作対象が YouTube のとき(フリックで操作)。アプリ名は箱から受けるので固定しない。
    private var youtubeMode: Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["DUO_YT"] == "1" { return true }
        #endif
        return connected && !link.menuOpen && link.targetApp == "YouTube"
    }

    private func content(_ ins: EdgeInsets) -> some View {
        ZStack {
            c.surface
            if menuMode {
                // メニューを開いている間は、タッチパッドに代えてメニュー操作モードへ
                FlickPad(c: c, level: neonLevel, profile: .menu, send: { link.send($0) })
                    .transition(.opacity) // 丸のネオンがふっと浮かぶ演出は FlickPad 側(appear)で
            } else if youtubeMode {
                // 操作対象が YouTube のときは、二本指でなく丸のフリック(上下左右＋決定＋戻る)で
                FlickPad(c: c, level: neonLevel, profile: .youtube, send: { link.send($0) })
                    .transition(.opacity)
            } else {
                Surface(sensitivity: sensitivity, acceleration: acceleration, screen: link.screen, autoTune: autoSpeed,
                        send: { link.send($0) }, onTouch: touched)
                    .transition(.opacity)
            }
            Lights(c: c, night: night, connected: connected, target: link.target,
                   touch: touch, touchGlow: touchGlow, notice: notice, level: neonLevel)
                .allowsHitTesting(false)
            if mapShown && connected && !link.menuOpen {
                LayoutMap(c: c, layout: link.layout, aspect: link.screen.width / max(1, link.screen.height),
                          send: { link.send($0) }, touched: { mapTouchedAt = Date() })
                    .padding(.horizontal, 28)
                    .transition(.opacity.combined(with: .scale(scale: 0.94)))
            }
            VStack {
                // 角の丸みの内側に収める(横向きでは上の余白が 0 になるため、角の丸みから下げる)
                HStack(alignment: .center) { settingLabel; Spacer(); statusLabel }
                    .padding(.top, max(ins.top, Screen.cornerRadius * 0.45))
                    .padding(.trailing, max(ins.trailing, Screen.cornerRadius * 0.45))
                    .padding(.leading, max(ins.leading, Screen.cornerRadius * 0.45))
                Spacer()
                if !menuMode { // メニュー操作モード中は下のツールバーを隠し、操作に集中できるように
                    HStack(spacing: 8) {
                        key("arrow.uturn.backward") { link.send("k b") }
                        key("house") { link.send("k h") }
                        // 押す＝タスク、長押し＝設定メニュー(長押しのあとはタスクを送らない)
                        HoldKey(icon: "square.on.square", c: c, tap: { link.send("k r") }, hold: { link.send("k m") })
                        key(mapShown ? "rectangle.split.2x1.fill" : "rectangle.split.2x1") { toggleMap() }
                        key(typing ? "keyboard.chevron.compact.down" : "keyboard") { typing.toggle() }
                    }
                    .frame(height: 50)
                    .padding(.horizontal, 16)
                    .padding(.bottom, max(ins.bottom, 12) + 4)
                    .transition(.opacity)
                }
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
        .onChange(of: link.turns) { _, _ in
            notice = Notice(kind: .turn, side: link.turnSide, tint: link.turnColor, level: link.turnLevel)
        }
        .sheet(isPresented: $settings) {
            SettingsSheet(sensitivity: $sensitivity, acceleration: $acceleration, theme: $theme,
                          dayTone: $dayTone, nightTone: $nightTone, dayNeon: $dayNeon, nightNeon: $nightNeon,
                          neonLevel: $neonLevel)
        }
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { now in
            #if DEBUG
            // 確認用: 環境変数 DUO_MAP=1 で、画面の縮図を出したままにする
            if ProcessInfo.processInfo.environment["DUO_MAP"] == "1" { if !mapShown { mapShown = true }; return }
            #endif
            if mapShown && now.timeIntervalSince(mapTouchedAt) > 6 {
                withAnimation(.easeOut(duration: 0.3)) { mapShown = false }
            }
        }
    }

    private func toggleMap() {
        mapTouchedAt = Date()
        withAnimation(.spring(response: 0.42, dampingFraction: 0.75)) { mapShown.toggle() }
    }

    private func touched(_ p: CGPoint?) {
        if let p {
            touch = p
            if touchGlow < 1 { withAnimation(.easeOut(duration: 0.15)) { touchGlow = 1 } }
        } else {
            withAnimation(.easeInOut(duration: 0.6)) { touchGlow = 0 }
        }
    }

    /// 左上: 小さな Setting。押すと設定。
    private var settingLabel: some View {
        Button { settings = true } label: {
            Text("Setting")
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
            StatusText(c: c, connected: connected, notice: notice, target: link.targetApp)
                .padding(.vertical, 8).padding(.horizontal, 4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(statusText)
    }

    private var statusText: String {
        switch link.state {
        case .searching: return String(localized: "Duo Cruise を探しています…")
        case .connecting(let n): return String(localized: "\(n) につないでいます…")
        case .connected(let n): return String(localized: "\(n) に接続中・往復 \(String(format: "%.0f", link.rttMs)) ms")
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
    var sensitivity: Double, acceleration: Double, screen: CGSize, autoTune: Bool
    var send: (String) -> Void
    var onTouch: (CGPoint?) -> Void
    func makeUIView(context: Context) -> PadSurface { PadSurface(frame: .zero) }
    func updateUIView(_ v: PadSurface, context: Context) {
        v.sensitivity = sensitivity
        v.acceleration = acceleration
        v.screenWidth = screen.width
        v.screenHeight = screen.height
        v.autoTune = autoTune
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
    @AppStorage("autoSpeed") private var autoSpeed = true
    /// 今の向きのパッドの大きさ(画面全体で近似)。設定に出す自動の値のため。
    private var padSize: CGSize {
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.screen.bounds.size ?? .zero
    }
    @Binding var theme: Int
    @Binding var dayTone: String
    @Binding var nightTone: String
    @Binding var dayNeon: String
    @Binding var nightNeon: String
    @Binding var neonLevel: Double
    @State private var host = UserDefaults.standard.string(forKey: "host") ?? ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("画面に合わせて自動で調整", isOn: $autoSpeed)
                    let tune = CursorTune.auto(link.screen, pad: padSize)
                    VStack(alignment: .leading) {
                        Text("感度 \(String(format: "%.1f", autoSpeed ? tune.sensitivity : sensitivity))")
                        Slider(value: autoSpeed ? .constant(tune.sensitivity) : $sensitivity, in: 0.3...3, step: 0.1)
                    }
                    .disabled(autoSpeed)
                    VStack(alignment: .leading) {
                        Text("加速 \(String(format: "%.1f", autoSpeed ? tune.acceleration : acceleration))(0＝加速なし)")
                        Slider(value: autoSpeed ? .constant(tune.acceleration) : $acceleration, in: 0...CursorTune.maxAccel, step: 0.1)
                    }
                    .disabled(autoSpeed)
                } header: { Text("動きの速さ") } footer: {
                    if autoSpeed { Text("画面の解像度 \(Int(link.screen.width))×\(Int(link.screen.height)) と、iPhone の向きに合わせています") }
                }
                Section("ダークモード") {
                    Picker("ダークモード", selection: $theme) {
                        Text("オン").tag(2); Text("オフ").tag(1); Text("デバイスの設定").tag(0)
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    ToneRow(title: String(localized: "ライト"), tones: PadTone.dayTones, selection: $dayTone)
                    ToneRow(title: String(localized: "ダーク"), tones: PadTone.nightTones, selection: $nightTone)
                } header: { Text("タッチパッドの配色") }
                Section("ネオンの配色") {
                    NeonRow(title: String(localized: "ライト"), dark: false, selection: $dayNeon)
                    NeonRow(title: String(localized: "ダーク"), dark: true, selection: $nightNeon)
                    VStack(alignment: .leading) {
                        Text("発光の強さ \(String(format: "%d%%", Int((neonLevel * 100).rounded())))")
                        Slider(value: $neonLevel, in: 0.4...1.6, step: 0.05)
                    }
                }
                Section {
                    TextField("空欄のままで大丈夫です(例 192.168.43.1)", text: $host)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } header: { Text("接続先の指定(通常は不要)") } footer: {
                    Text("通常は空欄のままにしてください。接続先は自動で見つかります。うまくつながらないときだけ、接続先の IP アドレスを入力します。")
                }
                Section("接続") {
                    Button("再接続") { link.restart() }
                }
                Section {
                    NavigationLink("Duo Slate について") {
                        AboutView().navigationTitle("Duo Slate について").navigationBarTitleDisplayMode(.inline)
                    }
                }
            }
            .navigationTitle("Duo Slate の設定")
            .toolbar { Button("閉じる") { dismiss() } }
            // 下スワイプで閉じたときも接続先を保存する(変わっていなければ、つなぎ直さない)
            .onDisappear { link.manualHost = host.trimmingCharacters(in: .whitespaces) }
        }
    }
}
/// 押すと tap、長押しで hold。長押しのあと指を離しても tap は送らない(タスクと設定メニューが同時に出ていた)。
struct HoldKey: View {
    let icon: String
    let c: Palette
    var tap: () -> Void
    var hold: () -> Void
    @State private var held = false

    var body: some View {
        Button {
            if held { held = false; return }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            tap()
        } label: {
            Image(systemName: icon).font(.system(size: 22, weight: .light))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(KeyStyle(c: c))
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            held = true
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            hold()
        })
    }
}

/// 面の色を選ぶ行: 小さな色見本を並べ、選んだものに輪を付ける。
struct ToneRow: View {
    let title: String
    let tones: [PadTone]
    @Binding var selection: String

    var body: some View {
        HStack(spacing: 14) {
            Text(title).font(.subheadline).lineLimit(1).minimumScaleFactor(0.7).frame(width: 52, alignment: .leading)
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
            Text(title).font(.subheadline).lineLimit(1).minimumScaleFactor(0.7).frame(width: 52, alignment: .leading).padding(.top, 8)
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
