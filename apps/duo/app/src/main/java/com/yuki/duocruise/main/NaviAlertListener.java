package com.yuki.duocruise.main;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Y!カーナビの知らせ(取り締まり・渋滞など)を受けて、Duo Cruise に伝える。通知へのアクセスの許可が要る。
 * 案内中ずっと出ている通知(ongoing)は除く。知らせの中身はまだ分からないので、届いたものは全部記録に残す。
 */
public class NaviAlertListener extends NotificationListenerService {
    static final String NAVI = "jp.co.yahoo.android.apps.navi";

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !NAVI.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        Bundle x = n == null ? null : n.extras;
        String title = x == null ? "" : String.valueOf(x.getCharSequence(Notification.EXTRA_TITLE, ""));
        String text = x == null ? "" : String.valueOf(x.getCharSequence(Notification.EXTRA_TEXT, ""));
        boolean ongoing = sbn.isOngoing();
        DuoLog.log("Y!カーナビの知らせ" + (ongoing ? "(常駐)" : "") + ": " + title + " / " + text);
        if (!ongoing) DuoActivity.naviAlert(title + " " + text);
    }
}
