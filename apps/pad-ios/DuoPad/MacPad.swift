#if os(macOS)
import SwiftUI
import AppKit

/// Mac の Duo Pad。マウス・トラックパッド・キーボードで OTTO を操作する。
///
/// - 窓をクリックすると、Mac のカーソルを Duo Pad が捕まえる(画面の端で止まらず、動きはすべて OTTO のカーソルへ)
/// - Esc で捕まえるのをやめる。窓から離れた(他のアプリを使った)ときも自動でやめる
/// - クリック＝押す、押したまま動かす＝ドラッグ、2本指＝スクロール、右クリック＝戻る
/// - キーボードで打った文字は、そのまま OTTO へ(Return＝決定、Delete＝1文字消す)
@main
struct DuoPadMacApp: App {
    @StateObject private var link = PadLink()

    var body: some Scene {
        WindowGroup("Duo Pad") {
            MacPadScreen(link: link)
                .frame(minWidth: 420, minHeight: 300)
                .onAppear { link.start() }
        }
        .windowResizability(.contentMinSize)
    }
}

struct MacPadScreen: View {
    @ObservedObject var link: PadLink
    @AppStorage("sensitivity") private var sensitivity = 1.0
    @AppStorage("theme") private var theme = 0
    @Environment(\.colorScheme) private var scheme
    @State private var captured = false
    @State private var notice: Notice?
    @State private var settings = false

    private var night: Bool { theme == 2 || (theme == 0 && scheme == .dark) }
    private var c: Palette { night ? .night : .day }
    private var connected: Bool { if case .connected = link.state { return true }; return false }

    var body: some View {
        ZStack {
            c.surface
            CaptureArea(captured: $captured, sensitivity: sensitivity) { link.send($0) }
            Lights(c: c, night: night, connected: connected, target: link.target,
                   touch: nil, touchGlow: 0, notice: notice)
                .allowsHitTesting(false)
            Text(captured ? "Esc で解除" : "クリックで操作開始")
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
                }
                .frame(height: 44)
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
            }
        }
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
                Picker("配色", selection: $theme) { Text("自動").tag(0); Text("昼").tag(1); Text("夜").tag(2) }
                    .pickerStyle(.segmented)
                Button("今すぐつなぎ直す") { link.restart() }
            }
            .padding(16)
        }
    }

    private func key(_ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 17, weight: .regular))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(KeyStyle(c: c))
    }
}

/// マウス・トラックパッド・キーボードを受け取る面。
struct CaptureArea: NSViewRepresentable {
    @Binding var captured: Bool
    var sensitivity: Double
    var send: (String) -> Void

    func makeNSView(context: Context) -> CaptureView {
        let v = CaptureView()
        v.onCapture = { on in DispatchQueue.main.async { captured = on } }
        return v
    }

    func updateNSView(_ v: CaptureView, context: Context) {
        v.send = send
        v.sensitivity = sensitivity
    }
}

final class CaptureView: NSView {
    var send: (String) -> Void = { _ in }
    var sensitivity = 1.0
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
