package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/**
 * タッチパッド(iPhone の Duo Pad)の指示を、Duo Cruise の画面への操作に変える。メインスレッドだけで使う。
 *
 * 指示(1行1つ。数は OTTO の画面の px):
 *   m dx dy          … カーソルを動かす(加速は iPhone 側で済ませてある。小数のまま足すので細かい動きも失わない)
 *   c                … カーソルの場所を押して離す(タップ)
 *   d / u            … カーソルの場所を押したまま(ドラッグの始まり)/ 離す。押している間の m は指を動かす
 *   p d sx sy        … 2本指の始まり。2本目の指は1本目(カーソル)から (sx, sy) ずれた場所
 *   p m ax ay bx by  … 2本指それぞれの動き(スクロール・ピンチ)
 *   p u              … 2本指を離す
 *   w dx dy / w end  … スクロール(Mac の2本指・ホイール)。カーソルの場所で指を置いて動かしたのと同じ形にする
 *   k b|h|r          … 戻る / ホーム / タスク
 *   t 文字           … 文字を打つ(iPhone のキーボードで確定した文字)
 *   x / e            … 1文字消す / 改行(決定)
 *   r 平均 最大 再接続 … iPhone が測った往復の時間(ms)と、つなぎ直した回数(記録に残す)
 *   h 版 名前        … つながった直後のあいさつ
 *
 * 押す操作は、画面に指で触れたのと同じ形(タッチ)で、Duo Cruise の土台へ渡す。
 * そのため YouTube・CarPlay・操作盤のどれにも同じように届く。
 */
final class PadInput implements PadServer.Sink {
    interface Host {
        View root();
        /** 押す・離すを渡す先(土台)。 */
        boolean touch(MotionEvent e);
        void back();
        void globalAction(int action);
        void typeText(String text);
        void typeKey(int keyCode);
        /** スクロール中(本当のなぞり)。この間は「操作する側」を切り替えない。 */
        void scrollActive(boolean on);
        /** メニュー(設定)を開く。タスクボタン長押しから。 */
        void openMenu();
        /** 2本指スクロール(操作中の画面へ)。 */
        void scroll(float dx, float dy);
        void scrollEnd();
    }

    private final Host host;
    private final PadCursor cursor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float x = -1, y = -1;
    private long downTime;
    private boolean dragging;
    private final Runnable hide = this::hideCursor;
    static final long HIDE_MS = 6000;

    PadInput(Context c, Host host) {
        this.host = host;
        cursor = new PadCursor(c);
        cursor.setVisibility(View.INVISIBLE);
    }

    View cursorView() { return cursor; }

    private void hideCursor() { cursor.setVisibility(View.INVISIBLE); }

    @Override public int[] screen() {
        View r = host.root();
        return new int[]{Math.max(1, r.getWidth()), Math.max(1, r.getHeight())};
    }

    @Override public void connected(boolean on, String who) {
        DuoLog.log("タッチパッド: " + (on ? "つながりました(" + who + ")" : "切れました"));
        if (!on) {
            if (dragging) { send(MotionEvent.ACTION_UP); dragging = false; }
            if (scrolling) endWheel();
            cursor.setVisibility(View.INVISIBLE);
        }
        DuoActivity.stateChanged();
    }

    @Override public void line(String s) {
        try {
            handle(s);
        } catch (Throwable t) {
            DuoLog.error("タッチパッド: 指示を扱えません「" + (s.length() > 40 ? s.substring(0, 40) : s) + "」 " + DuoLog.why(t));
        }
    }

    private void handle(String s) {
        if (s.isEmpty()) return;
        String[] a = s.split(" ");
        switch (a[0]) {
            case "m": move(f(a[1]), f(a[2])); break;
            case "c": show(); send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_UP); break;
            case "d": show(); if (!dragging) { dragging = true; send(MotionEvent.ACTION_DOWN); } break;
            case "u": if (dragging) { dragging = false; send(MotionEvent.ACTION_UP); } break;
            case "p": // iPhone の2本指 → スクロール(1本指のなぞり)にする
                if (a.length > 1) switch (a[1]) {
                    case "m": if (a.length > 5) scrollStep((f(a[2]) + f(a[4])) / 2f, (f(a[3]) + f(a[5])) / 2f); break;
                    case "u": endWheel(); break;
                    default: break; // "d"(指を2本置いただけ)は、動いてから始める
                }
                break;
            case "w": // Mac の2本指スクロール
                if (a[1].equals("end")) endWheel(); else scrollStep(f(a[1]), f(a[2]));
                break;
            case "k":
                switch (a[1]) {
                    case "b": host.back(); break;
                    case "h": host.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME); break;
                    case "r": host.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS); break;
                    case "m": host.openMenu(); break; // タスクボタン長押し → 設定
                }
                break;
            case "t": if (s.length() > 2) host.typeText(s.substring(2)); break;
            case "x": host.typeKey(android.view.KeyEvent.KEYCODE_DEL); break;
            case "e": host.typeKey(android.view.KeyEvent.KEYCODE_ENTER); break;
            case "r": DuoLog.state("タッチパッド: 往復 平均" + a[1] + "ms 最大" + a[2] + "ms つなぎ直し" + a[3] + "回"); break;
            case "h": DuoLog.log("タッチパッド: あいさつ " + s.substring(2)); break;
            default: break;
        }
    }

    private void move(float dx, float dy) {
        // 2本指スクロール中は、カーソルを動かさない(スクロールにカーソルが追従しないように)
        if (scrolling) return;
        show();
        int[] wh = screen();
        x = clamp(x + dx, 0, wh[0] - 1);
        y = clamp(y + dy, 0, wh[1] - 1);
        cursor.setTranslationX(x);
        cursor.setTranslationY(y);
        if (dragging) send(MotionEvent.ACTION_MOVE);
    }

    // ── スクロール: 指を置いて(カーソルの場所)、動かした分だけずらし、止まったら離す ──
    private boolean scrolling;
    private float wx, wy;
    private long wheelDown;
    private final Runnable wheelEnd = this::endWheel;

    /** スクロール1歩。操作中の画面へ、その画面本来の送り方(矢印・ダイヤル)で渡す。カーソルは動かさない。 */
    private void scrollStep(float dx, float dy) {
        if (dragging) return;
        if (!scrolling) { scrolling = true; host.scrollActive(true); }
        host.scroll(dx, dy);
        main.removeCallbacks(wheelEnd);
        main.postDelayed(wheelEnd, 150);
    }

    private void endWheel() {
        main.removeCallbacks(wheelEnd);
        if (!scrolling) return;
        scrolling = false;
        host.scrollActive(false);
        host.scrollEnd();
    }

    private void touchAt(int action, long down, float px, float py) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        host.touch(e);
        e.recycle();
    }

    private void show() {
        if (x < 0) { int[] wh = screen(); x = wh[0] / 2f; y = wh[1] / 2f; cursor.setTranslationX(x); cursor.setTranslationY(y); }
        cursor.setVisibility(View.VISIBLE);
        cursor.bringToFront();
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_MS);
    }

    private void send(int action) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent e = MotionEvent.obtain(downTime, now, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        host.touch(e);
        e.recycle();
    }


    static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }

    private static float f(String s) { return Float.parseFloat(s); }


}
