package com.yuki.duocruise.main;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * リアルタイムの更新。新しい版ができた瞬間に CI が送る合図を待ち、届いたら更新アプリに「今すぐ確認」を頼む。
 *
 * ・合図の中継は ntfy.sh(公開の通知サービス)。口の名前はビルド時に入る秘密の文字列(BuildConfig.PUSH_TOPIC)
 * ・送られる中身は「check」の一言だけ。受けても起きるのは確認だけで、入るのは GitHub の署名済みの版だけ
 * ・口は開いたまま(1 本の長い HTTP)。切れたら 5 秒から 60 秒まで間をあけながらつなぎ直す
 * ・待つのは Duo Cruise 本体(いつも前面で動いている)。更新アプリは裏では Android に止められやすいため
 * ・口の名前が無いビルド(手元で作ったもの)では動かない。そのときは 15 分ごとの確認と、記録シートの指示で更新する
 */
final class InstantUpdate {
    private static final String CHECK_ACTION = "com.yuki.duocruise.updater.CHECK";
    private static final String UPDATER = "com.yuki.duocruise.updater";

    private final Context app;
    private volatile boolean running;
    private Thread thread;

    InstantUpdate(Context c) { app = c.getApplicationContext(); }

    void start() {
        if (running || BuildConfig.PUSH_TOPIC.isEmpty()) return;
        running = true;
        thread = new Thread(this::loop, "duo-instant-update");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        long wait = 5_000;
        while (running) {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL("https://ntfy.sh/" + BuildConfig.PUSH_TOPIC + "/raw").openConnection();
                h.setConnectTimeout(15_000);
                h.setReadTimeout(120_000); // ntfy は 45 秒ごとに空の行を送るので、2 分黙ったら切れたとみなす
                h.setRequestProperty("User-Agent", "DuoCruise");
                BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8));
                wait = 5_000;
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.trim().equals("check")) requestCheck();
                }
            } catch (Exception e) {
                if (!running) break;
            } finally {
                if (h != null) h.disconnect();
            }
            try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
            wait = Math.min(wait * 2, 60_000);
        }
    }

    private void requestCheck() {
        DuoLog.log("リアルタイム更新: 新しい版の合図を受けました。更新アプリに今すぐ確認を頼みます");
        Intent i = new Intent(CHECK_ACTION).setPackage(UPDATER);
        app.sendBroadcast(i);
    }
}
