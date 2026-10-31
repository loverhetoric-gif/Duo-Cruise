package com.yuki.duocruise.main;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 走行の記録(Duo Cruise Unlimited Edition・本人の検証用)。適切な切り替えのタイミングと配置を測るため、
 * 走っている間ずっと、下の4つを機器のローカルに貯める。送信はしない(量が多いため)。後で adb で吸い出す:
 *   adb pull /sdcard/Android/data/com.yuki.duocruise.main/files/trace
 *
 *  ・位置と経路   … DriveWatch の GPS の各点(緯度経度・速さ・向き)
 *  ・切り替え     … モードの帯・並べ替え・分割比など、表示が変わった瞬間(ev)
 *  ・画面         … その瞬間の全画面スクショ(MediaProjection。ShotService。許可が要る)
 *  ・CPU/メモリ   … 数秒ごとの使用率・空き(TraceStats)
 *
 * 記録は1行1件の JSONL。1回の走行(アプリの起動〜)で1ファイル。落ちても直前まで残るよう、毎行 flush する。
 * オン/オフは Store に残り、次の起動でも続く(「運転中はずっと」)。切り替えは記録シートの指示 trace on/off か adb。
 */
final class Trace {
    interface Ctx { String snapshot(); } // 今の状態を JSON の断片("drive":..,"yt":.. )で返す

