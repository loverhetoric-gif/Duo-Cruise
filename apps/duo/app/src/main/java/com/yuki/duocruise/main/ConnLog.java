package com.yuki.duocruise.main;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 接続の記録(常に残す・消えない記録)。CarPlay・YouTube の利用状況と、Wi-Fi・Bluetooth の接続状況を、
 * 走行の記録(Trace)の on/off とも、画面(DuoActivity)が開いているかとも関係なく、機器の起動から残し続ける。
 *
 *   置き場所: Android/data/com.yuki.duocruise.main/files/connlog/yyyyMMdd.jsonl(1 日 1 ファイル)
 *   吸い出し: python3 tools/connlog.py pull(Mac。1 日の流れの一覧も同じ道具で出す)
 *
 * ・1 行 1 件の JSON。{"t":時刻,"up":起動からのミリ秒,"cat":分類,"ev":出来事, ...中身}
 *   cat = carplay / youtube / wifi / net / ap / bt / sys / snap(30 秒ごとの今の状態のまとめ)
 * ・1 行ごとに fsync まで行う(電源が突然切れても直前の行まで残す)。書けなかった行は覚えておき、次に書くときに先に書く
 * ・保存は 90 日。全部で 200MB を超えたら古い日から消す
 * ・書き込みはすべて専用のスレッドで行い、本体(画面・CarPlay)を待たせない。落ちる瞬間の 1 行(crash)だけはその場で書く
 *
 * 状態(CarPlay・YouTube)は、画面の側(DuoActivity・Panes・TvPane)が変わった瞬間に set して、ここが変化を見て 1 行にする。
 * Wi-Fi・Bluetooth・起動と終了は常駐サービス(ConnLogService)が見張る。
 */
public final class ConnLog {
    static final String DIR = "connlog";
    static final long KEEP_MS = 90L * 24 * 60 * 60 * 1000;
    static final long MAX_BYTES = 200L * 1024 * 1024;

