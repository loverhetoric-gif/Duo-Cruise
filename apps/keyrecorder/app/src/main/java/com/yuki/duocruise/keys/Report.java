package com.yuki.duocruise.keys;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 記録を「Duo Cruise 更新」へ渡す(更新アプリがまとめて記録シートへ送る)。
 * キーの記録は 1.5 秒ぶんまとめて1回のブロードキャストにする。
 * 受け取れるのは同じ署名のアプリだけ(権限 com.yuki.duocruise.permission.LOG)。
 */
public final class Report {
    static final String ACTION = "com.yuki.duocruise.updater.LOG";
    static final String UPDATER = "com.yuki.duocruise.updater";
    static final String PERM = "com.yuki.duocruise.permission.LOG";
    private static final int CHUNK = 30000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<String> pending = new ArrayList<>();
    private static Context app;
    private static boolean scheduled = false;

    private Report() {}

    static synchronized void init(Context c) { if (app == null) app = c.getApplicationContext(); }

    /** キー記録の1行を溜め、少し待ってからまとめて送る。 */
    static synchronized void queue(String line) {
        if (app == null) return;
        pending.add(line);
        if (!scheduled) {
            scheduled = true;
            MAIN.postDelayed(Report::flush, 1500);
        }
    }

    private static void flush() {
        String text;
        synchronized (Report.class) {
            scheduled = false;
            if (pending.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (String l : pending) sb.append(l).append('\n');
            pending.clear();
            text = sb.toString();
        }
        send(app, "keys", text, -1);
    }

    /** 1件送る。長いものは 30000 文字ずつに分ける。 */
    static void send(Context c, String kind, String text, int row) {
        if (c == null) return;
        String t = text == null ? "" : text;
        long version = -1;
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode(); } catch (Exception ignored) {}
        int parts = Math.max(1, (t.length() + CHUNK - 1) / CHUNK);
        for (int i = 0; i < parts; i++) {
            String part = t.substring(i * CHUNK, Math.min(t.length(), (i + 1) * CHUNK));
            if (parts > 1) part = "(" + (i + 1) + "/" + parts + ")\n" + part;
            Intent in = new Intent(ACTION).setPackage(UPDATER)
                    .putExtra("kind", kind).putExtra("text", part)
                    .putExtra("app", c.getPackageName()).putExtra("version", String.valueOf(version))
                    .putExtra("time", System.currentTimeMillis());
            if (row > 0) in.putExtra("row", row);
            try { c.sendBroadcast(in, PERM); } catch (Exception ignored) {}
        }
    }
}
