package com.yuki.duocruise.main;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 記録。3か所に残す: 画面(操作盤の「記録」)、logcat(DUO)、更新アプリへのブロードキャスト(記録シートへ)。
 *
 * 約束(実装の分担 2026-10-27):
 *   送る: com.yuki.duocruise.updater.LOG、宛先パッケージ com.yuki.duocruise.updater
 *   中身: kind(log / state / keys / screen / error / result)、text、app、version
 *   送る側の権限: com.yuki.duocruise.permission.LOG(マニフェストで uses-permission 済み)
 */
public final class DuoLog {
    private DuoLog() {}

    static final String ACTION = "com.yuki.duocruise.updater.LOG";
    static final String UPDATER = "com.yuki.duocruise.updater";
    static final String TAG = "DUO";

    interface Listener { void changed(); }
    static volatile Listener listener;

    private static Context app;
    private static String version = "?";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<String> lines = new ArrayList<>();
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
    private static final StringBuilder keyBuf = new StringBuilder();
    private static boolean keyFlushPosted;
    /** 送りすぎ防止: 1分あたりの上限(error と result は別枠で必ず送る)。 */
    private static final int PER_MIN = 40;
    private static long windowStart;
    private static int sentInWindow;
    private static String lastError = "";
    private static long lastErrorAt;

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        try { version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
    }

    static String version() { return version; }

    static void log(String t) { add("log", t); }
    static void state(String t) { add("state", t); }
    static void result(String t) { add("result", t); }

    static void error(String t) {
        long now = SystemClock.elapsedRealtime();
        synchronized (DuoLog.class) {
            if (t.equals(lastError) && now - lastErrorAt < 10_000) return; // 同じ失敗の連発は1回に
            lastError = t;
            lastErrorAt = now;
        }
        add("error", t);
    }

    /** キーの記録は3秒ぶんまとめて1通にする(画面と logcat には出さない)。 */
    static void keys(String t) {
        synchronized (DuoLog.class) {
            if (keyBuf.length() > 0) keyBuf.append(" | ");
            keyBuf.append(t);
            if (keyBuf.length() > 3000) keyBuf.delete(0, keyBuf.length() - 3000);
            if (keyFlushPosted) return;
            keyFlushPosted = true;
        }
        MAIN.postDelayed(() -> {
            String s;
            synchronized (DuoLog.class) {
                s = keyBuf.toString();
                keyBuf.setLength(0);
                keyFlushPosted = false;
            }
            if (!s.isEmpty()) send("keys", s);
        }, 3000);
    }

    private static void add(String kind, String t) {
        synchronized (DuoLog.class) {
            lines.add(0, F.format(new Date()) + "  " + ("error".equals(kind) ? "✕ " : "") + t);
            while (lines.size() > 200) lines.remove(lines.size() - 1);
        }
        if ("error".equals(kind)) Log.w(TAG, t); else Log.i(TAG, kind + ": " + t);
        send(kind, t);
        Listener l = listener;
        if (l != null) MAIN.post(l::changed);
    }

    /**
     * ほかの部品(tv/ の TvLog、carplay/ など)から使う入り口。画面の記録・logcat・更新アプリの3か所に残す。
     * kind は log / state / keys / screen / error / result。
     */
    public static void send(Context c, String kind, String text) {
        if (c != null) init(c);
        if ("keys".equals(kind)) { keys(text); return; }
        if ("error".equals(kind)) { error(text); return; }
        add(kind == null ? "log" : kind, text);
    }

    /** 更新アプリへ送る。更新アプリが無い・権限が無いときは何もしない(画面と logcat には残る)。 */
    static void send(String kind, String text) {
        Context c = app;
        if (c == null) return;
        if (!"error".equals(kind) && !"result".equals(kind)) {
            synchronized (DuoLog.class) {
                long now = SystemClock.elapsedRealtime();
                if (now - windowStart > 60_000) { windowStart = now; sentInWindow = 0; }
                if (++sentInWindow > PER_MIN) return;
            }
        }
        try {
            Intent i = new Intent(ACTION).setPackage(UPDATER)
                    .putExtra("kind", kind)
                    .putExtra("text", text)
                    .putExtra("app", c.getPackageName())
                    .putExtra("version", version);
            c.sendBroadcast(i);
        } catch (Exception e) {
            Log.w(TAG, "記録を送れません: " + e);
        }
    }

    static synchronized List<String> snapshot() { return new ArrayList<>(lines); }

    static String why(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c != c.getCause()) c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
