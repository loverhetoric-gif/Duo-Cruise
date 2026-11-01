package com.yuki.duocruise.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 記録シート(本人の Google Apps Script)との行き来。
 * ・自分と自作アプリの記録を端末に溜め、まとめて HTTPS で送る(ネットが無い間は溜めるだけ・上限あり)
 * ・数分ごとに「指示」を取りに行き、Diag に渡す
 * ・合言葉は初回に乱数で作って端末内に保存し、送信に付ける(画面・記録には出さない)
 * 受け口の URL はビルド時に remote.properties から入る。空なら送らずに溜めるだけ。
 */
final class Remote {
    static final String URL_ = BuildConfig.LOG_URL;
    static final String[] KINDS = {"log", "state", "keys", "screen", "error", "result"};

    /** 溜める上限。超えたら古いものから捨てる。 */
    private static final int MAX_ITEMS = 2000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT = 40000;
    private static final int MAX_PNG = 5;
    /** 1回の送信の上限。 */
    private static final int BATCH_ITEMS = 100;
    private static final int BATCH_BYTES = 600 * 1024;
    /** 指示を取りに行く間隔。 */
    /** 指示を取りに行く間隔。30 秒(updater:check で、ほぼすぐ更新できるように。2026-10-29)。 */
    static final long POLL_MS = 30 * 1000L;
    private static final long FLUSH_DELAY_MS = 20 * 1000L;

    private static Context app;
    private static Handler handler;
    private static File queueFile, pngDir;
    private static final List<JSONObject> queue = new ArrayList<>();
    private static int queueBytes = 0;
    private static boolean flushScheduled = false, saveScheduled = false;
    /** 状態の報告用。 */
    static volatile String lastSend = "まだ送っていません";
    static volatile String lastPoll = "まだ取りに行っていません";
    static volatile int dropped = 0;

    private Remote() {}

    static boolean configured() { return URL_ != null && URL_.startsWith("https://"); }

