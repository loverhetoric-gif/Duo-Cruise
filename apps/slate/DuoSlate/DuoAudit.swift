#if os(iOS)
import Foundation

/// Duo Slate の縮図(LayoutMap)の総当たり検証。箱の uiaudit と同じ考え方で、
/// あらゆる並べ方で「操作対象が縮図に見えているか」などの約束に反していないかを調べる。
/// DEBUG で環境変数 DUO_AUDIT=1 のとき、起動時に標準出力へ結果を出す。
enum DuoAudit {
    static func run() -> String {
        var pass = 0, fail = 0
        var lines: [String] = []
        let yts = [0, 30, 40, 50, 60, 70, 100]
        for single in [false, true] {
            for swapped in [false, true] {
                for side in ["L", "R"] {
                    for yt in (single ? [50] : yts) {
                        let L = DuoLayout(single: single, swapped: swapped, side: side, yt: yt)
                        let tgtTv = L.targetIsTv
                        let carVis = single ? !tgtTv : yt < 98
                        let tvVis  = single ?  tgtTv : yt > 2
                        let tgtVis = tgtTv ? tvVis : carVis
                        var bad: [String] = []
                        if !tgtVis { bad.append("操作対象(\(tgtTv ? "YouTube" : "CarPlay"))が縮図で見えない") }
                        if !carVis && !tvVis { bad.append("両方見えない") }
                        let st = "\(single ? "1画面" : "2画面") \(swapped ? "反転" : "通常") 操作=\(tgtTv ? "YouTube" : "CarPlay")\(single ? "" : " YT\(yt)%")"
                        if bad.isEmpty { pass += 1 } else { fail += 1; lines.append("‼ \(st) → \(bad.joined(separator: "、"))") }
                    }
                }
            }
        }
        let head = "DuoSlate 縮図の総当たり: \(pass + fail) 通り中 OK \(pass) / 不具合 \(fail)"
        return fail == 0 ? head + "(問題なし)" : head + "\n" + lines.joined(separator: "\n")
    }
}
#endif
