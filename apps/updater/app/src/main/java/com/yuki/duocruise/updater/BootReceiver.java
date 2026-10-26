package com.yuki.duocruise.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        UpdLog.init(c);
        CheckJobService.schedule(c);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            AuthCheck.runOnce(app);
            UpdateEngine.checkAndInstall(app, false);
        }).start();
    }
}
