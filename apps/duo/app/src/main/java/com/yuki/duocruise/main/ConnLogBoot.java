package com.yuki.duocruise.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接続の記録を、画面を開かなくても始める受け口。
 * ・機器の起動(BOOT_COMPLETED)・本体の入れ替え(MY_PACKAGE_REPLACED。Upbeater の更新の後)で常駐サービスを始める
 * ・電源が切れる直前(SHUTDOWN・REBOOT)は、その場で 1 行書き切る
 */
public final class ConnLogBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        ConnLog.init(c);
        switch (a) {
            case Intent.ACTION_SHUTDOWN:
            case "android.intent.action.REBOOT":
                ConnLogService.shutdown(c, a);
                return;
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                ConnLog.event("sys", "app-updated");
                ConnLogService.start(c, "本体が入れ替わった");
                return;
            default:
                ConnLog.event("sys", "boot-completed", "action", a);
                ConnLogService.start(c, "機器の起動(" + a + ")");
        }
    }
}
