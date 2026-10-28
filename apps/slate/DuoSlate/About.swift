import SwiftUI

/// Duo Slate について。起動演出と同じロゴ(色付きのシンボルとロゴ)を、縦に中央でまとめて見せる。
/// 昼は白地・夜は黒地(素材は Assets の昼/夜で切り替わる)。iPhone は設定から、Mac はアプリのメニューから開く。
struct AboutView: View {
    @Environment(\.colorScheme) private var scheme

    private var version: String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "?"
        let b = info?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            VStack(spacing: side * 0.06) {
                Spacer()
                Image("SlateSymbolColor").resizable().scaledToFit()
                    .frame(width: side * 0.42, height: side * 0.42)
                    .accessibilityHidden(true)
                Image("SlateLogoColor").resizable().scaledToFit()
                    .frame(width: side * 0.52)
                    .accessibilityLabel("Duo Slate")
                Text("バージョン \(version)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, side * 0.04)
                Spacer()
                Spacer()
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .background((scheme == .dark ? Color.black : Color.white).ignoresSafeArea())
    }
}
