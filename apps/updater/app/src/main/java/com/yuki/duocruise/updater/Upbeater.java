package com.yuki.duocruise.updater;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Upbeater(自前の配布の仕組み)から新しい版を受け取る(docs/Upbeater配布_設計書.md)。
 * ・登録:記録シートの指示 updater:register <コード> で、一回限りのコードを端末のしるし(トークン)に換える
 * ・照合:今の通番を送り、署名つきのマニフェストを受け取る。署名は組み立て時に埋め込んだ公開鍵だけで確かめる
 *   (返事に含まれる公開鍵は信用しない)。決まりは Upbeater の hub/src/manifest.ts と同じ
 * ・取得:まとめ(zip)を取り、SHA-256 と大きさを確かめてから、決まった名前のファイルだけを取り出す
 * ・報告:導入の結果を知らせる(失敗しても更新は止めない)
 */
final class Upbeater {
    static final String HUB = BuildConfig.UPBEATER_HUB;
    /** Upbeater に登録したこのリポジトリの名前(Hub の apps の name と照らし合わせて app_id を決める)。 */
    static final String REPO = "loverhetoric-gif/duo-cruise";
    static final String OS = "android";
    /** まとめの大きさの上限(本体・更新アプリ・戻す用の版で 1 つ数十 MB)。 */
    static final long MAX_BUNDLE = 400L << 20;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9-]{4,64}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private Upbeater() {}

    /** 照合で受け取った、確かめ済みの版。 */
    static final class Offer {
        long seq;
        String sha256, url, commit;
        long size;
        boolean rollback;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("upbeater", Context.MODE_PRIVATE); }

    static boolean registered(Context c) { return !prefs(c).getString("token", "").isEmpty(); }
    static long appliedSeq(Context c) { return prefs(c).getLong("applied_seq", 0); }
    static void markApplied(Context c, long seq) { prefs(c).edit().putLong("applied_seq", seq).apply(); }

    static String status(Context c) {
        if (!registered(c)) return "未登録";
        return "登録済(版 " + appliedSeq(c) + ")";
    }

    // ---------- 登録 ----------

    /** 一回限りのコードで登録する。成功したら端末のしるしを、このアプリの中だけにしまう。 */
    static String register(Context c, String code) throws Exception {
        String k = code == null ? "" : code.trim();
        if (!CODE.matcher(k).matches()) throw new Exception("コードの形が違います");
        JSONObject body = new JSONObject().put("code", k).put("name", "Duo Cruise(" + Remote.device() + ")").put("os", OS);
        JSONObject r = new JSONObject(request("POST", "/v1/devices/register", null, body.toString(), 201));
        String token = r.getString("token");
        if (!token.startsWith("upb_d_")) throw new Exception("しるしの形が違います");
        prefs(c).edit().putString("token", token).putString("device_id", r.optString("device_id")).remove("app_id").apply();
        return r.optString("device_id");
    }

    /** Hub の apps から、このリポジトリの app_id を探す(配る版がまだ無いときは空)。 */
    private static String appId(Context c, String token) throws Exception {
        String saved = prefs(c).getString("app_id", "");
        if (!saved.isEmpty()) return saved;
        JSONObject r = new JSONObject(request("GET", "/v1/devices/apps", token, null, 200));
        JSONArray apps = r.optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject a = apps.getJSONObject(i);
            if (REPO.equalsIgnoreCase(a.optString("name"))) {
                String id = a.getString("app_id");
                prefs(c).edit().putString("app_id", id).apply();
                return id;
            }
        }
        return "";
    }

    // ---------- 照合 ----------

    /** 今の通番より新しい版(または署名つきの巻き戻し)があれば返す。無ければ null。 */
    static Offer check(Context c) throws Exception {
        String token = prefs(c).getString("token", "");
        if (token.isEmpty()) throw new Exception("Upbeater に未登録です(記録シートの指示 updater:register <コード> で登録します)");
        String app = appId(c, token);
        if (app.isEmpty()) return null;
        long current = appliedSeq(c);
        JSONObject req = new JSONObject().put("app_id", app).put("current_seq", current);
        JSONObject r = new JSONObject(request("POST", "/v1/devices/check", token, req.toString(), 200));
        if (!r.optBoolean("update", false)) return null;
        JSONObject signed = r.getJSONObject("manifest");
        return accept(signed, trustedKeys(), revoked(), app, current, System.currentTimeMillis());
    }

    /** 組み立て時に埋め込んだ公開鍵(世代番号 → base64url の 32 バイト)。 */
    static Map<Integer, byte[]> trustedKeys() throws Exception {
        Map<Integer, byte[]> out = new HashMap<>();
        String raw = BuildConfig.UPBEATER_PUBLIC_KEYS;
        if (raw == null || raw.trim().isEmpty()) return out;
        JSONObject o = new JSONObject(raw);
        JSONArray names = o.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String g = names.getString(i);
            out.put(Integer.parseInt(g), b64url(o.getString(g)));
        }
        return out;
    }

    static Set<Integer> revoked() {
        Set<Integer> out = new HashSet<>();
        for (String s : BuildConfig.UPBEATER_REVOKED.split(",")) {
            s = s.trim();
            if (s.matches("\\d+")) out.add(Integer.parseInt(s));
        }
        return out;
    }

    /**
     * 署名を確かめ、入れてよい版かを決める(hub/src/manifest.ts の verifyManifest・evaluateManifest と同じ決まり)。
     * 失効した世代・知らない世代・署名の違い・形の違い・別のアプリ・別の OS・期限切れ・同じ版・巻き戻しの指示の無い逆戻りは断る。
     */
    static Offer accept(JSONObject signed, Map<Integer, byte[]> trusted, Set<Integer> revoked, String app, long current, long now) throws Exception {
        int gen = signed.getInt("key_generation");
        if (revoked.contains(gen)) throw new Exception("失効した鍵の署名です");
        byte[] pub = trusted.get(gen);
        if (pub == null) throw new Exception("知らない鍵の署名です(世代 " + gen + ")");
        byte[] payload = b64url(signed.getString("payload"));
        byte[] sig = b64url(signed.getString("signature"));
        if (!Ed25519.verify(pub, payload, sig)) throw new Exception("署名が合いません");
        JSONObject b = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (b.optInt("v") != 1 || b.optInt("key_generation", -1) != gen) throw new Exception("マニフェストの形が違います");
        if (!app.equals(b.optString("app_id"))) throw new Exception("別のアプリの版です");
        if (!OS.equals(b.optString("os"))) throw new Exception("別の OS の版です");
        if (!(b.getLong("expires_at") > now)) throw new Exception("期限の切れたマニフェストです(端末の時計を確かめてください)");
        Offer o = new Offer();
        o.seq = b.getLong("seq");
        JSONObject rb = b.optJSONObject("rollback");
        if (o.seq <= current) {
            if (o.seq == current) throw new Exception("同じ版です");
            if (rb == null || rb.optLong("to_seq", -1) != o.seq) throw new Exception("巻き戻しの指示の無い古い版です");
            o.rollback = true;
        }
        o.sha256 = b.getString("sha256").toLowerCase(Locale.ROOT);
        if (!o.sha256.matches("[0-9a-f]{64}")) throw new Exception("SHA-256 の形が違います");
        o.size = b.getLong("size");
        if (o.size <= 0 || o.size > MAX_BUNDLE) throw new Exception("大きさが違います");
        o.url = b.getString("url");
        if (!o.url.startsWith(HUB + "/v1/artifacts/")) throw new Exception("取得先が Upbeater ではありません");
        o.commit = b.optString("commit_sha");
        return o;
    }

    // ---------- 取得 ----------

    /** まとめを取り、大きさと SHA-256 を確かめて、決まった名前のファイルだけを dir に取り出す。 */
    static void download(Context c, Offer o, File dir) throws Exception {
        File zip = new File(dir.getParentFile(), "bundle-" + o.seq + ".zip");
        HttpURLConnection h = (HttpURLConnection) new URL(o.url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(60000);
        h.setRequestProperty("User-Agent", "duo-updater");
        if (h.getResponseCode() != 200) throw new Exception("取得 HTTP " + h.getResponseCode());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > o.size) throw new Exception("大きさが合いません");
                out.write(buf, 0, n);
                md.update(buf, 0, n);
            }
        } catch (Exception e) { zip.delete(); throw e; }
        if (total != o.size || !hex(md.digest()).equals(o.sha256)) { zip.delete(); throw new Exception("まとめのハッシュが合いません(破棄しました)"); }
        try { unzip(zip, dir, o.size); } finally { zip.delete(); }
    }

    /** 平らな(フォルダの無い)決まった形の名前だけを取り出す。合わない名前があれば全部やめる。 */
    static void unzip(File zip, File dir, long limit) throws Exception {
        deleteTree(dir);
        if (!dir.mkdirs()) throw new Exception("置き場所を作れません");
        long written = 0;
        int count = 0;
        try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!FILE.matcher(name).matches() || ++count > 20) throw new Exception("まとめに想定外のファイルがあります: " + name);
                File f = new File(dir, name);
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        written += n;
                        if (written > limit * 4) throw new Exception("まとめの中身が大きすぎます");
                        out.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception ex) { deleteTree(dir); throw ex; }
        if (!new File(dir, "manifest.json").isFile()) { deleteTree(dir); throw new Exception("まとめに manifest.json がありません"); }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ---------- 報告 ----------

    /** 導入の結果を知らせる(失敗しても止めない)。status は downloading・verifying・installing・installed・failed */
    static void report(Context c, long seq, String status, String message) {
        try {
            String token = prefs(c).getString("token", "");
            String app = prefs(c).getString("app_id", "");
            if (token.isEmpty() || app.isEmpty()) return;
            JSONObject b = new JSONObject().put("app_id", app).put("seq", seq).put("status", status);
            if (message != null && !message.isEmpty()) b.put("message", message.length() > 1900 ? message.substring(0, 1900) : message);
            request("POST", "/v1/devices/report", token, b.toString(), 200);
        } catch (Exception e) {
            UpdLog.add("Upbeater への報告に失敗: " + e.getMessage());
        }
    }

    // ---------- 通信 ----------

    private static String request(String method, String path, String token, String body, int expect) throws Exception {
        if (!HUB.startsWith("https://")) throw new Exception("Upbeater の住所が設定されていません");
        HttpURLConnection h = (HttpURLConnection) new URL(HUB + path).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestMethod(method);
        h.setRequestProperty("User-Agent", "duo-updater");
        h.setRequestProperty("Accept", "application/json");
        if (token != null) h.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = h.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        String text = "";
        if (in != null) try (InputStream s = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = s.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > (1 << 20)) throw new Exception("返事が大きすぎます"); }
            text = b.toString("UTF-8");
        }
        if (code != expect) {
            String why = text;
            try { JSONObject e = new JSONObject(text); why = e.optString("message", e.optString("error", text)); } catch (Exception ignored) {}
            if (code == 401) why = "端末のしるしが無効です(登録し直してください)";
            if (code == 503) why = "Upbeater の署名の準備ができていません";
            throw new Exception("Upbeater " + path + " HTTP " + code + " " + (why.length() > 200 ? why.substring(0, 200) : why));
        }
        return text;
    }

    static byte[] b64url(String s) {
        return java.util.Base64.getUrlDecoder().decode(s.trim().replace("=", ""));
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
