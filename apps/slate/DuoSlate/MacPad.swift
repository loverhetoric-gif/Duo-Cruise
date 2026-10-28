#if os(macOS)
import SwiftUI
import AppKit

/// Mac の Duo Slate。マウス・トラックパッド・キーボードで OTTO を操作する。
///
/// - 窓をクリックすると、Mac のカーソルを Duo Slate が捕まえる(画面の端で止まらず、動きはすべて OTTO のカーソルへ)
/// - Esc で捕まえるのをやめる。窓から離れた(他のアプリを使った)ときも自動でやめる
/// - クリック＝押す、押したまま動かす＝ドラッグ、2本指＝スクロール、右クリック＝戻る
/// - キーボードで打った文字は、そのまま OTTO へ(Return＝決定、Delete＝1文字消す)
@main
struct DuoSlateMacApp: App {
    @StateObject private var link = PadLink()
    /// Mac が省電力でアプリを眠らせる(App Nap)と、「生きているか」の合図が数秒止まり、
    /// OTTO が切れたとみなして往復 11 秒・つなぎ直し 25 回になっていた(2026-10-27)。動いている間は眠らせない。
    private let awake = ProcessInfo.processInfo.beginActivity(
        options: [.userInitiated, .latencyCritical, .idleSystemSleepDisabled],
        reason: "Duo Slate: タッチパッドとして操作中")

    var body: some Scene {
        WindowGroup("Duo Slate") {
            MacPadScreen(link: link)
                .withSplash()
                .frame(minWidth: 420, minHeight: 300)
                .onAppear { link.start() }
        }
        .windowResizability(.contentMinSize)
        .commands {
            // アプリのメニューの「Duo Slate について」を、起動演出と同じロゴの窓にする
            CommandGroup(replacing: .appInfo) { AboutMenuItem() }
        }
        Window("Duo Slate について", id: "about") {
            AboutView().frame(width: 360, height: 420)
        }
        .windowResizability(.contentSize)
    }
}

private struct AboutMenuItem: View {
    @Environment(\.openWindow) private var openWindow
    var body: some View { Button("Duo Slate について") { openWindow(id: "about") } }
}

struct MacPadScreen: View {
    @ObservedObject var link: PadLink
    @AppStorage("sensitivity") private var sensitivity = 1.0
    @AppStorage("theme") private var theme = 0
    @Environment(\.colorScheme) private var scheme
    @State private var captured = false
    @State private var notice: Notice?
    @State private var settings = false
    @State private var commander = false

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
    private var connected: Bool { if case .connected = link.state { return true }; return false }

    var body: some View {
        ZStack {
            c.surface
            CaptureArea(captured: $captured, sensitivity: sensitivity) { link.send($0) }
            Lights(c: c, night: night, connected: connected, target: link.target,
                   touch: nil, touchGlow: 0, notice: notice)
                .allowsHitTesting(false)
            (captured ? Text("Esc で解除") : Text("クリックで操作開始"))
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(c.icon.opacity(captured ? 0.35 : 0.7))
                .allowsHitTesting(false)
            VStack {
                HStack {
                    Spacer()
                    Button { settings = true } label: {
                        StatusText(c: c, connected: connected, notice: notice).padding(8)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, 6).padding(.trailing, 12)
                Spacer()
                HStack(spacing: 8) {
                    key("arrow.uturn.backward") { link.send("k b") }
                    key("house") { link.send("k h") }
                    key("square.on.square") { link.send("k r") }
                        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in link.send("k m") }) // 長押しで設定メニュー
                    key("line.3.horizontal") { link.send("k m") }      // 設定メニューを開く
                    key("dial.min") { commander.toggle() }             // MAZDA コマンダー(開発用)
                }
                .frame(height: 44)
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
            }
            if commander {
                CommanderPanel(c: c) { link.send($0) } close: { commander = false }
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
        }
        .animation(.easeOut(duration: 0.18), value: commander)
        .preferredColorScheme(theme == 1 ? .light : theme == 2 ? .dark : nil)
        .onChange(of: connected) { was, now in
            if now { notice = Notice(kind: .duo) } else if was { notice = Notice(kind: .lost) }
        }
        .onChange(of: link.bluetoothUps) { _, _ in notice = Notice(kind: .bluetooth) }
        .onChange(of: link.switches) { _, _ in notice = Notice(kind: .switched) }
        .popover(isPresented: $settings) {
            VStack(alignment: .leading, spacing: 10) {
                Text("感度 \(String(format: "%.1f", sensitivity))")
                Slider(value: $sensitivity, in: 0.3...3, step: 0.1).frame(width: 220)
                Picker("ダークモード", selection: $theme) { Text("オン").tag(2); Text("オフ").tag(1); Text("デバイスの設定").tag(0) }
                    .pickerStyle(.segmented)
                Button("再接続") { link.restart() }
            }
            .padding(16)
        }
    }

