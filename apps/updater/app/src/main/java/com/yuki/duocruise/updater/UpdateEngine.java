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

/** GitHubの最新リリースを見て、入っているアプリより新しければ、取得して入れる。 */
final class UpdateEngine {
    static final String RELEASE_API = "https://api.github.com/repos/loverhetoric-gif/Duo-Cruise/releases/latest";
    /** テスト版(変更の提案の段階で作ったもの)も含めた、新しい順の一覧。 */
    static final String RELEASES_API = "https://api.github.com/repos/loverhetoric-gif/Duo-Cruise/releases?per_page=5";

    /** true のとき、テスト版も受け取る。 */
    static boolean testChannel(Context c) { return c.getSharedPreferences("upd", Context.MODE_PRIVATE).getBoolean("test", false); }
    static void setTestChannel(Context c, boolean v) { c.getSharedPreferences("upd", Context.MODE_PRIVATE).edit().putBoolean("test", v).apply(); }
    static final String ACTION = "com.yuki.duocruise.updater.INSTALL_RESULT";
    /** この時刻まで、ユーザー補助がインストール確認を自動で押す。 */
    static volatile long autoConfirmUntil = 0;
    private static final Object LOCK = new Object();

    static final class Item {
        String pkg, label, file, sha256, url;
        long versionCode, installed = -1, size;
        boolean needsUpdate() { return installed >= 0 && versionCode > installed; }
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

    /** 最新の一覧を取得する(入れはしない)。 */
    static java.util.List<Item> fetch(Context c) throws Exception {
        JSONObject rel;
        if (testChannel(c)) {
            JSONArray list = new JSONArray(new String(get(RELEASES_API, 4 << 20), "UTF-8"));
            if (list.length() == 0) throw new Exception("リリースがありません");
            rel = list.getJSONObject(0);
            UpdLog.add("テスト版も含めて確認: " + rel.optString("name", "?"));
        } else {
            rel = new JSONObject(new String(get(RELEASE_API, 1 << 20), "UTF-8"));
        }
        JSONArray assets = rel.getJSONArray("assets");
        String manifestUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if ("manifest.json".equals(a.getString("name"))) manifestUrl = a.getString("browser_download_url");
        }
        if (manifestUrl == null) throw new Exception("manifest.json が見つかりません");
        JSONObject m = new JSONObject(new String(get(manifestUrl, 1 << 20), "UTF-8"));
        JSONArray apps = m.getJSONArray("apps");
        java.util.List<Item> out = new java.util.ArrayList<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject o = apps.getJSONObject(i);
            Item it = new Item();
            it.pkg = o.getString("package");
            it.label = o.optString("label", it.pkg);
            it.versionCode = o.getLong("versionCode");
            it.file = o.getString("file");
            it.sha256 = o.getString("sha256").toLowerCase();
            it.size = o.optLong("size", 0);
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                if (it.file.equals(a.getString("name"))) it.url = a.getString("browser_download_url");
            }
            if (it.url == null) continue;
            if (!it.url.startsWith("https://")) continue;
            it.installed = installedVersion(c, it.pkg);
            out.add(it);
        }
        return out;
    }

    /** 確認して、新しいものを入れる。入れた数を返す。 */
    static int checkAndInstall(Context c, boolean includeMissing) {
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
                    UpdLog.add(it.label + ": v" + it.installed + " → v" + it.versionCode + " を取得します");
                    File f = download(c, it);
                    UpdLog.add(it.label + ": 取得・照合OK。インストールします");
                    install(c, f, it);
                    started++;
                }
                UpdLog.add("確認を終了しました(インストール開始 " + started + " 件)");
            } catch (Exception e) {
                UpdLog.add("失敗: " + e.getMessage());
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
