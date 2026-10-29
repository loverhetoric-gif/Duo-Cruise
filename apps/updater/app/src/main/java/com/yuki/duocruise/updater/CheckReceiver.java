package com.yuki.duocruise.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 今すぐ確認して、新しい版があれば入れる。
 * 新しい版ができた瞬間に CI が送る合図を、Duo Cruise が受けてここへ届ける(リアルタイムの更新の中継)。
 * 更新アプリ自身も PushListener で同じ合図を待っている。こちらは、更新アプリが Android に止められていたときの起こし役。
 * 送れるのは同じ署名のアプリだけ(マニフェストで権限 LOG を要求)。
 */
public class CheckReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yuki.duocruise.updater.CHECK";

    @Override public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        Remote.init(c);
        UpdLog.init(c);
        Context app = c.getApplicationContext();
        PushListener.start(app); // 止められていたなら、自分でも待ち始める
        // 確かめ直しで 1 分ほどかかるので、受信はすぐ終えて、このスレッドで続ける(受信の持ち時間は短い)
        new Thread(() -> UpdateEngine.checkRealtime(app, "Duo Cruise の中継"), "duo-check-now").start();
    }
}
