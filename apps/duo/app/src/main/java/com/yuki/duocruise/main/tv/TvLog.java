package com.yuki.duocruise.main.tv;

import android.content.Context;

import com.yuki.duocruise.main.DuoLog;

/**
 * TV 版 YouTube の記録。本体の DuoLog に渡す(画面の記録・logcat・更新アプリへの送信は DuoLog が行う)。
 * 文の先頭に「[TV]」を付けて、どの部品の記録か分かるようにする。
 */
public final class TvLog {
    private TvLog() {}

    public static void log(Context c, String text) { send(c, "log", text); }
    public static void state(Context c, String text) { send(c, "state", text); }
    public static void error(Context c, String text) { send(c, "error", text); }
    public static void result(Context c, String text) { send(c, "result", text); }

    public static void send(Context c, String kind, String text) {
        DuoLog.send(c, kind, "[TV] " + text);
    }
}
