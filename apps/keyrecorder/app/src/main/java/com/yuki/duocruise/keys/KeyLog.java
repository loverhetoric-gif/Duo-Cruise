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

/** キー・タッチ・前面アプリの記録を、メモリ・ファイル・logcat(DUOKEY)に残し、更新アプリ経由で記録シートへも送る。 */
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
        Report.init(c);
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
        Report.queue(line);
    }

    /** 保存した全記録(ファイル)。大きいときは新しい側の max 文字だけ。 */
    public static synchronized String readAll(int max) {
        if (file == null || !file.exists()) return "";
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(file, "r")) {
            long len = r.length();
            long from = Math.max(0, len - (long) max * 3); // UTF-8 で日本語は最大3バイト
            r.seek(from);
            byte[] b = new byte[(int) (len - from)];
            r.readFully(b);
            String s = new String(b, "UTF-8");
            if (from > 0) { int nl = s.indexOf('\n'); if (nl >= 0) s = s.substring(nl + 1); }
            return s.length() > max ? s.substring(s.length() - max) : s;
        } catch (Exception e) { return "(読めません: " + e.getMessage() + ")"; }
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
                + " t=" + e.getEventTime() + "ms 押した時=" + e.getDownTime() + "ms"
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