    /** アプリの起動時に1回。端末に溜めた分を読み、定期の送信・指示の取得を始める。 */
    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        queueFile = new File(app.getFilesDir(), "remote-queue.jsonl");
        pngDir = new File(app.getFilesDir(), "remote-png");
        pngDir.mkdirs();
        load();
        HandlerThread t = new HandlerThread("duo-remote");
        t.start();
        handler = new Handler(t.getLooper());
        handler.postDelayed(Remote::tick, 10 * 1000L);
    }

    /** 30 秒ごと:送る → 指示を取りに行く → 次を予約。 */
    private static void tick() {
        try { flushNow(); } catch (Throwable e) { Log.w("DUOUPD", "flush", e); }
        try { pollNow(); } catch (Throwable e) { Log.w("DUOUPD", "poll", e); }
        handler.postDelayed(Remote::tick, POLL_MS);
    }

    /** すぐに送る・指示を見る(画面のボタン・起動時など)。 */
    static void kick() {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> {
            try { flushNow(); } catch (Throwable ignored) {}
            try { pollNow(); } catch (Throwable ignored) {}
        });
    }

    // ---------- 溜める ----------

    static String device() { return Build.MANUFACTURER + " " + Build.MODEL; }

    private static String stamp() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.JAPAN).format(new Date()); }

    /** 更新アプリ自身の記録。 */
    static void own(String kind, String text) {
        add(kind, text, BuildConfig.APPLICATION_ID, String.valueOf(BuildConfig.VERSION_CODE), -1, 0);
    }

    /** 指示の結果。row は「指示」シートの行(分からなければ -1)。 */
    static void result(String text, int row) {
        add("result", text, BuildConfig.APPLICATION_ID, String.valueOf(BuildConfig.VERSION_CODE), row, 0);
    }

    /** 記録を1件溜める。time は端末の時刻(ms、0なら今)。 */
    static void add(String kind, String text, String appPkg, String version, int row, long time) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", Arrays.asList(KINDS).contains(kind) ? kind : "log");
            String t = text == null ? "" : text;
            if (t.length() > MAX_TEXT) t = t.substring(0, MAX_TEXT) + "…(省略)";
            o.put("text", t);
            o.put("app", appPkg == null ? "" : appPkg);
            o.put("version", version == null ? "" : version);
            o.put("time", time > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.JAPAN).format(new Date(time)) : stamp());
            o.put("device", device());
            if (row > 0) o.put("row", row);
            push(o);
        } catch (Exception e) {
            Log.w("DUOUPD", "add", e);
        }
    }

    /** 画面の写真(PNG)を溜める。写真はファイルに置き、送るときに読む。 */
    static void addPng(String text, byte[] png, int row) {
        try {
            File[] old = pngDir.listFiles();
            if (old != null && old.length >= MAX_PNG) {
                Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));
                for (int i = 0; i <= old.length - MAX_PNG; i++) old[i].delete();
            }
            File f = new File(pngDir, System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(f)) { out.write(png); }
            JSONObject o = new JSONObject();
            o.put("kind", "screen");
            o.put("text", text);
            o.put("app", BuildConfig.APPLICATION_ID);
            o.put("version", String.valueOf(BuildConfig.VERSION_CODE));
            o.put("time", stamp());
            o.put("device", device());
            if (row > 0) o.put("row", row);
            o.put("pngFile", f.getName());
            push(o);
        } catch (Exception e) {
            Log.w("DUOUPD", "png", e);
        }
    }

    private static synchronized void push(JSONObject o) {
        if (app == null) return; // 起動前(通常は起こらない)
        queue.add(o);
        queueBytes += o.toString().length();
        while (queue.size() > MAX_ITEMS || queueBytes > MAX_BYTES) {
            JSONObject x = queue.remove(0);
            queueBytes -= x.toString().length();
            String pf = x.optString("pngFile", "");
            if (!pf.isEmpty()) new File(pngDir, pf).delete();
            dropped++;
        }
        scheduleSave();
        if (configured() && !flushScheduled && handler != null) {
            flushScheduled = true;
            handler.postDelayed(() -> {
                synchronized (Remote.class) { flushScheduled = false; }
                try { flushNow(); } catch (Throwable ignored) {}
            }, FLUSH_DELAY_MS);
        }
    }

    static synchronized int pending() { return queue.size(); }

    // ---------- 端末への保存 ----------

    private static void scheduleSave() {
        if (saveScheduled || handler == null) return;
        saveScheduled = true;
        handler.postDelayed(Remote::save, 5000);
    }

    private static synchronized void save() {
        saveScheduled = false;
        File tmp = new File(queueFile.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) {
            for (JSONObject o : queue) { w.write(o.toString()); w.write('\n'); }
        } catch (Exception e) { Log.w("DUOUPD", "save", e); return; }
        tmp.renameTo(queueFile);
    }

    private static synchronized void load() {
        if (!queueFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(queueFile), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    JSONObject o = new JSONObject(line);
                    queue.add(o);
                    queueBytes += line.length();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { Log.w("DUOUPD", "load", e); }
        while (queue.size() > MAX_ITEMS || queueBytes > MAX_BYTES) queueBytes -= queue.remove(0).toString().length();
    }

    // ---------- 合言葉 ----------

    /** 初回に乱数で作る。値は端末内(アプリ専用の領域)にだけ置き、画面や記録には出さない。 */
    static synchronized String token() {
        SharedPreferences p = app.getSharedPreferences("remote", Context.MODE_PRIVATE);
        String t = p.getString("token", null);
        if (t == null || t.length() != 64) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            p.edit().putString("token", t).commit();
        }
        return t;
    }

    // ---------- 送る ----------

    private static String clock() { return new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(new Date()); }

    /** 溜めた分をまとめて送る(この呼び出しは送信用の糸の中だけ)。 */
    private static void flushNow() throws Exception {
        if (!configured()) return;
        for (int round = 0; round < 20; round++) {
            List<JSONObject> batch = new ArrayList<>();
            synchronized (Remote.class) {
                int bytes = 0;
                for (JSONObject o : queue) {
                    boolean png = !o.optString("pngFile", "").isEmpty();
                    if (png && !batch.isEmpty()) break;        // 写真は1件ずつ送る
                    int len = o.toString().length();
                    if (!batch.isEmpty() && (batch.size() >= BATCH_ITEMS || bytes + len > BATCH_BYTES)) break;
                    batch.add(o);
                    bytes += len;
                    if (png) break;
                }
            }
            if (batch.isEmpty()) return;
            JSONArray items = new JSONArray();
            for (JSONObject o : batch) {
                JSONObject x = new JSONObject(o.toString());
                String pf = x.optString("pngFile", "");
                if (!pf.isEmpty()) {
                    x.remove("pngFile");
                    File f = new File(pngDir, pf);
                    if (f.exists()) x.put("png", Base64.encodeToString(readAll(f), Base64.NO_WRAP));
                    else x.put("text", x.optString("text") + "(写真のファイルが見つかりません)");
                }
                items.put(x);
            }
            JSONObject body = new JSONObject();
            body.put("token", token());
            body.put("device", device());
            body.put("app", BuildConfig.APPLICATION_ID);
            body.put("version", String.valueOf(BuildConfig.VERSION_CODE));
            body.put("items", items);
            JSONObject res;
            try {
                res = new JSONObject(http(URL_, body.toString()));
            } catch (Exception e) {
                lastSend = clock() + " 送れませんでした(" + e.getMessage() + ")。溜めておきます";
                return;
            }
            if (!res.optBoolean("ok", false)) {
                String err = res.optString("error", "?");
                lastSend = clock() + ("token".equals(err) ? " 受け口に断られました(合言葉が違います)" : " 受け口でエラー: " + err);
                return;
            }
            synchronized (Remote.class) {
                for (JSONObject o : batch) {
                    if (queue.remove(o)) queueBytes -= o.toString().length();
                    String pf = o.optString("pngFile", "");
                    if (!pf.isEmpty()) new File(pngDir, pf).delete();
                }
                scheduleSave();
            }
            lastSend = clock() + " " + batch.size() + " 件送りました";
        }
    }

    /** 指示を取りに行く。受け取った指示は Diag が実行し、結果を記録に返す。 */
    private static void pollNow() throws Exception {
        if (!configured()) return;
        String q = URL_ + (URL_.contains("?") ? "&" : "?") + "token=" + token()
                + "&device=" + java.net.URLEncoder.encode(device(), "UTF-8");
        JSONObject res;
        try {
            res = new JSONObject(http(q, null));
        } catch (Exception e) {
            lastPoll = clock() + " 取りに行けませんでした(" + e.getMessage() + ")";
            return;
        }
        if (!res.optBoolean("ok", false)) {
            lastPoll = clock() + " 受け口に断られました(" + res.optString("error", "?") + ")";
            return;
        }
        JSONArray cmds = res.optJSONArray("commands");
        int n = cmds == null ? 0 : cmds.length();
        lastPoll = clock() + " 指示 " + n + " 件";
        for (int i = 0; i < n; i++) {
            JSONObject c = cmds.getJSONObject(i);
            Diag.run(app, c.optString("command", ""), c.optInt("row", -1));
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return readAll(in, 20 << 20); }
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > max) throw new Exception("応答が大きすぎます"); }
        return b.toByteArray();
    }

    /**
     * Apps Script は、受け付けたあと別の場所(googleusercontent)へ転送して結果を返す。
     * 転送は自分でたどる(POST の本文は最初の1回だけ送り、転送先は GET で読む)。
     */
    static String http(String url, String postBody) throws Exception {
        String u = url;
        byte[] body = postBody == null ? null : postBody.getBytes("UTF-8");
        for (int hop = 0; hop < 5; hop++) {
            if (!u.startsWith("https://")) throw new Exception("https ではない転送先");
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(20000);
            h.setReadTimeout(60000);
            h.setRequestProperty("User-Agent", "duo-updater");
            if (body != null) {
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = h.getOutputStream()) { out.write(body); }
            }
            int code = h.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                if (loc == null) throw new Exception("転送先がありません");
                u = new URL(new URL(u), loc).toString();
                body = null; // 転送先は GET で読む
                continue;
            }
            if (code != 200) { h.disconnect(); throw new Exception("HTTP " + code); }
            try (InputStream in = h.getInputStream()) { return new String(readAll(in, 1 << 20), "UTF-8"); }
        }
        throw new Exception("転送が多すぎます");
    }
}
