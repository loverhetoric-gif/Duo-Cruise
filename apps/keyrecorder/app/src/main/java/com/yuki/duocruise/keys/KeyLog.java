package com.yuki.duocruise.keys;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** キー・タッチ・前面アプリの記録を、メモリ・ファイル・logcat(DUOKEY)の3か所に残す。 */
public final class KeyLog {
    public interface Listener { void onChanged(); }

    private static final int MAX = 300;
    private static final List<String> lines = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Listener listener;
    private static File file;
    /** ユーザー補助サービスが動いている間は true(画面側での二重記録を避ける)。 */
    public static volatile boolean serviceActive = false;

    private KeyLog() {}

    public static synchronized void init(Context c) {
        if (file != null) return;
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        file = new File(dir, "keylog.txt");
    }

    public static String filePath() { return file == null ? "(未作成)" : file.getAbsolutePath(); }

    public static void setListener(Listener l) { listener = l; }

    public static synchronized List<String> snapshot() { return new ArrayList<>(lines); }

    public static synchronized void clear() {
        lines.clear();
        if (file != null) file.delete();
        notifyChanged();
    }

    public static synchronized void add(String text) {
        String line = FMT.format(new Date()) + "  " + text;
        lines.add(0, line);
        while (lines.size() > MAX) lines.remove(lines.size() - 1);
        Log.i("DUOKEY", text);
        if (file != null) {
            try (FileWriter w = new FileWriter(file, true)) { w.write(line + "\n"); } catch (Exception ignored) {}
        }
        notifyChanged();
    }

    private static void notifyChanged() {
        final Listener l = listener;
        if (l != null) MAIN.post(l::onChanged);
    }

    public static String describe(KeyEvent e, String where) {
        String act = e.getAction() == KeyEvent.ACTION_DOWN ? "押す" : e.getAction() == KeyEvent.ACTION_UP ? "離す" : "他";
        InputDevice d = InputDevice.getDevice(e.getDeviceId());
        String dev = d == null ? "dev=" + e.getDeviceId() : "dev=" + e.getDeviceId() + "(" + d.getName() + ")";
        return "[" + where + "] " + act + "  " + KeyEvent.keyCodeToString(e.getKeyCode())
                + "  code=" + e.getKeyCode() + " scan=" + e.getScanCode()
                + " 連続=" + e.getRepeatCount() + (e.isLongPress() ? " 長押し" : "")
                + " src=0x" + Integer.toHexString(e.getSource()) + " " + dev;
    }

    public static String describe(MotionEvent e, String where) {
        InputDevice d = InputDevice.getDevice(e.getDeviceId());
        String dev = d == null ? "dev=" + e.getDeviceId() : "dev=" + e.getDeviceId() + "(" + d.getName() + ")";
        return "[" + where + "] 動き " + MotionEvent.actionToString(e.getActionMasked())
                + " x=" + Math.round(e.getX()) + " y=" + Math.round(e.getY())
                + " src=0x" + Integer.toHexString(e.getSource()) + " " + dev;
    }
}
