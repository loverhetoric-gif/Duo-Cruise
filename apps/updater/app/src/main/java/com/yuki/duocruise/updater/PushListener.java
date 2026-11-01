package com.yuki.duocruise.updater;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * リアルタイムの更新(更新アプリ自身が待つ側)。新しい版ができた瞬間に CI が送る合図を待ち、届いたらすぐ確認する。
 *
 * ・合図の中継は ntfy.sh。口の名前はビルド時に入る秘密の文字列(BuildConfig.PUSH_TOPIC。Duo Cruise と同じ)
 * ・送られる中身は「check」の一言だけ。受けても起きるのは確認だけで、入るのは GitHub の署名済みの版だけ
 * ・口は開いたまま(1 本の長い HTTP)。切れたら 5 秒から 60 秒まで間をあけながらつなぎ直す
 * ・更新アプリが Android に止められている間は届かない。そのときは Duo Cruise の中継(CheckReceiver)が起こす
 * ・口の名前が無いビルド(手元で作ったもの)では動かない
 */
final class PushListener {
    private static Thread thread;

    private PushListener() {}

    static synchronized void start(Context c) {
        if (thread != null || BuildConfig.PUSH_TOPIC.isEmpty()) return;
        Context app = c.getApplicationContext();
        thread = new Thread(() -> loop(app), "duo-push");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop(Context app) {
        long wait = 5_000;
        boolean announced = false;
        while (true) {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL("https://ntfy.sh/" + BuildConfig.PUSH_TOPIC + "/raw").openConnection();
                h.setConnectTimeout(15_000);
                h.setReadTimeout(120_000); // ntfy は 45 秒ごとに空の行を送るので、2 分黙ったら切れたとみなす
                h.setRequestProperty("User-Agent", "DuoCruiseMarket");
                BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8));
                if (!announced) { UpdLog.add("リアルタイム更新: 新しい版の合図を待ち始めました"); announced = true; }
                wait = 5_000;
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().equals("check")) {
                        new Thread(() -> UpdateEngine.checkRealtime(app, "更新アプリが直接"), "duo-check-now").start();
                    }
                }
            } catch (Exception ignored) {
                // 回線が無い・切れた。間をあけてつなぎ直す
            } finally {
                if (h != null) h.disconnect();
            }
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            wait = Math.min(wait * 2, 60_000);
        }
    }
}
