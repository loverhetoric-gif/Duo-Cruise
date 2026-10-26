package com.yuki.duocruise.updater;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 画面・ファイル・logcat(DUOUPD)・記録シート(Remote)に記録する。 */
final class UpdLog {
    interface Listener { void changed(); }
    private static final List<String> lines = new ArrayList<>();
    private static final SimpleDateFormat F = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN);
    private static File file;
    static volatile Listener listener;

    static synchronized void init(Context c) {
        if (file != null) return;
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        file = new File(d, "updater.log");
    }

    static synchronized void add(String t) {
        String l = F.format(new Date()) + "  " + t;
        lines.add(0, l);
        while (lines.size() > 200) lines.remove(lines.size() - 1);
        Log.i("DUOUPD", t);
        if (file != null) try (FileWriter w = new FileWriter(file, true)) { w.write(l + "\n"); } catch (Exception ignored) {}
        Listener x = listener;
        if (x != null) x.changed();
        // 記録シートへも送る(受け口が未設定なら端末に溜めるだけ)
        Remote.own(t.contains("失敗") || t.contains("不一致") ? "error" : "log", t);
    }

    static synchronized List<String> snapshot() { return new ArrayList<>(lines); }
}
