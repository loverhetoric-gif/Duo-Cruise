package com.yuki.duocruise.main.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * TV 版 YouTube の記録。約束のブロードキャスト(com.yuki.duocruise.updater.LOG)で更新アプリへ送る。
 * C の DuoLog(com.yuki.duocruise.main.DuoLog の static send(Context, String kind, String text))があればそれを使い、
 * 無ければ同じ約束で直接送る。logcat(タグ DUO-TV)にも出す。
 */
public final class TvLog {
    static final String ACTION = "com.yuki.duocruise.updater.LOG";
    static final String TARGET = "com.yuki.duocruise.updater";
    static final String PERMISSION = "com.yuki.duocruise.permission.LOG";

    private static volatile Method duoLog;
    private static volatile boolean looked;
    private static volatile String version;

    private TvLog() {}

    public static void log(Context c, String text) { send(c, "log", text); }
    public static void state(Context c, String text) { send(c, "state", text); }
    public static void error(Context c, String text) { send(c, "error", text); }
    public static void result(Context c, String text) { send(c, "result", text); }

    public static void send(Context c, String kind, String text) {
        String t = "[TV] " + text;
        Log.i("DUO-TV", kind + ": " + t);
        if (c == null) return;
        Context app = c.getApplicationContext();
        Method m = findDuoLog();
        if (m != null) {
            try { m.invoke(null, app, kind, t); return; } catch (Exception ignored) { /* 下で直接送る */ }
        }
        try {
            Intent i = new Intent(ACTION).setPackage(TARGET)
                    .putExtra("kind", kind)
                    .putExtra("text", t)
                    .putExtra("app", app.getPackageName())
                    .putExtra("version", version(app));
            app.sendBroadcast(i);
        } catch (Exception e) {
            Log.w("DUO-TV", "記録の送信に失敗: " + e);
        }
    }

    private static Method findDuoLog() {
        if (looked) return duoLog;
        try {
            Class<?> k = Class.forName("com.yuki.duocruise.main.DuoLog");
            duoLog = k.getMethod("send", Context.class, String.class, String.class);
        } catch (Exception ignored) {
            duoLog = null;
        }
        looked = true;
        return duoLog;
    }

    private static String version(Context c) {
        String v = version;
        if (v != null) return v;
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            v = p.versionName + "(" + p.getLongVersionCode() + ")";
        } catch (Exception e) {
            v = "?";
        }
        version = v;
        return v;
    }
}
