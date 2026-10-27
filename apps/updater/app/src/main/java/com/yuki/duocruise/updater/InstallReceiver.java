package com.yuki.duocruise.updater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        UpdLog.init(c);
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String label = i.getStringExtra("label");
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UpdLog.add(label + ": 確認画面を開きます(自動で押します)");
            try { c.startActivity(confirm); } catch (Exception e) { notifyUser(c, label, confirm); }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            UpdLog.add(label + ": インストール成功");
            // 本体は入れ替えで閉じるので、すぐ開き直す(ホームが別のアプリでも、CarPlay・YouTube が戻るように)
            if ("com.yuki.duocruise.main".equals(i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))) {
                try {
                    Intent open = new Intent().setClassName("com.yuki.duocruise.main", "com.yuki.duocruise.main.DuoActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(open);
                    UpdLog.add(label + ": 開き直しました");
                } catch (Exception e) { UpdLog.add(label + ": 開き直せません " + e.getMessage()); }
            }
        } else {
            UpdLog.add(label + ": インストール失敗 (" + st + ") " + msg);
        }
    }

    private void notifyUser(Context c, String label, Intent confirm) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("upd", "アップデート", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, 1, confirm, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, "upd").setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(label + " を更新できます").setContentText("タップして確認").setContentIntent(pi)
                    .setFullScreenIntent(pi, true).setAutoCancel(true).build();
            nm.notify(7, n);
            UpdLog.add(label + ": 通知を出しました(画面を開いてください)");
        } catch (Exception e) { UpdLog.add("通知失敗: " + e.getMessage()); }
    }
}
