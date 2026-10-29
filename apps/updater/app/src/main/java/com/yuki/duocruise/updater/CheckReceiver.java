package com.yuki.duocruise.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 今すぐ確認して、新しい版があれば入れる。
 * 新しい版ができた瞬間に CI が送る合図を、Duo Cruise が受けてここへ届ける(リアルタイムの更新)。
 * 送れるのは同じ署名のアプリだけ(マニフェストで権限 LOG を要求)。
 */
public class CheckReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yuki.duocruise.updater.CHECK";
    /** 確かめる間隔(合図の直後、20 秒後、60 秒後)。 */
    private static final long[] RETRY_MS = {0, 20_000, 40_000};

    @Override public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        Remote.init(c);
        UpdLog.init(c);
        PendingResult pr = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean finished = false;
            try {
                UpdLog.add("新しい版の合図を受けました。今すぐ確認します");
                // 公開の直後は、GitHub の一覧に新しい版がまだ出ないことがある(10-29 に 1 回)。
                // 何も入らなければ、少し待って確かめ直す
                int n = 0;
                for (long wait : RETRY_MS) {
                    if (wait > 0) {
                        // 受信の持ち時間は短いので、待つ前に受信は終えておく(確かめ直しはこのスレッドで続ける)
                        if (!finished) { finished = true; pr.finish(); }
                        UpdLog.add("新しい版がまだ見えないので、" + wait / 1000 + " 秒後に確かめ直します");
                        Thread.sleep(wait);
                    }
                    n = UpdateEngine.checkAndInstall(app, false);
                    if (n > 0) break;
                }
                Remote.add("log", "リアルタイム更新: " + UpdateEngine.lastResult + "(開始 " + n + " 件)",
                        BuildConfig.APPLICATION_ID, String.valueOf(BuildConfig.VERSION_CODE), -1, 0);
            } catch (Throwable t) {
                UpdLog.add("今すぐ確認で失敗: " + t);
            } finally {
                if (!finished) pr.finish();
            }
        }, "duo-check-now").start();
    }
}