    private static Context app;
    private static Handler io;
    private static File dir;
    private static String openDay;
    private static FileOutputStream out;
    private static final ArrayDeque<String> pending = new ArrayDeque<>();
    private static final int PENDING_MAX = 2000;

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.ROOT);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);

    private ConnLog() {}

    /** 何度呼んでもよい(常駐サービス・画面・落ちたときの受け口から)。 */
    public static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        HandlerThread t = new HandlerThread("duo-connlog");
        t.start();
        io = new Handler(t.getLooper());
        io.post(ConnLog::prune);
    }

    static boolean ready() { return io != null; }

    // ───────── 書く ─────────

    /** 1 行。kv は「名前, 値, 名前, 値 …」。値が null の組は書かない。 */
    public static void event(String cat, String ev, Object... kv) {
        if (io == null) return;
        final String line = build(cat, ev, kv);
        io.post(() -> write(line));
    }

    /** その場で書き切る(落ちる直前・電源が切れる直前)。専用のスレッドを待たない。 */
    static void eventNow(String cat, String ev, Object... kv) {
        if (app == null) return;
        String line = build(cat, ev, kv);
        synchronized (ConnLog.class) { write(line); }
    }

    private static String build(String cat, String ev, Object... kv) {
        try {
            JSONObject o = new JSONObject();
            synchronized (STAMP) { o.put("t", STAMP.format(new Date())); }
            o.put("up", SystemClock.elapsedRealtime());
            o.put("cat", cat);
            o.put("ev", ev);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                Object v = kv[i + 1];
                if (v == null) continue;
                o.put(String.valueOf(kv[i]), v instanceof Float ? Double.valueOf((Float) v) : v);
            }
            return o.toString();
        } catch (Throwable t) {
            return "{\"cat\":\"" + cat + "\",\"ev\":\"build-error\"}";
        }
    }

    /** io スレッド(か eventNow)でだけ呼ぶ。 */
    private static synchronized void write(String line) {
        if (pending.size() >= PENDING_MAX) pending.pollFirst();
        pending.addLast(line);
        try {
            String day;
            synchronized (STAMP) { day = DAY.format(new Date()); }
            if (out == null || !day.equals(openDay)) {
                closeQuietly();
                if (dir == null) {
                    File base = app.getExternalFilesDir(null);
                    if (base == null) return;                       // まだ読み書きできない(起動の直後)。次の行で試す
                    dir = new File(base, DIR);
                }
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                out = new FileOutputStream(new File(dir, day + ".jsonl"), true);
                if (openDay != null && !day.equals(openDay)) io.post(ConnLog::prune); // 日が変わった
                openDay = day;
            }
            StringBuilder b = new StringBuilder();
            for (String s : pending) b.append(s).append('\n');
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();                                      // 電源が切れても残るよう、ここで確定させる
            pending.clear();
        } catch (Throwable t) {
            closeQuietly();                                          // 次の行で開き直す(行は pending に残る)
        }
    }

    private static void closeQuietly() {
        try { if (out != null) out.close(); } catch (Throwable ignored) { }
        out = null;
    }

    /** 90 日より古い日と、200MB を超えた分(古い日から)を消す。 */
    private static void prune() {
        try {
            File base = app.getExternalFilesDir(null);
            if (base == null) return;
            File d = new File(base, DIR);
            File[] fs = d.listFiles((f, n) -> n.endsWith(".jsonl"));
            if (fs == null || fs.length == 0) return;
            Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName())); // 名前 = 日付の順
            long cut = System.currentTimeMillis() - KEEP_MS;
            long total = 0;
            for (File f : fs) total += f.length();
            int removed = 0;
            for (int i = 0; i < fs.length - 1; i++) {                       // 今日(最後)は消さない
                File f = fs[i];
                boolean old = f.lastModified() < cut;
                if (!old && total <= MAX_BYTES) break;
                long len = f.length();
                if (f.delete()) { total -= len; removed++; }
            }
            if (removed > 0) write(build("sys", "prune", "removed", removed, "bytes", total));
        } catch (Throwable ignored) { }
    }

    /** 吸い出しの案内(指示 connlog の結果)。 */
    static String where() {
        if (app == null) return "接続の記録: まだ始まっていません";
        File base = app.getExternalFilesDir(null);
        File d = base == null ? null : new File(base, DIR);
        File[] fs = d == null ? null : d.listFiles();
        long total = 0;
        if (fs != null) for (File f : fs) total += f.length();
        return "接続の記録: " + (d == null ? "?" : d.getAbsolutePath()) + " / " + (fs == null ? 0 : fs.length)
                + " 日分 " + (total / 1024) + "KB / 吸い出し: python3 tools/connlog.py pull";
    }

    // ───────── CarPlay の状態(画面の側から) ─────────

    private static volatile String carStatus = "画面なし", carIphone, carLocal, carIface, carHotspot, carDownWhy;
    private static volatile boolean carOn, carConn, carStream, carVisible;
    private static volatile int carWidth = -1, carArea = -1;
    private static volatile long carUpAt, carDownAt;

    /** CarPlay の状態の文(CarPlayPane の statusText)。変わったときだけ 1 行。 */
    public static void carStatus(String s) {
        if (s == null || s.equals(carStatus)) return;
        carStatus = s;
        event("carplay", "status", "status", s);
    }

    /** 本人が CarPlay を動かしているか(操作盤の開始/止める)。 */
    static void carOn(boolean on, String why) {
        if (on == carOn) return;
        carOn = on;
        event("carplay", on ? "start" : "stop", "why", why);
    }

    /**
     * iPhone とつながった/切れた(Bluetooth で iPhone と準備中〜無線 CarPlay 接続中)。
     * つながり直したときは、途切れていた秒数と切れた理由を添える。
     */
    static void carConn(boolean up, String status) {
        if (up == carConn) return;
        carConn = up;
        long now = SystemClock.elapsedRealtime();
        if (up) {
            long gap = carDownAt > 0 ? (now - carDownAt) / 1000 : -1;
            carUpAt = now;
            event("carplay", "connected", "gapS", gap >= 0 ? gap : null, "downWhy", gap >= 0 ? carDownWhy : null,
                    "status", status, "path", path());
            carDownAt = 0;
        } else {
            long kept = carUpAt > 0 ? (now - carUpAt) / 1000 : -1;
            carDownAt = now;
            carDownWhy = status;
            event("carplay", "disconnected", "keptS", kept, "why", status, "path", path());
        }
    }

    /** 映像が届いているか。 */
    static void carStream(boolean on) {
        if (on == carStream) return;
        carStream = on;
        event("carplay", on ? "video-on" : "video-off");
    }

    /** CarPlay が画面に見えているか(2 画面・1 画面のどちらを出しているか)。 */
    static void carVisible(boolean v, String layout) {
        if (v == carVisible) return;
        carVisible = v;
        event("carplay", v ? "shown" : "hidden", "layout", layout);
    }

    /** CarPlay の枠の幅(px)。 */
    static void carWidth(int px) {
        if (px == carWidth) return;
        carWidth = px;
        event("carplay", "width", "px", px);
    }

    /**
     * CarPlay の部品からの通信の知らせ(CarPlayPane.KIND_CONN。タブ区切りの 名前=値)。
     * ev=session-up(iPhone の IP・こちらの IP・通信の口)/ session-down / hotspot(CarPlay 用の Wi-Fi)/ area(描く範囲の幅)
     */
    static void carNet(String text) {
        String[] parts = text.split("\t");
        String ev = parts[0];
        Object[] kv = new Object[(parts.length - 1) * 2];
        int n = 0;
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            String k = parts[i].substring(0, eq), v = parts[i].substring(eq + 1);
            kv[n++] = k; kv[n++] = v;
            if (ev.equals("session-up")) {
                if (k.equals("iphone")) carIphone = v;
                else if (k.equals("local")) carLocal = v;
                else if (k.equals("iface")) carIface = v;
            } else if (ev.equals("hotspot") && k.equals("desc")) carHotspot = v;
            else if (ev.equals("area") && k.equals("w")) { try { carArea = Integer.parseInt(v); } catch (Exception ignored) { } }
        }
        if (ev.equals("session-down")) { carIphone = null; carLocal = null; carIface = null; }
        event("carplay", ev, Arrays.copyOf(kv, n));
    }

    /** CarPlay の通信の道(いまの iPhone・こちらの IP・口・CarPlay 用の Wi-Fi)。 */
    private static String path() {
        return "iphone=" + carIphone + " local=" + carLocal + " iface=" + carIface + " hotspot=" + carHotspot;
    }

    /** 画面が閉じた(CarPlay・YouTube の状態は画面と一緒に消える)。 */
    static void screenGone() {
        carStatus = "画面なし"; carConn = false; carStream = false; carVisible = false;
        carIphone = null; carLocal = null; carIface = null;
        ytState = "画面なし";
    }

    static JSONObject carSnap() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", carOn).put("status", carStatus).put("conn", carConn).put("video", carStream)
                    .put("shown", carVisible).put("width", carWidth).put("area", carArea);
            if (carIphone != null) o.put("iphone", carIphone);
            if (carLocal != null) o.put("local", carLocal);
            if (carIface != null) o.put("iface", carIface);
            if (carHotspot != null) o.put("hotspot", carHotspot);
            if (carConn && carUpAt > 0) o.put("upS", (SystemClock.elapsedRealtime() - carUpAt) / 1000);
        } catch (Throwable ignored) { }
        return o;
    }

    // ───────── YouTube(TV 版)の状態 ─────────

    private static volatile String ytState = "画面なし", ytRes, ytErr;
    private static volatile float ytDuck = 1f;
    private static volatile boolean ytVoice, ytHold;

    /** 再生の状態: playing / paused / loading / error / no-video。変わったときだけ 1 行。 */
    public static void yt(String state, String res, String err) {
        boolean changed = !state.equals(ytState) || (err != null && !err.equals(ytErr));
        boolean resChanged = res != null && !res.equals(ytRes) && !"0x0".equals(res);
        ytState = state;
        if (err != null) ytErr = err;
        if (resChanged) ytRes = res;
        if (changed) event("youtube", state, "res", ytRes, "err", "error".equals(state) ? err : null);
        else if (resChanged) event("youtube", "quality", "res", res);
    }

    /** ページの読み込みの失敗など、YouTube の枠の失敗。 */
    public static void ytError(String what) { ytErr = what; event("youtube", "error", "err", what); }

    /** 音量(CarPlay の声で下げている間か)。 */
    public static void ytDuck(float level, String why) {
        if (Math.abs(level - ytDuck) < 0.01f) return;
        ytDuck = level;
        event("youtube", level >= 1f ? "duck-off" : "duck-on", "level", Math.round(level * 100), "why", why);
    }

    /** ナビの声の間(無音・スロー/一時停止)・電話や Siri の間(一時停止)。 */
    public static void ytVoice(boolean voice, boolean hold) {
        if (voice != ytVoice) { ytVoice = voice; event("youtube", voice ? "voice-on" : "voice-off"); }
        if (hold != ytHold) { ytHold = hold; event("youtube", hold ? "hold-on" : "hold-off"); }
    }

    static JSONObject ytSnap() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", ytState).put("duck", Math.round(ytDuck * 100)).put("voice", ytVoice).put("hold", ytHold);
            if (ytRes != null) o.put("res", ytRes);
            if (ytErr != null) o.put("lastErr", ytErr);
        } catch (Throwable ignored) { }
        return o;
    }
}
