package com.yuki.duocruise.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.yuki.duocruise.carplay.MfiProbe;

import java.util.List;

/**
 * 記録シートの「指示」の受け口。更新アプリがブロードキャスト com.yuki.duocruise.COMMAND(extra command)で届ける。
 * 送れるのは同じ署名のアプリだけ(マニフェストで com.yuki.duocruise.permission.LOG を要求)。結果は kind=result で返す。
 *
 * 指示(先頭の「com.yuki.duocruise.main:」は有っても無くてもよい):
 *   state / log / keys / swap / side / panel / keys-reset / car-stop / car-wifi-reset / car-maps / car-input 0〜3 / car-knob basic|full / car-hidid split|same / car-areas 幅_幅…|off / car-area N|auto / car-areatest
 *   tv-…       … D の TvPane.command() へ渡す
 *   mfi-probe  … 受けない(チップに触れる指示は遠くからは実行しない)
 */
public class CommandReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yuki.duocruise.COMMAND";
    static final String PREFIX = "com.yuki.duocruise.main:";

    @Override public void onReceive(Context c, Intent i) {
        DuoLog.init(c);
        if (!ACTION.equals(i.getAction())) return;
        String raw = i.getStringExtra("command");
        String cmd = normalize(raw);
        if (cmd.isEmpty()) { DuoLog.result("指示が空です"); return; }
        DuoLog.log("指示を受けました: " + cmd);

        // チップに触れる指示は、遠くからは受けない(本人の承認と、OTTO の画面での操作が要る)
        if (cmd.equals(MfiProbe.COMMAND)) {
            DuoLog.result("mfi-probe: 遠くからの指示では実行しません(チップに触れるため)");
            return;
        }
        if (cmd.equals("log")) { DuoLog.result("log: " + recent(30)); return; }
        if (cmd.equals("keys")) {
            KeyMap m = Store.keyMap(c);
            StringBuilder b = new StringBuilder("keys:");
            for (KeyMap.Action a : KeyMap.Action.values()) b.append(' ').append(a.label()).append('=').append(m.describe(a)).append(';');
            b.append(" 文字列=").append(m.serialize()).append(" 長押し=").append(Store.longMs(c)).append("ms");
            DuoLog.result(b.toString());
            return;
        }

        // 画面に関わる指示は、画面のスレッドで
        PendingResult pr = goAsync();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                DuoActivity a = DuoActivity.live;
                String r;
                if (a == null) r = cmd.equals("state") ? "画面は閉じています(" + (Store.swapped(c) ? "入れ替え中" : "通常の配置") + ")" : "画面が開いていないため「" + cmd + "」はできません";
                else {
                    r = a.command(cmd);
                    if (r == null) r = "知らない指示です。使えるもの: state / log / keys / swap / side / panel / keys-reset / car-stop / car-wifi-reset / car-maps / car-input 0〜3 / car-knob basic|full / car-hidid split|same / car-areas 幅_幅…|off / car-area N|auto / car-areatest / tv-…";
                }
                DuoLog.result(cmd + ": " + r);
            } catch (Throwable t) {
                DuoLog.error("指示「" + cmd + "」で失敗: " + DuoLog.why(t));
            } finally {
                pr.finish();
            }
        });
    }

    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith(PREFIX)) s = s.substring(PREFIX.length()).trim();
        return s;
    }

    private static String recent(int n) {
        List<String> all = DuoLog.snapshot();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, all.size()); i++) b.append(all.get(i)).append('\n');
        return b.length() == 0 ? "記録なし" : b.toString();
    }
}
