package com.yuki.duocruise.updater;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * GitHub のリリースを見て、入っているアプリより新しければ、取得して入れる。
 * ・ふだんは正式版(latest)だけを見る
 * ・「テスト版を受け取る」がオンなら、テスト版(prerelease)も含めた一番新しいものを見る
 * ・「1つ前の版に戻す」は、正式版に付いている「1つ前の版を同じ版番号で作り直したもの」を入れる
 *   (Android は版番号を下げられないため、版番号は今と同じにして中身だけ1つ前にする)
 */
final class UpdateEngine {
    static final String REPO_API = "https://api.github.com/repos/loverhetoric-gif/Duo-Cruise";
    static final String RELEASE_API = REPO_API + "/releases/latest";
    /** テスト版(変更の提案の段階で作ったもの)も含めた、新しい順の一覧。 */
    static final String RELEASES_API = REPO_API + "/releases?per_page=50";
    static final String ACTION = "com.yuki.duocruise.updater.INSTALL_RESULT";

    /** true のとき、テスト版も受け取る。 */
    static boolean testChannel(Context c) { return prefs(c).getBoolean("test", false); }
    static void setTestChannel(Context c, boolean v) { prefs(c).edit().putBoolean("test", v).apply(); }
    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("upd", Context.MODE_PRIVATE); }

    /** この時刻まで、ユーザー補助がインストール確認を自動で押す。 */
    static volatile long autoConfirmUntil = 0;
    /** 最後の確認の時刻と結果(状態の報告用)。 */
    static volatile String lastResult = "まだ確認していません";
    static volatile String lastRelease = "";
    private static final Object LOCK = new Object();

    static final class Item {
        String pkg, label, file, sha256, url;
        long versionCode, installed = -1, size;
        boolean needsUpdate() { return installed >= 0 && versionCode > installed; }
    }

    static final class Release {
        String name;
        JSONArray assets;
        JSONObject manifest;
    }

    static long installedVersion(Context c, String pkg) {
        try {
            PackageInfo i = c.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? i.getLongVersionCode() : i.versionCode;
        } catch (PackageManager.NameNotFoundException e) { return -1; }
    }

    private static byte[] get(String u, int max) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "duo-updater");
        h.setRequestProperty("Accept", "application/vnd.github+json");
        if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode() + " " + u);
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > max) throw new Exception("too large"); }
            return b.toByteArray();
        }
    }

    private static String assetUrl(JSONArray assets, String name) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (name.equals(a.getString("name"))) {
                String u = a.getString("browser_download_url");
                return u.startsWith("https://") ? u : null;
            }
        }
        return null;
    }

    /** 見に行くリリースを1つ決めて、manifest.json まで読む。test=true ならテスト版も含めて一番新しいもの。 */
    static Release release(Context c, boolean test) throws Exception {
        JSONObject rel = null;
        if (test) {
            // GitHub の一覧は公開の新しい順に並ばない(正式版が先に来る)ので、公開時刻で一番新しいものを選ぶ
            JSONArray list = new JSONArray(new String(get(RELEASES_API, 4 << 20), "UTF-8"));
            String newest = "";
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if (o.optBoolean("draft", false)) continue;
                String at = o.optString("published_at", "");
                if (rel == null || at.compareTo(newest) > 0) { rel = o; newest = at; }
            }
            if (rel == null) throw new Exception("リリースがありません");
        } else {
            rel = new JSONObject(new String(get(RELEASE_API, 1 << 20), "UTF-8"));
        }
        Release r = new Release();
        r.name = rel.optString("tag_name", rel.optString("name", "?")) + (rel.optBoolean("prerelease", false) ? "(テスト版)" : "");
        r.assets = rel.getJSONArray("assets");
        String manifestUrl = assetUrl(r.assets, "manifest.json");
        if (manifestUrl == null) throw new Exception("manifest.json が見つかりません");
        r.manifest = new JSONObject(new String(get(manifestUrl, 1 << 20), "UTF-8"));
        return r;
    }

    /** manifest の一覧(apps または rollback)を Item にする。自分自身(更新アプリ)は最後に回す。 */
    static List<Item> items(Context c, Release r, String key) throws Exception {
        List<Item> out = new ArrayList<>();
        Item self = null;
        JSONArray apps = r.manifest.optJSONArray(key);
        if (apps == null) return out;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject o = apps.getJSONObject(i);
            Item it = new Item();
            it.pkg = o.getString("package");
            it.label = o.optString("label", it.pkg);
            it.versionCode = o.getLong("versionCode");
            it.file = o.getString("file");
            it.sha256 = o.getString("sha256").toLowerCase(Locale.ROOT);
            it.size = o.optLong("size", 0);
            it.url = assetUrl(r.assets, it.file);
            if (it.url == null) continue;
            it.installed = installedVersion(c, it.pkg);
            // 自分を入れると、その場でこのアプリが止まる。ほかを先に入れる
            if (it.pkg.equals(c.getPackageName())) self = it; else out.add(it);
        }
        if (self != null) out.add(self);
        return out;
    }

    /** 最新の一覧を取得する(入れはしない)。 */
    static List<Item> fetch(Context c) throws Exception {
        boolean test = testChannel(c);
        Release r = release(c, test);
        lastRelease = r.name;
        if (test) UpdLog.add("テスト版も含めて確認: " + r.name);
        return items(c, r, "apps");
    }

    private static String now() { return new SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(new Date()); }

    /** 確認して、新しいものを入れる。入れ始めた数を返す。 */
    static int checkAndInstall(Context c, boolean includeMissing) { return checkAndInstall(c, includeMissing, false); }

    /** OTTO を起動してから、本体を入れ替えてよい時間。これを過ぎた定期確認では本体を入れ替えない。 */
    static final long BOOT_WINDOW_MS = 10 * 60 * 1000;

    /**
     * periodic=true は 15 分ごとの定期確認。このときは、OTTO の起動直後(BOOT_WINDOW_MS 以内)を除き、
     * Duo Cruise 本体を入れ替えない。入れ替えると本体が強制終了され、運転中の CarPlay・YouTube が止まる
     * (2026-10-27、テスト版を出すたびに本体が落ち続けた)。本体は次に OTTO を起動したときに入れる。
     */
    static int checkAndInstall(Context c, boolean includeMissing, boolean periodic) {
        synchronized (LOCK) {
            UpdLog.init(c);
            int started = 0;
            try {
                UpdLog.add("確認を開始します");
                for (Item it : fetch(c)) {
                    boolean missing = it.installed < 0;
                    if (missing && !includeMissing) {
                        UpdLog.add(it.label + ": 未インストール(自動では入れません)");
                        continue;
                    }
                    if (!missing && !it.needsUpdate()) {
                        UpdLog.add(it.label + ": 最新です (v" + it.installed + ")");
                        continue;
                    }
                    if (periodic && it.pkg.equals("com.yuki.duocruise.main")
                            && android.os.SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) {
                        UpdLog.add(it.label + ": v" + it.versionCode + " があります。使用中に落とさないよう、次に OTTO を起動したときに入れます");
                        continue;
                    }
                    UpdLog.add(it.label + ": v" + it.installed + " → v" + it.versionCode + " を取得します");
                    File f = download(c, it);
                    UpdLog.add(it.label + ": 取得・照合OK。インストールします");
                    install(c, f, it);
                    started++;
                }
                UpdLog.add("確認を終了しました(インストール開始 " + started + " 件)");
                lastResult = now() + " " + lastRelease + " を確認、インストール開始 " + started + " 件";
            } catch (Exception e) {
                UpdLog.add("失敗: " + e.getMessage());
                lastResult = now() + " 失敗: " + e.getMessage();
            }
            return started;
        }
    }

    /**
     * 1つ前の正式版に戻す。正式版の manifest の rollback(1つ前の中身・今と同じ版番号)を入れる。
     * 今の正式版が入っているアプリだけが対象(テスト版が入っているもの・古いものは触らない)。
     */
    static int rollback(Context c) {
        synchronized (LOCK) {
            UpdLog.init(c);
            int started = 0;
            try {
                Release r = release(c, false);
                String of = r.manifest.optString("rollbackOf", "");
                List<Item> list = items(c, r, "rollback");
                if (list.isEmpty()) {
                    UpdLog.add("戻せる版がありません(" + r.name + " には1つ前の版が付いていません)");
                    return 0;
                }
                UpdLog.add("1つ前の版(" + (of.isEmpty() ? "?" : of) + ")に戻します");
                for (Item it : list) {
                    if (it.installed != it.versionCode) {
                        UpdLog.add(it.label + ": 今の正式版(v" + it.versionCode + ")ではないので戻しません (入っているのは v" + it.installed + ")");
                        continue;
                    }
                    File f = download(c, it);
                    UpdLog.add(it.label + ": 1つ前の版を取得・照合OK。入れ直します");
                    install(c, f, it);
                    started++;
                }
                UpdLog.add("戻す処理を終了しました(インストール開始 " + started + " 件)");
            } catch (Exception e) {
                UpdLog.add("戻す処理に失敗: " + e.getMessage());
            }
            return started;
        }
    }

    private static File download(Context c, Item it) throws Exception {
        File dir = c.getExternalCacheDir() != null ? c.getExternalCacheDir() : c.getCacheDir();
        File f = new File(dir, it.file);
        HttpURLConnection h = (HttpURLConnection) new URL(it.url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(60000);
        h.setRequestProperty("User-Agent", "duo-updater");
        if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        if (!sb.toString().equals(it.sha256)) { f.delete(); throw new Exception(it.label + ": ハッシュ不一致(破棄しました)"); }
        return f;
    }

    private static void install(Context c, File f, Item it) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(it.pkg);
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            autoConfirmUntil = System.currentTimeMillis() + 3 * 60 * 1000;
            Intent i = new Intent(c, InstallReceiver.class).setAction(ACTION).putExtra("label", it.label);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        }
    }
}
