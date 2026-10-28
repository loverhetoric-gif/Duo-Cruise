package com.yuki.duocruise.main;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 落ちたときの記録。利用者の車で起きた不具合を受け取るため。
 *
 * 落ちた瞬間は送れない(送る前にアプリが終わる)ので、手元に書き残し、次に開いたとき記録として送る。
 * 送り先は今の記録と同じ(更新アプリ → 記録シート)。書き残すのは版・スレッド・例外の先頭 30 行だけ。
 */
final class Crash {
    private static final String PREFS = "duo_crash", KEY = "last";
    private static boolean installed;

    private Crash() {}

    static synchronized void install(Context c) {
        Context app = c.getApplicationContext();
        report(app);
        if (installed) return;
        installed = true;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String[] lines = sw.toString().split("\n");
                StringBuilder b = new StringBuilder();
                b.append("版 ").append(DuoLog.version()).append(" / スレッド ").append(thread.getName()).append('\n');
                for (int i = 0; i < Math.min(30, lines.length); i++) b.append(lines[i].trim()).append('\n');
                prefs(app).edit().putString(KEY, b.toString()).commit(); // 落ちる前に書き切る
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(thread, e);
        });
    }

    /** 前回落ちていたら、記録として送って消す。 */
    private static void report(Context app) {
        SharedPreferences p = prefs(app);
        String last = p.getString(KEY, null);
        if (last == null) return;
        p.edit().remove(KEY).apply();
        DuoLog.error("前回、アプリが落ちました:\n" + last);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
