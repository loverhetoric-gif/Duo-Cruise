package com.yuki.duocruise.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 自作アプリからの記録を受ける。送れるのは同じ署名のアプリだけ(マニフェストで権限 LOG を要求)。
 * extra: kind(log/state/keys/screen/error/result) text app version、任意で row(指示の行) time(ms)
 */
public class LogReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yuki.duocruise.updater.LOG";

    @Override public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        Remote.init(c);
        String kind = i.getStringExtra("kind");
        String app = i.getStringExtra("app");
        String version = i.getStringExtra("version");
        if (version == null) {
            long v = i.getLongExtra("version", -1);
            if (v < 0) v = i.getIntExtra("version", -1);
            version = v < 0 ? "" : String.valueOf(v);
        }
        Remote.add(kind == null ? "log" : kind, i.getStringExtra("text"), app, version,
                i.getIntExtra("row", -1), i.getLongExtra("time", 0));
    }
}
