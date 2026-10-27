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
                .preferredColorScheme(.dark)
                .persistentSystemOverlays(.hidden)
                .onAppear { link.start(); UIApplication.shared.isIdleTimerDisabled = true }
                .onChange(of: phase) { _, p in
                    // 前面に戻ったらすぐつなぎ直す。使っている間は画面を消さない
                    if p == .active { link.restart(); UIApplication.shared.isIdleTimerDisabled = true }
                    if p == .background { link.stop() }
                }
        }
    }
}

struct PadScreen: View {
    @EnvironmentObject var link: PadLink
    @AppStorage("sensitivity") private var sensitivity = 1.0
    @AppStorage("acceleration") private var acceleration = 1.0
    @State private var typing = false
    @State private var settings = false

    var body: some View {
        VStack(spacing: 10) {
            status
            Surface(sensitivity: sensitivity, acceleration: acceleration, screenWidth: link.screen.width) { link.send($0) }
                .background(RoundedRectangle(cornerRadius: 22).fill(Color(white: 0.11)))
                .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color(white: 0.22), lineWidth: 1))
            KeyField(active: $typing) { link.send($0) }
                .frame(width: 1, height: 1).opacity(0.01)
            HStack(spacing: 10) {
                key("arrow.uturn.backward", "戻る") { link.send("k b") }
                key("house", "ホーム") { link.send("k h") }
                key("square.on.square", "タスク") { link.send("k r") }
                key(typing ? "keyboard.chevron.compact.down" : "keyboard", "文字") { typing.toggle() }
            }
            .frame(height: 64)
        }
        .padding(12)
        .background(Color.black.ignoresSafeArea())
        .sheet(isPresented: $settings) { SettingsSheet(sensitivity: $sensitivity, acceleration: $acceleration) }
    }

    private var status: some View {
        HStack {
            Circle().fill(dotColor).frame(width: 9, height: 9)
            Text(statusText).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            Spacer()
            Button { settings = true } label: { Image(systemName: "slider.horizontal.3") }
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 6)
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

    private func key(_ icon: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 20, weight: .medium))
                Text(label).font(.caption2)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color(white: 0.16)))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
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
