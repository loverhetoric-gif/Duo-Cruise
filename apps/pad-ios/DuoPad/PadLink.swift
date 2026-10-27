import Foundation
import Network
import QuartzCore

/// OTTO の Duo Cruise とのつながり。
///
/// - Bonjour(_duopad._tcp)で探し、見つからなければ手で入れた住所へつなぐ
/// - TCP の遅らせる仕組み(Nagle)を切り、指の動きを1つずつすぐ送る
/// - 1秒ごとに「生きているか」(q 番号)を送り、返事(a 番号)で往復の時間を測る
/// - 切れたら 0.5 秒後に自動でつなぎ直す
/// - 30秒ごとに、往復の時間とつなぎ直した回数を OTTO へ送る(OTTO が記録シートに残す)
@MainActor
final class PadLink: ObservableObject {
    enum State: Equatable { case searching, connecting(String), connected(String), failed(String) }

    @Published private(set) var state: State = .searching
    @Published private(set) var rttMs: Double = 0
    /// OTTO の画面の大きさ(px)。
    @Published private(set) var screen = CGSize(width: 1480, height: 480)
    /// Duo Cruise で操作している側("L"＝左、"R"＝右、"F"＝1画面、""＝まだ分からない)。
    @Published private(set) var target = ""
    /// 操作している側が変わるたびに1つ増える(縁を光らせる合図)。
    @Published private(set) var switches = 0
    /// CarPlay の Bluetooth が iPhone とつながっているか(OTTO が知らせる)。
    @Published private(set) var bluetooth = false
    /// Bluetooth がつながるたびに1つ増える(縁を緑に灯す合図)。
    @Published private(set) var bluetoothUps = 0

    static let port: UInt16 = 47800
    private let queue = DispatchQueue(label: "duopad.link", qos: .userInteractive)
    private var browser: NWBrowser?
    private var conn: NWConnection?
    private var buffer = Data()
    private var pingTimer: Timer?, statsTimer: Timer?
    private var pingSent: [Int: CFTimeInterval] = [:]
    private var pingId = 0
    private var rtts: [Double] = []
    private var lastPong = CACurrentMediaTime()
    private(set) var reconnects = 0
    private var stopped = true

    var manualHost: String {
        get { UserDefaults.standard.string(forKey: "host") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "host"); restart() }
    }

    func start() {
        guard stopped else { return }
        stopped = false
        browse()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.ping() }
        }
        statsTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.sendStats() }
        }
    }

    func stop() {
        stopped = true
        pingTimer?.invalidate(); statsTimer?.invalidate()
        browser?.cancel(); browser = nil
        conn?.cancel(); conn = nil
        state = .searching
    }

    func restart() { stop(); start() }

    /// 前面に戻ったとき: つながっていればそのまま、切れていればつなぎ直す(起動直後に二重につながないように)。
    func resume() {
        if stopped { start(); return }
        if case .connected = state { return }
        restart()
    }

    /// 1行送る(改行はこちらで付ける)。
    func send(_ line: String) {
        guard let c = conn, case .connected = state else { return }
        c.send(content: Data((line + "\n").utf8), completion: .idempotent)
    }

    // MARK: - 探す・つなぐ

    private func browse() {
        state = .searching
        if !manualHost.isEmpty {
            connect(to: .hostPort(host: NWEndpoint.Host(manualHost), port: NWEndpoint.Port(rawValue: Self.port)!), name: manualHost)
            return
        }
        let b = NWBrowser(for: .bonjour(type: "_duopad._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in
                guard let self, self.conn == nil, let r = results.first else { return }
                var name = "Duo Cruise"
                if case let .service(n, _, _, _) = r.endpoint { name = n }
                self.connect(to: r.endpoint, name: name)
            }
        }
        b.start(queue: queue)
        browser = b
    }

    private func connect(to endpoint: NWEndpoint, name: String) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 3
        let params = NWParameters(tls: nil, tcp: tcp)
        params.serviceClass = .interactiveVoice // 無線で優先して送ってもらう
        let c = NWConnection(to: endpoint, using: params)
        conn = c
        state = .connecting(name)
        c.stateUpdateHandler = { [weak self] st in
            Task { @MainActor in self?.onState(st, c, name) }
        }
        c.start(queue: queue)
    }

    private func onState(_ st: NWConnection.State, _ c: NWConnection, _ name: String) {
        guard c === conn else { return }
        switch st {
        case .ready:
            state = .connected(name)
            lastPong = CACurrentMediaTime()
            browser?.cancel(); browser = nil
            send("h 1 \(UIDeviceName.name)")
            receive(c)
        case .failed(let e):
            drop("つながりません: \(e.localizedDescription)")
        case .waiting(let e):
            drop("待っています: \(e.localizedDescription)")
        case .cancelled:
            break
        default:
            break
        }
    }

    private func drop(_ why: String) {
        conn?.cancel(); conn = nil
        guard !stopped else { return }
        state = .failed(why)
        reconnects += 1
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, !self.stopped, self.conn == nil else { return }
            self.browser?.cancel(); self.browser = nil
            self.browse()
        }
    }

    private func receive(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, done, err in
            Task { @MainActor in
                guard let self, c === self.conn else { return }
                if let data { self.buffer.append(data); self.lines() }
                if done || err != nil { self.drop("切れました"); return }
                self.receive(c)
            }
        }
    }

    private func lines() {
        while let nl = buffer.firstIndex(of: 0x0A) {
            let line = String(decoding: buffer[buffer.startIndex..<nl], as: UTF8.self)
            buffer.removeSubrange(buffer.startIndex...nl)
            let p = line.split(separator: " ")
            guard let k = p.first else { continue }
            if k == "a", p.count > 1, let id = Int(p[1]), let t = pingSent.removeValue(forKey: id) {
                lastPong = CACurrentMediaTime()
                let ms = (CACurrentMediaTime() - t) * 1000
                rttMs = ms
                rtts.append(ms)
            } else if k == "s", p.count > 1 {
                let t = String(p[1])
                if !target.isEmpty && t != target { switches += 1 }
                target = t
            } else if k == "b", p.count > 1 {
                let on = p[1] == "1"
                if on && !bluetooth { bluetoothUps += 1 }
                bluetooth = on
            } else if k == "i", p.count > 2, let w = Double(p[1]), let h = Double(p[2]) {
                screen = CGSize(width: w, height: h)
            }
        }
    }

    private func ping() {
        guard case .connected = state else { return }
        if CACurrentMediaTime() - lastPong > 4 { drop("返事がありません"); return } // 4秒返事が無ければつなぎ直す
        pingId += 1
        pingSent[pingId] = CACurrentMediaTime()
        if pingSent.count > 20 { pingSent.removeValue(forKey: pingSent.keys.min()!) }
        send("q \(pingId)")
    }

    private func sendStats() {
        guard !rtts.isEmpty else { return }
        let avg = rtts.reduce(0, +) / Double(rtts.count)
        send(String(format: "r %.1f %.1f %d", avg, rtts.max() ?? 0, reconnects))
        rtts.removeAll()
    }
}

#if canImport(UIKit)
import UIKit
enum UIDeviceName {
    @MainActor static var name: String { UIDevice.current.name.replacingOccurrences(of: " ", with: "_") }
}
#else
import AppKit
enum UIDeviceName {
    @MainActor static var name: String { (Host.current().localizedName ?? "Mac").replacingOccurrences(of: " ", with: "_") }
}
#endif