    private static Context app;
    private static Ctx ctx;
    private static Handler io;                 // 専用スレッド。書き込みは全部ここで
    private static volatile boolean on;
    private static volatile boolean shots;     // スクショも撮るか
    private static File dir, shotDir, file;
    private static Writer out;
    private static long lastShotMs;            // スクショの連発を防ぐ

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT);
    private static final SimpleDateFormat NAME = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000; // 1週間で古いものは消す
    private static final long SHOT_GAP_MS = 350;                  // スクショの最短間隔

    private Trace() {}

    static synchronized void init(Context c, Ctx snapshot) {
        if (app != null) return;
        app = c.getApplicationContext();
        ctx = snapshot;
        HandlerThread t = new HandlerThread("duo-trace");
        t.start();
        io = new Handler(t.getLooper());
        shots = Store.traceShots(app);
        if (Store.traceOn(app)) setOn(app, true);
    }

    static boolean on() { return on; }
    static boolean shots() { return shots; }

    /** 記録のオン/オフ。オンにしたら新しい走行ファイルを開く。 */
    static synchronized void setOn(Context c, boolean v) {
        Store.setTraceOn(c, v);
        if (v == on) return;
        on = v;
        if (io == null) return;
        io.post(v ? Trace::open : Trace::close);
    }

    static void setShots(Context c, boolean v) {
        shots = v;
        Store.setTraceShots(c, v);
        event("shots", v ? "on" : "off");
    }

    private static void open() {
        try {
            dir = new File(app.getExternalFilesDir(null), "trace");
            shotDir = new File(dir, "shots");
            //noinspection ResultOfMethodCallIgnored
            shotDir.mkdirs();
            prune();
            file = new File(dir, NAME.format(new Date()) + ".jsonl");
            out = new FileWriter(file, true);
            line("{\"t\":\"" + STAMP.format(new Date()) + "\",\"up\":" + SystemClock.elapsedRealtime()
                    + ",\"ev\":\"start\",\"ver\":\"" + esc(DuoLog.version()) + "\"" + ctxOrEmpty() + "}");
            DuoLog.log("走行の記録: 開始 → " + file.getName() + (shots ? "(スクショあり)" : ""));
        } catch (Throwable t) {
            DuoLog.error("走行の記録を始められません: " + DuoLog.why(t));
            out = null;
        }
    }

    private static void close() {
        if (out == null) return;
        line("{\"t\":\"" + STAMP.format(new Date()) + "\",\"up\":" + SystemClock.elapsedRealtime() + ",\"ev\":\"stop\"}");
        try { out.flush(); out.close(); } catch (Throwable ignored) {}
        out = null;
        DuoLog.log("走行の記録: 停止");
    }

    /** 表示が変わった・状態が変わった瞬間。 */
    static void event(String ev, String v) {
        if (!on || io == null) return;
        final long up = SystemClock.elapsedRealtime();
        final String stamp = STAMP.format(new Date());
        io.post(() -> line("{\"t\":\"" + stamp + "\",\"up\":" + up + ",\"ev\":\"" + esc(ev) + "\",\"v\":\""
                + esc(v) + "\"" + ctxOrEmpty() + "}"));
    }

    /** GPS の点(DriveWatch から)。経路の記録。 */
    static void gps(Location loc) {
        if (!on || io == null || loc == null) return;
        final double lat = loc.getLatitude(), lon = loc.getLongitude();
        final float spd = loc.hasSpeed() ? loc.getSpeed() * 3.6f : -1f;     // km/h
        final float brg = loc.hasBearing() ? loc.getBearing() : -1f;
        final float acc = loc.hasAccuracy() ? loc.getAccuracy() : -1f;
        final long up = SystemClock.elapsedRealtime();
        final String stamp = STAMP.format(new Date());
        io.post(() -> line("{\"t\":\"" + stamp + "\",\"up\":" + up + ",\"ev\":\"gps\",\"lat\":" + r6(lat)
                + ",\"lon\":" + r6(lon) + ",\"spd\":" + r1(spd) + ",\"brg\":" + Math.round(brg)
                + ",\"acc\":" + r1(acc) + ctxOrEmpty() + "}"));
    }

    /** CPU・メモリの一行(TraceStats から。すでに JSON の中身の断片)。 */
    static void stat(String body) {
        if (!on || io == null) return;
        final long up = SystemClock.elapsedRealtime();
        final String stamp = STAMP.format(new Date());
        io.post(() -> line("{\"t\":\"" + stamp + "\",\"up\":" + up + ",\"ev\":\"stat\"," + body + "}"));
    }

    /** いまの全画面を1枚撮る(スクショがオンのときだけ)。reason は撮った理由(mode:corner など)。 */
    static void shot(String reason) {
        if (!on || !shots || io == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastShotMs < SHOT_GAP_MS) return;
        lastShotMs = now;
        ShotService.grab(reason);
    }

    /** ShotService が撮り終えたら呼ぶ(スレッドは問わない)。file は shots/ からの相対名。 */
    static void shotSaved(String relFile, String reason) {
        if (io == null) return;
        final long up = SystemClock.elapsedRealtime();
        final String stamp = STAMP.format(new Date());
        io.post(() -> line("{\"t\":\"" + stamp + "\",\"up\":" + up + ",\"ev\":\"shot\",\"file\":\""
                + esc(relFile) + "\",\"reason\":\"" + esc(reason) + "\"" + ctxOrEmpty() + "}"));
    }

    static File shotDir() { return shotDir; }

    /** 吸い出し方を一言で返す(記録シートの指示 trace pull の結果用)。 */
    static String where() {
        if (dir == null) return "まだ記録していません";
        File[] fs = dir.listFiles();
        int n = fs == null ? 0 : fs.length;
        return "記録先: " + dir.getAbsolutePath() + " / ファイル " + n + " 件。吸い出し: adb pull " + dir.getAbsolutePath();
    }

    // ───────── 内部 ─────────

    /** io スレッドでだけ呼ぶ。 */
    private static void line(String s) {
        if (out == null) return;
        try { out.write(s); out.write('\n'); out.flush(); }
        catch (Throwable t) { /* 記録の失敗で本体を巻き込まない */ }
    }

    private static String ctxOrEmpty() {
        try { String s = ctx == null ? null : ctx.snapshot(); return s == null || s.isEmpty() ? "" : "," + s; }
        catch (Throwable t) { return ""; }
    }

    /** 1週間より古い走行ファイルとスクショを消す。 */
    private static void prune() {
        long cut = System.currentTimeMillis() - KEEP_MS;
        for (File d : new File[]{dir, shotDir}) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) if (f.isFile() && f.lastModified() < cut) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else if (ch < 0x20) b.append(' ');
            else b.append(ch);
        }
        return b.toString();
    }

    private static String r6(double v) { return String.format(Locale.ROOT, "%.6f", v); }
    private static String r1(float v) { return v < 0 ? "-1" : String.format(Locale.ROOT, "%.1f", v); }
}