    private func key(_ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 20, weight: .light))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(KeyStyle(c: c))
    }
}

/// MAZDA コマンダー風の操作盤(開発用)。回す=メニュー移動、押す=決定、倒す=カーソル、周囲ボタン。
/// Mazda Connect のコマンダー: 中央のダイヤル(回す・倒す・押す)＋周囲に Home/Nav/Music/Back/Fav、手前に音量ノブ。
struct CommanderPanel: View {
    let c: Palette
    let send: (String) -> Void
    let close: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea().onTapGesture { close() }
            VStack(spacing: 20) {
                HStack {
                    Text("コマンダー（開発用）").font(.system(size: 13, weight: .medium)).foregroundStyle(c.icon.opacity(0.8))
                    Spacer()
                    Button { close() } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 18)) }
                        .buttonStyle(.plain).foregroundStyle(c.icon.opacity(0.7))
                }
                // 中央ダイヤルと、その周りのハードボタン(Mazda の配置に寄せる)
                ZStack {
                    CommanderDial(c: c, send: send)
                        .frame(width: 210, height: 210)
                    hard("map", "ナビ") {}.offset(x: 150, y: -92)          // Nav/Map(飾り)
                    hard("music.note", "ミュージック") {}.offset(x: 150, y: 0) // Music(飾り)
                    hard("star", "お気に入り") {}.offset(x: 150, y: 92)       // Fav(飾り)
                    hard("arrow.uturn.backward", "戻る") { send("k b") }.offset(x: -150, y: -92) // Back
                    hard("house", "ホーム") { send("k h") }.offset(x: -150, y: 0)                 // Home
                    hard("line.3.horizontal", "メニュー") { send("k m") }.offset(x: -150, y: 92)  // メニュー(開発用の追加)
                }
                .frame(width: 360, height: 230)
                Text("回す=項目移動 / 押す=決定 / 倒す=カーソル").font(.system(size: 11)).foregroundStyle(c.icon.opacity(0.5))
            }
            .padding(26)
            .background(RoundedRectangle(cornerRadius: 22).fill(c.surface).shadow(radius: 24, y: 8))
            .overlay(RoundedRectangle(cornerRadius: 22).stroke(c.edge, lineWidth: 1))
        }
    }

    private func hard(_ icon: String, _ label: LocalizedStringKey, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 16, weight: .regular))
                Text(label).font(.system(size: 9))
            }
            .frame(width: 66, height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(KeyStyle(c: c))
    }
}

/// コマンダーの中央ダイヤル。ドラッグで回す(回した角度ぶん項目移動)、中央タップで決定、4方向チップで倒す。
struct CommanderDial: View {
    let c: Palette
    let send: (String) -> Void
    @State private var lastAngle: Double? = nil
    @State private var accum: Double = 0
    private let stepDeg = 14.0

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height, rr = min(w, h)
            ZStack {
                Circle().fill(c.key)
                    .overlay(Circle().stroke(c.edge, lineWidth: 2))
                Circle().stroke(c.icon.opacity(0.15), lineWidth: 1).padding(10)
                // 4方向(倒す)
                tilt("chevron.up").offset(y: -rr * 0.34).onTapGesture { send("k up") }
                tilt("chevron.down").offset(y: rr * 0.34).onTapGesture { send("k dn") }
                tilt("chevron.left").offset(x: -rr * 0.34).onTapGesture { send("k lf") }
                tilt("chevron.right").offset(x: rr * 0.34).onTapGesture { send("k rt") }
                // 中央(押す=決定)
                Circle().fill(c.surface)
                    .overlay(Circle().stroke(c.edge, lineWidth: 1))
                    .overlay(Text("OK").font(.system(size: 13, weight: .semibold)).foregroundStyle(c.icon))
                    .frame(width: rr * 0.42, height: rr * 0.42)
                    .onTapGesture { send("k c") }
            }
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 2)
                    .onChanged { v in
                        let cx = w / 2, cy = h / 2
                        let ang = atan2(Double(v.location.y) - cy, Double(v.location.x) - cx) * 180 / .pi
                        if let last = lastAngle {
                            var d = ang - last
                            if d > 180 { d -= 360 }; if d < -180 { d += 360 }
                            accum += d
                            while accum >= stepDeg { send("k rot 1"); accum -= stepDeg }
                            while accum <= -stepDeg { send("k rot -1"); accum += stepDeg }
                        }
                        lastAngle = ang
                    }
                    .onEnded { _ in lastAngle = nil; accum = 0 }
            )
        }
    }

    private func tilt(_ icon: String) -> some View {
        Image(systemName: icon).font(.system(size: 15, weight: .semibold))
            .foregroundStyle(c.icon.opacity(0.7))
            .frame(width: 30, height: 30)
            .contentShape(Rectangle())
    }
}

