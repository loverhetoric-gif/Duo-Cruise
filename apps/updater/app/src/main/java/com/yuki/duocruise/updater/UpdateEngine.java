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
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Upbeater から新しい版のまとめ(zip)を受け取り、入っているアプリより新しければ入れる(docs/Upbeater配布_設計書.md)。
 * ・まとめの中の manifest.json(tools/make_manifest.py の形)に、アプリごとのパッケージ・版番号・ファイル・SHA-256 がある
 * ・受け取ったまとめは、次の版が来るまで端末に置いておく(未導入のアプリを入れる・1つ前に戻すときに使う)
 * ・「1つ前の版に戻す」は、まとめに付いている「1つ前の版を同じ版番号で作り直したもの」を入れる
 *   (Android は版番号を下げられないため、版番号は今と同じにして中身だけ1つ前にする)
 */
final class UpdateEngine {
    static final String ACTION = "com.yuki.duocruise.updater.INSTALL_RESULT";

    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("upd", Context.MODE_PRIVATE); }

    /** この時刻まで、ユーザー補助がインストール確認を自動で押す。 */
    static volatile long autoConfirmUntil = 0;
    /** 最後の確認の時刻と結果(状態の報告用)。 */
    static volatile String lastResult = "まだ確認していません";
    static volatile String lastRelease = "";
    private static final Object LOCK = new Object();

    static final class Item {
        String pkg, label, file, sha256;
        File local;
        long versionCode, installed = -1, size;
        boolean needsUpdate() { return installed >= 0 && versionCode > installed; }
    }

    /** 端末に置いてある、受け取り済みのまとめ。 */
    static final class Release {
        String name;
        long seq;
        File dir;
        JSONObject manifest;
    }

    static long installedVersion(Context c, String pkg) {
        try {
            PackageInfo i = c.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? i.getLongVersionCode() : i.versionCode;
        } catch (PackageManager.NameNotFoundException e) { return -1; }
    }

    private static File bundleRoot(Context c) {
        File base = c.getExternalCacheDir() != null ? c.getExternalCacheDir() : c.getCacheDir();
        return new File(base, "upbeater");
    }

    /** 置いてあるまとめを読む。無ければ null。 */
    static Release cached(Context c) throws Exception {
        long seq = prefs(c).getLong("bundle_seq", 0);
        if (seq <= 0) return null;
        File dir = new File(bundleRoot(c), "current");
        File m = new File(dir, "manifest.json");
        if (!m.isFile()) return null;
        Release r = new Release();
        r.seq = seq;
        r.dir = dir;
        r.name = "版 " + seq;
        byte[] b = new byte[(int) Math.min(m.length(), 1 << 20)];
        try (InputStream in = new FileInputStream(m)) { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; }
        r.manifest = new JSONObject(new String(b, "UTF-8"));
        return r;
    }

    /**
     * Upbeater に照合し、新しい版があれば取得して置き換える。無ければ置いてあるまとめを使う。
     * 同じ版を取り直さない(入れ終わるまで通番は進めないため、同じ版を何度も勧められる)。
     */
    static Release release(Context c) throws Exception {
        Release have = cached(c);
        Upbeater.Offer o = Upbeater.check(c);
        if (o != null && (have == null || have.seq != o.seq)) {
            UpdLog.add("Upbeater に新しい版(版 " + o.seq + (o.rollback ? "・巻き戻し" : "") + ")があります。取得します");
            Upbeater.report(c, o.seq, "downloading", null);
            File root = bundleRoot(c);
            root.mkdirs();
            File next = new File(root, "next");
            Upbeater.download(c, o, next);
            File cur = new File(root, "current");
            Upbeater.deleteTree(cur);
            if (!next.renameTo(cur)) throw new Exception("まとめを置き換えられません");
            prefs(c).edit().putLong("bundle_seq", o.seq).apply();
            Upbeater.report(c, o.seq, "verifying", "まとめの署名・SHA-256 を確かめました");
            have = cached(c);
        }
        if (have == null) throw new Exception("Upbeater に配る版がまだありません");
        return have;
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
            it.local = new File(r.dir, it.file);
            if (it.file.contains("/") || !it.local.isFile()) continue;
            it.installed = installedVersion(c, it.pkg);
            // 自分を入れると、その場でこのアプリが止まる。ほかを先に入れる
            if (it.pkg.equals(c.getPackageName())) self = it; else out.add(it);
        }
        if (self != null) out.add(self);
        return out;
    }

    /** 最新の一覧を取得する(入れはしない)。 */
    static List<Item> fetch(Context c) throws Exception {
        Release r = release(c);
        lastRelease = r.name;
        current = r;
        return items(c, r, "apps");
    }

    /** いま使っているまとめ(入れ終わったかの判定と報告に使う)。 */
    private static Release current;

    private static String now() { return new SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(new Date()); }

    /** 確かめる間隔(合図の直後、20 秒後、60 秒後)。 */
    private static final long[] RETRY_MS = {0, 20_000, 40_000};
    private static final java.util.concurrent.atomic.AtomicBoolean realtimeBusy = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 新しい版ができた合図を受けたときの確認(リアルタイムの更新)。呼んだスレッドで最後まで動く。
     * 合図は更新アプリ自身(PushListener)と Duo Cruise の中継(CheckReceiver)の両方から届くので、
     * 確認中に届いた分は捨てる。公開の直後は新しい版がまだ配れる状態になっていないことがあるため、
     * 何も入らなければ少し待って確かめ直す。結果はすぐ記録シートへ送る。
     * @param from 合図の届き方(記録用)
     */
    static int checkRealtime(Context c, String from) {
        if (!realtimeBusy.compareAndSet(false, true)) {
            UpdLog.add("新しい版の合図(" + from + ")を受けました。すでに確認中なので、そちらに任せます");
            return 0;
        }
        try {
            UpdLog.add("新しい版の合図(" + from + ")を受けました。今すぐ確認します");
            int n = 0;
            for (long wait : RETRY_MS) {
                if (wait > 0) {
                    UpdLog.add("新しい版がまだ見えないので、" + wait / 1000 + " 秒後に確かめ直します");
                    Remote.kick();
                    Thread.sleep(wait);
                }
                n = checkAndInstall(c, false);
                if (n > 0) break;
            }
            Remote.own("log", "リアルタイム更新: " + lastResult + "(開始 " + n + " 件)");
            return n;
        } catch (InterruptedException e) {
            return 0;
        } finally {
            realtimeBusy.set(false);
            Remote.kick();
        }
    }

    /** 確認して、新しいものを入れる。入れ始めた数を返す。 */
    static int checkAndInstall(Context c, boolean includeMissing) { return checkAndInstall(c, includeMissing, false); }

    /**
     * periodic=true は 15 分ごとの定期確認。新しい版があれば、Duo Cruise 本体も含めてその場で入れ替える
     * (2026-10-29 本人: 更新は停車中の検証で行うもの。起動直後にしか入れ替えない制限は外した)。
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
                    UpdLog.add(it.label + ": v" + it.installed + " → v" + it.versionCode + " を取得します");
                    long t0 = System.currentTimeMillis();
                    File f = download(c, it);
                    UpdLog.add(it.label + ": 取得・照合OK(" + (f.length() / 1024) + " KB、"
                            + (System.currentTimeMillis() - t0) / 1000 + " 秒)。インストールします");
                    try {
                        install(c, f, it);
                    } catch (Exception e) {
                        throw new Exception(it.label + " v" + it.versionCode + " のインストールを始められません: " + e.getMessage(), e);
                    }
                    UpdLog.add(it.label + ": v" + it.versionCode + " のインストールを始めました(結果は続けて届きます)");
                    started++;
                }
                UpdLog.add("確認を終了しました(インストール開始 " + started + " 件)");
                lastResult = now() + " " + lastRelease + " を確認、インストール開始 " + started + " 件";
                settle(c, started);
            } catch (Exception e) {
                UpdLog.add("失敗: " + e.getMessage());
                lastResult = now() + " 失敗: " + e.getMessage();
                Release r = current;
                if (r != null && r.seq > Upbeater.appliedSeq(c)) Upbeater.report(c, r.seq, "failed", e.getMessage());
            }
            return started;
        }
    }

    /**
     * まとめの中のアプリが、すべてまとめの版(以上)になっていれば、その版を入れ終えたとして Upbeater に知らせる。
     * 入れ始めたものがあるときは結果を待つ(次の確認で判定する。更新アプリ自身は入れ替えで止まるため)。
     */
    private static void settle(Context c, int started) throws Exception {
        Release r = current;
        if (r == null || r.seq <= Upbeater.appliedSeq(c)) return;
        if (started > 0) { Upbeater.report(c, r.seq, "installing", started + " 件のインストールを始めました"); return; }
        for (Item it : items(c, r, "apps")) if (it.installed < it.versionCode) return;
        Upbeater.markApplied(c, r.seq);
        Upbeater.report(c, r.seq, "installed", "まとめのアプリをすべて入れ終えました");
        UpdLog.add("版 " + r.seq + " を入れ終えました(Upbeater に知らせました)");
    }

    /**
     * 1つ前の版に戻す。置いてあるまとめの manifest の rollback(1つ前の中身・今と同じ版番号)を入れる。
     * 今のまとめの版が入っているアプリだけが対象(古いものは触らない)。
     */
    static int rollback(Context c) {
        synchronized (LOCK) {
            UpdLog.init(c);
            int started = 0;
            try {
                Release r = cached(c);
                if (r == null) { UpdLog.add("戻せる版がありません(まだ版を受け取っていません)"); return 0; }
                String of = r.manifest.optString("rollbackOf", "");
                List<Item> list = items(c, r, "rollback");
                if (list.isEmpty()) {
                    UpdLog.add("戻せる版がありません(" + r.name + " には1つ前の版が付いていません)");
                    return 0;
                }
                UpdLog.add("1つ前の版(" + (of.isEmpty() ? "?" : of) + ")に戻します");
                for (Item it : list) {
                    if (it.installed != it.versionCode) {
                        UpdLog.add(it.label + ": 今の版(v" + it.versionCode + ")ではないので戻しません (入っているのは v" + it.installed + ")");
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

    /** まとめから取り出したファイルの SHA-256 を、manifest の値と照らし合わせる。 */
    private static File download(Context c, Item it) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(it.local)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        if (!Upbeater.hex(md.digest()).equals(it.sha256)) throw new Exception(it.label + ": ハッシュ不一致(入れません)");
        return it.local;
    }

    /**
     * このアプリが作ったまま残っているインストールのセッションを捨てる。
     * 確認の画面で止まった・途中で落ちたセッションは閉じられずに残り、50 個たまると
     * 「Too many active sessions」で何も入れられなくなる(2026-10-29 車で発生)。
     */
    static int abandonLeftovers(Context c) {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int n = 0;
        for (PackageInstaller.SessionInfo si : pi.getMySessions()) {
            try { pi.abandonSession(si.getSessionId()); n++; } catch (Exception ignored) {}
        }
        if (n > 0) UpdLog.add("残っていたインストールのセッションを " + n + " 個片付けました");
        return n;
    }

    private static synchronized void install(Context c, File f, Item it) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        abandonLeftovers(c);
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(it.pkg);
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        boolean committed = false;
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
            committed = true;
        } finally {
            if (!committed) try { pi.abandonSession(id); } catch (Exception ignored) {}
        }
    }
}
