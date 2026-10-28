package com.yuki.duocruise.main;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.yuki.duocruise.carplay.CarPlayPane;
import com.yuki.duocruise.main.tv.TvPane;

/**
 * 左右の部品を用意する。左＝B の CarPlayPane、右＝D の TvPane を、DuoActivity から同じ形(Pane)で扱えるようにする。
 * 部品を作れなかったときだけ、理由を出す枠(Broken)を置く(画面全体が落ちないように)。
 */
final class Panes {
    private Panes() {}

    static Pane car(Activity a) {
        try {
            return new Car(new CarPlayPane(a, new CarPlayPane.Listener() {
                /** 状態が変わった。画面の記録にだけ出す(更新アプリへは CarPlayPane が送り済み)。 */
                @Override public void onStatus(String status) {
                    DuoLog.local("state", "CarPlay の状態: " + status);
                    DuoActivity.stateChanged();
                }

                /** 記録した内容。画面の記録にだけ出す(更新アプリへは CarPlayPane が送り済み)。 */
                @Override public void onRecord(String kind, String text) {
                    DuoLog.local(kind, "CarPlay: " + text);
                }

                /** CarPlay の音。ナビ・電話・Siri のときに YouTube の音量を下げる。 */
                @Override public void onAudio(String audioType, boolean active) {
                    DuoActivity.carAudio(audioType, active);
                }
            }));
        } catch (Throwable t) {
            DuoLog.error("CarPlay の部品を作れません: " + DuoLog.why(t));
            return new Broken(a, "CarPlay", DuoLog.why(t));
        }
    }

    static Pane tv(Activity a) {
        try {
            return new Tv(new TvPane(a));
        } catch (Throwable t) {
            DuoLog.error("YouTube の部品を作れません: " + DuoLog.why(t));
            return new Broken(a, "YouTube", DuoLog.why(t));
        }
    }

    /** CarPlay に要る実行時の権限。 */
    static String[] carPermissions() { return CarPlayPane.requiredPermissions(); }

    /** 左: 自作 CarPlay。 */
    private static final class Car implements Pane {
        private final CarPlayPane p;
        Car(CarPlayPane p) { this.p = p; }
        @Override public View view() { return p.view(); }
        @Override public void start() { p.start(); }
        @Override public void resume() {}
        @Override public void pause() {}
        /** Wi-Fi・Bluetooth・チップを手放す(MultiPlay に返す)。 */
        @Override public void destroy() { p.stop(); }
        @Override public boolean onKey(KeyEvent e) { return p.onKey(e); }
        @Override public boolean onGenericMotion(MotionEvent e) { return p.onGenericMotion(e); }
        @Override public String status() { return p.status(); }
        @Override public String command(String cmd) {
            return "car-wifi-reset".equals(cmd) ? p.resetWifiPass() : null;
        }
        @Override public void scroll(float dx, float dy) { p.scroll(dx, dy); }
        @Override public void scrollEnd() { p.scrollEnd(); }
        /** OTTO の Wi-Fi のパスワードを入力中のときだけ使う。 */
        @Override public void typeText(String text) { p.typeText(text); }
        @Override public void typeKey(int keyCode) { p.typeKey(keyCode); }
        @Override public String name() { return "CarPlay"; }
        @Override public boolean real() { return true; }
    }

    /** 右: TV 版 YouTube。作った時点で読み込みを始める。後ろに回っても再生は止めない。 */
    private static final class Tv implements Pane {
        private final TvPane p;
        Tv(TvPane p) { this.p = p; }
        @Override public View view() { return p.view(); }
        @Override public void start() {}
        @Override public void resume() { p.onResume(); }
        @Override public void pause() { p.onPause(); }
        @Override public void destroy() { p.destroy(); }
        @Override public boolean onKey(KeyEvent e) { return p.onKey(e); }
        @Override public boolean onGenericMotion(MotionEvent e) { return false; }
        @Override public String status() { return p.status(); }
        @Override public String command(String cmd) { return p.command(cmd); }
        @Override public void duck(float level) { p.setDuck(level); }
        @Override public void hold(boolean on) { p.setHold(on); }
        @Override public void typeText(String text) { p.typeText(text); }
        @Override public void typeKey(int keyCode) { p.typeKey(keyCode); }
        @Override public void scroll(float dx, float dy) { p.scroll(dx, dy); }
        @Override public void scrollEnd() { p.scrollEnd(); }
        @Override public String name() { return "YouTube"; }
        @Override public boolean real() { return true; }
    }

    /** 部品を作れなかったとき、その枠に理由を出す。 */
    private static final class Broken implements Pane {
        private final TextView v;
        private final String name, why;

        Broken(Context c, String name, String why) {
            this.name = name;
            this.why = why;
            v = new TextView(c);
            v.setText(name + " を開けませんでした\n" + why);
            v.setTextColor(Theme.SUB);
            v.setTextSize(15);
            v.setGravity(Gravity.CENTER);
            v.setBackgroundColor(Theme.BG);
        }

        @Override public View view() { return v; }
        @Override public void start() {}
        @Override public void resume() {}
        @Override public void pause() {}
        @Override public void destroy() {}
        @Override public boolean onKey(KeyEvent e) { return false; }
        @Override public boolean onGenericMotion(MotionEvent e) { return false; }
        @Override public String status() { return "作れません: " + why; }
        @Override public String command(String cmd) { return name + " の部品がありません"; }
        @Override public String name() { return name; }
        @Override public boolean real() { return false; }
    }
}
