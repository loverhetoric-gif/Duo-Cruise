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

    @Override public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        Remote.init(c);
        UpdLog.init(c);
        PendingResult pr = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                UpdLog.add("新しい版の合図を受けました。今すぐ確認します");
                int n = UpdateEngine.checkAndInstall(app, false);
                Remote.add("log", "リアルタイム更新: " + UpdateEngine.lastResult + "(開始 " + n + " 件)",
                        BuildConfig.APPLICATION_ID, String.valueOf(BuildConfig.VERSION_CODE), -1, 0);
            } catch (Throwable t) {
                UpdLog.add("今すぐ確認で失敗: " + t);
            } finally {
                pr.finish();
            }
        }, "duo-check-now").start();
    }
}
