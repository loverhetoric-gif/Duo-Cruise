package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 仮の CarPlay(B の CarPlayPane が届くまでの代わり)。
 * 届いたキーを画面に出すだけ。キーの振り分けが正しいかを、実機で目で見て確かめられる。
 */
final class StubCarPane implements Pane {
    private final LinearLayout root;
    private final TextView keyLine;
    private int count;

    StubCarPane(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Theme.CAR_BG);
        TextView t = new TextView(c);
        t.setText("CarPlay");
        t.setTextColor(Theme.TEXT);
        t.setTextSize(28);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        TextView s = new TextView(c);
        s.setText("準備中(仮の画面)");
        s.setTextColor(Theme.SUB);
        s.setTextSize(15);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Math.round(6 * d), 0, Math.round(14 * d));
        root.addView(s);
        keyLine = new TextView(c);
        keyLine.setTextColor(Theme.TEXT);
        keyLine.setTextSize(16);
        keyLine.setGravity(Gravity.CENTER);
        keyLine.setText("キーはまだ届いていません");
        root.addView(keyLine);
    }

    @Override public View view() { return root; }
    @Override public void start() { DuoLog.log("CarPlay(仮): 開始"); }
    @Override public void resume() {}
    @Override public void pause() {}
    @Override public void destroy() {}

    @Override public boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
        count++;
        keyLine.setText("届いたキー: " + KeyMap.keyName(e.getKeyCode()) + "(" + count + "回目)");
        return true;
    }

    @Override public String status() { return "仮の部品(届いたキー " + count + " 回)"; }
    @Override public String command(String cmd) { return null; }
    @Override public String name() { return "CarPlay"; }
    @Override public boolean real() { return false; }
}
