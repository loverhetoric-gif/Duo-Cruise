import SwiftUI

/// 起動演出(Duo Slate)。Duo Cruise と同じ作りを、約 1 秒・音なしに縮めたもの。
///
/// ・昼は白地、夜は黒地(OS のダーク表示に従う。素材は Assets の昼/夜で切り替わる)
/// ・一色版と色付き版を同じ位置・同じ大きさで重ね、一色版を上に置く
///   色付き版: 0.10〜0.55 秒で現れる / 一色版: 0.35〜0.80 秒で消える / 0.85 秒から 0.25 秒で画面へ
/// ・縦向きは「上にシンボル、下にロゴ」、横向きは「左にシンボル、右にロゴ」。どちらも中央にまとめる
/// ・「視差効果を減らす」がオンなら動かさず、色付き版をそのまま短く見せる
struct Splash: View {
    let done: () -> Void
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var still
    @State private var color = 0.0
    @State private var mono = 1.0
    @State private var shown = 1.0

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height
            ZStack {
                (scheme == .dark ? Color.black : Color.white).ignoresSafeArea()
                if wide {
                    HStack(spacing: geo.size.height * 0.08) {
                        mark(size: geo.size.height * 0.42)
                        logo(width: geo.size.height * 0.62)
                    }
                } else {
                    VStack(spacing: geo.size.height * 0.05) {
                        mark(size: geo.size.width * 0.62)
                        logo(width: geo.size.width * 0.70)
                    }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .ignoresSafeArea()
        .opacity(shown)
        .allowsHitTesting(shown > 0.01)
        .task { await run() }
    }

    /// シンボル: 色付きの上に一色を重ねる。
    private func mark(size: CGFloat) -> some View {
        ZStack {
            Image("SlateSymbolColor").resizable().scaledToFit().opacity(color)
            Image("SlateSymbolMono").resizable().scaledToFit().opacity(mono)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    /// ロゴ: 同じく色付きの上に一色を重ねる。
    private func logo(width: CGFloat) -> some View {
        ZStack {
            Image("SlateLogoColor").resizable().scaledToFit().opacity(color)
            Image("SlateLogoMono").resizable().scaledToFit().opacity(mono)
        }
        .frame(width: width)
        .accessibilityLabel("Duo Slate")
    }

    private func run() async {
        if still {
            color = 1; mono = 0
            try? await Task.sleep(for: .milliseconds(600))
        } else {
            try? await Task.sleep(for: .milliseconds(100))
            withAnimation(.easeInOut(duration: 0.45)) { color = 1 }
            try? await Task.sleep(for: .milliseconds(250))
            withAnimation(.easeInOut(duration: 0.45)) { mono = 0 }
            try? await Task.sleep(for: .milliseconds(500))
        }
        withAnimation(.easeOut(duration: 0.25)) { shown = 0 }
        try? await Task.sleep(for: .milliseconds(260))
        done()
    }
}

/// 起動時に一度だけ、画面の上に起動演出を重ねる。
struct WithSplash: ViewModifier {
    @State private var showing = true

    func body(content: Content) -> some View {
        content.overlay {
            if showing { Splash { showing = false } }
        }
    }
}

extension View {
    func withSplash() -> some View { modifier(WithSplash()) }
}