/// マウス・トラックパッド・キーボードを受け取る面。
struct CaptureArea: NSViewRepresentable {
    @Binding var captured: Bool
    var sensitivity: Double
    var active: Bool = true           // false のときはキャプチャしない(コマンダー表示中など)
    var send: (String) -> Void

    func makeNSView(context: Context) -> CaptureView {
        let v = CaptureView()
        v.onCapture = { on in DispatchQueue.main.async { captured = on } }
        return v
    }

    func updateNSView(_ v: CaptureView, context: Context) {
        v.send = send
        v.sensitivity = sensitivity
        v.active = active
        if !active { v.release() }
    }
}

final class CaptureView: NSView {
    var send: (String) -> Void = { _ in }
    var sensitivity = 1.0
    var active = true
    var onCapture: (Bool) -> Void = { _ in }
    private var captured = false
    private var pressed = false, dragging = false
    private var pressMoved: CGFloat = 0
    private var resignObserver: Any?
    /** Mac のマウスは1秒に数百回動きを出すので、約 8ms ぶんずつまとめて送る(小数のまま足すので失わない)。 */
    private var accX = 0.0, accY = 0.0, flushScheduled = false

    override var acceptsFirstResponder: Bool { true }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        window?.acceptsMouseMovedEvents = true
        if let o = resignObserver { NotificationCenter.default.removeObserver(o) }
        // 他のアプリへ移ったら、カーソルを返す
        resignObserver = NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification,
                                                                object: window, queue: .main) { [weak self] _ in
            self?.release()
        }
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseMoved, .activeInKeyWindow, .inVisibleRect],
                                       owner: self, userInfo: nil))
    }

    private func capture() {
        guard !captured else { return }
        captured = true
        window?.makeFirstResponder(self)
        CGAssociateMouseAndMouseCursorPosition(0) // カーソルをその場に止め、動きだけを受け取る
        NSCursor.hide()
        onCapture(true)
    }

    func release() {
        guard captured else { return }
        captured = false
        if dragging { sendNow("u") }
        pressed = false; dragging = false
        CGAssociateMouseAndMouseCursorPosition(1)
        NSCursor.unhide()
        onCapture(false)
    }

    /// Mac のカーソルの動き(加速は macOS が済ませている)を、OTTO の画面の px へ。
    private func move(_ e: NSEvent) {
        accX += Double(e.deltaX) * 1.4 * sensitivity
        accY += Double(e.deltaY) * 1.4 * sensitivity
        guard !flushScheduled else { return }
        flushScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.008) { [weak self] in self?.flush() }
    }

    /** たまった動きを送る。押す・離す などの前にも必ず呼び、順番を守る。 */
    private func flush() {
        flushScheduled = false
        if accX == 0 && accY == 0 { return }
        send(String(format: "m %.2f %.2f", accX, accY))
        accX = 0; accY = 0
    }

    private func sendNow(_ s: String) { flush(); send(s) }

    override func mouseMoved(with e: NSEvent) { if captured { move(e) } }

    override func mouseDown(with e: NSEvent) {
        guard captured else { capture(); return }
        pressed = true; dragging = false; pressMoved = 0
    }

    override func mouseDragged(with e: NSEvent) {
        guard captured else { return }
        if pressed && !dragging {
            pressMoved += abs(e.deltaX) + abs(e.deltaY)
            if pressMoved > 3 { dragging = true; sendNow("d") } // 少し動いたらドラッグ
        }
        move(e)
    }

    override func mouseUp(with e: NSEvent) {
        guard captured, pressed else { return }
        pressed = false
        if dragging { dragging = false; sendNow("u") } else { sendNow("c") }
    }

    override func rightMouseDown(with e: NSEvent) { if captured { sendNow("k b") } }

    /// 2本指のスクロール(マウスのホイールも)。指を動かした向きにページが動く。
    override func scrollWheel(with e: NSEvent) {
        guard captured else { return }
        let k = e.hasPreciseScrollingDeltas ? 1.2 : 12.0
        let dx = Double(e.scrollingDeltaX) * k, dy = Double(e.scrollingDeltaY) * k
        if dx != 0 || dy != 0 { sendNow(String(format: "w %.2f %.2f", dx, dy)) }
        if e.phase == .ended || e.momentumPhase == .ended { sendNow("w end") }
    }

    override func keyDown(with e: NSEvent) {
        switch e.keyCode {
        case 53: release()                // Esc
        case 36, 76: sendNow("e")         // Return
        case 51: sendNow("x")             // Delete
        default:
            guard captured, let s = e.characters, !s.isEmpty,
                  s.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value != 0x7F && !(0xF700...0xF8FF).contains($0.value) })
            else { return }
            sendNow("t " + s)
        }
    }
}
#endif
