package com.yuki.duocruise.main;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 走行の記録(JSONL・動画クリップ)を、機器の SIM から Cloudflare の Worker に送って R2 に貯める。
 * 送り先とトークンは CI の秘密から BuildConfig に入る(手元ビルドでは空＝送らない。公開リポジトリに鍵は置かない)。
 *
 * 送るのは「閉じたファイル」だけ(書き込み中の走行ファイルは避ける)。送れたものは trace/sent/ へ移して二度送りしない。
 * 走行中は CarPlay の通信(iPhone 側)とは別の SIM だが、無駄打ちを避けるため、起動時と駐車したときにまとめて送る。
 */
final class Uploader {
    private static final String URL_BASE = BuildConfig.UPLOAD_URL, TOKEN = BuildConfig.UPLOAD_TOKEN;
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);

    private static HandlerThread th;
    private static Handler h;
    private static volatile boolean busy;

    private Uploader() {}

    static boolean enabled() { return URL_BASE != null && !URL_BASE.isEmpty() && TOKEN != null && !TOKEN.isEmpty(); }

    /** 送れるものを送る(起動時・駐車時などに呼ぶ)。重ねて呼ばれても1本だけ走る。 */
    static synchronized void sweep(Context c) {
        if (!enabled()) return;
        if (th == null) { th = new HandlerThread("duo-upload"); th.start(); h = new Handler(th.getLooper()); }
        final Context app = c.getApplicationContext();
        h.post(() -> run(app));
    }

    private static void run(Context app) {
        if (busy) return;
        busy = true;
        int sent = 0;
        try {
            File dir = new File(app.getExternalFilesDir(null), "trace");
            File sentDir = new File(dir, "sent");
            //noinspection ResultOfMethodCallIgnored
            sentDir.mkdirs();
            String active = Trace.activeName();
            sent += uploadFrom(dir, sentDir, active);                 // 走行ファイル(*.jsonl)
            sent += uploadFrom(new File(dir, "clips"), sentDir, null); // 動画クリップ(*.mp4)
        } catch (Throwable t) {
            DuoLog.error("アップロード: " + DuoLog.why(t));
        } finally {
            busy = false;
        }
        if (sent > 0) DuoLog.log("走行の記録: " + sent + " 件アップロードしました");
    }

    /** dir の中の閉じたファイルを送り、送れたら sentDir へ移す。active と一致するファイルは避ける。 */
    private static int uploadFrom(File dir, File sentDir, String active) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (!f.isFile()) continue;                       // sent/ clips/ などは飛ばす
            if (active != null && f.getName().equals(active)) continue; // 書き込み中は送らない
            String key = DAY.format(new Date(f.lastModified())) + "/" + f.getName();
            if (put(f, key)) {
                File to = new File(sentDir, f.getName());
                if (f.renameTo(to) || f.delete()) n++;       // 送れたら移す(移せなければ消して二度送りを防ぐ)
            }
        }
        return n;
    }

    /** 1ファイルを Worker に PUT(Bearer トークン)。2xx で成功。 */
    private static boolean put(File f, String key) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(URL_BASE + "/" + key).openConnection();
            con.setRequestMethod("PUT");
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setRequestProperty("Authorization", "Bearer " + TOKEN);
            con.setRequestProperty("Content-Type", mime(f.getName()));
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(f.length());
            try (FileInputStream in = new FileInputStream(f); OutputStream out = con.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            int code = con.getResponseCode();
            if (code / 100 == 2) return true;
            DuoLog.error("アップロード失敗(" + code + "): " + key);
            return false;
        } catch (Throwable t) {
            DuoLog.error("アップロード失敗: " + key + " " + DuoLog.why(t)); // 次の掃き出しでまた試す
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String mime(String name) {
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".jsonl")) return "application/x-ndjson";
        return "application/octet-stream";
    }
}
