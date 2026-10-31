package com.yuki.duocruise.main;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 汎用モード：ユーザー補助で、OTTO のホームや他のアプリを、Duo Slate のカーソルで操作する。
 * メインスレッドだけで使う。
 *
 * ・カーソルは「ユーザー補助の重ね表示(TYPE_ACCESSIBILITY_OVERLAY)」で出す。他アプリの上に出す許可は要らない
 * ・押す・ドラッグ・スクロールは、ユーザー補助の dispatchGesture(本物の指のなぞり)で行う
 * ・戻る・ホーム・タスクは performGlobalAction
 * ・できないこと：特に守られた画面(パスワード欄など)は、ユーザー補助でも操作できないことがある。
 *   文字入力は、汎用モードでは今は未対応(Duo Cruise の中だけ)
 */
final class SystemPad implements PadServer.Sink {
    private static final long TAP_MS = 1;
    private static final long DRAG_MIN_MS = 60;
    private static final long SCROLL_MS = 120;
    private static final long HIDE_MS = PadInput.HIDE_MS;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final View cursor;
    private final WindowManager.LayoutParams lp;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int w = 1, h = 1;
    private float x = -1, y = -1;
    private boolean added;

    // ドラッグ(押したまま動かす)
    private boolean dragging;
    private float downX, downY;
    private long downAt;

    // スクロール(2本指・Mac のホイール): たまった動きを本物のなぞりにして送る。カーソルは動かさない
    private boolean scrolling;
    private float scOX, scOY, scAX, scAY;
    private boolean gestureBusy;
    private static final float SCROLL_STEP = 40;
    private final Runnable scrollEnd = this::endScroll;
    private final Runnable hide = this::hideCursor;
    private boolean warnedText;

    SystemPad(AccessibilityService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        cursor = new PadCursor(s);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        measure();
    }

    private void measure() {
        // 画面全体(1480×480)で測る。
        // そうしないとカーソルが画面の一番下まで行けない(2026-10-28 実機)。
        try {
            android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
            w = Math.max(1, b.width());
            h = Math.max(1, b.height());
        } catch (Throwable t) {
            DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            w = Math.max(1, dm.widthPixels);
            h = Math.max(1, dm.heightPixels);
        }
    }

    /** ユーザー補助が止まるとき。重ね表示を消す。 */
    void dispose() {
        main.post(() -> {
            main.removeCallbacks(hide);
            main.removeCallbacks(scrollEnd);
            if (added) { try { wm.removeViewImmediate(cursor); } catch (Exception ignored) {} added = false; }
        });
    }

    @Override public int[] screen() {
        measure();
        return new int[]{w, h};
    }

    @Override public void connected(boolean on, String who) {
        if (!on) main.post(() -> {
            dragging = false;
            if (scrolling) endScroll();
            hideCursor();
        });
    }

    @Override public void line(String s) {
        main.post(() -> {
            try { handle(s); }
            catch (Throwable t) { DuoLog.error("汎用タッチパッド: 指示を扱えません " + DuoLog.why(t)); }
        });
    }

    private void handle(String s) {
        if (s.isEmpty()) return;
        String[] a = s.split(" ");
        switch (a[0]) {
            case "m": move(f(a[1]), f(a[2])); break;
            case "c": show(); tap(x, y); break;
            case "d": show(); startDrag(); break;
            case "u": endDrag(); break;
            case "w": // Mac の2本指スクロール
                if (a.length > 1 && a[1].equals("end")) endScroll();
                else if (a.length > 2) scrollBy(f(a[1]), f(a[2]));
                break;
            case "p": // iPhone の2本指スクロール
                if (a.length > 1) switch (a[1]) {
                    case "d": scrollBegin(); break;
                    case "m": if (a.length > 5) scrollBy((f(a[2]) + f(a[4])) / 2f, (f(a[3]) + f(a[5])) / 2f); break;
                    case "u": endScroll(); break;
                }
                break;
            case "k":
                switch (a[1]) {
                    case "b": svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); break;
                    case "h": svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); break;
                    case "r": svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
                    case "m": openDuo(); break; // タスク長押し → Duo Cruise を前面に(設定はその中)
                }
                break;
            case "t": case "x": case "e": textUnsupported(); break;
            default: break; // r / h / i などは無視
        }
    }

    /** 汎用モードでタスク長押し: Duo Cruise を前面に出す(設定メニューはその中)。 */
    private void openDuo() {
        try {
            android.content.Intent i = new android.content.Intent(svc, DuoActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("openMenu", true);
            svc.startActivity(i);
        } catch (Throwable t) { DuoLog.error("メニューを開けません " + DuoLog.why(t)); }
    }

    private void move(float dx, float dy) {
        show();
        x = PadInput.clamp(x + dx, 0, w - 1);
        y = PadInput.clamp(y + dy, 0, h - 1);
        place();
    }

    private void startDrag() {
        if (dragging) return;
        dragging = true;
        downX = x; downY = y; downAt = android.os.SystemClock.uptimeMillis();
    }

    /** 押したところから、今の場所まで、1回のなぞりにして送る(アイコンの並べ替えやページ送りに使える)。 */
    private void endDrag() {
        if (!dragging) return;
        dragging = false;
        long dur = Math.max(DRAG_MIN_MS, android.os.SystemClock.uptimeMillis() - downAt);
        swipe(downX, downY, x, y, dur);
    }

    private void scrollBegin() {
        if (scrolling) return;
        scrolling = true;
        scOX = (x < 0) ? w / 2f : x;
        scOY = (y < 0) ? h / 2f : y;
        scAX = 0; scAY = 0;
    }

    private void scrollBy(float dx, float dy) {
        if (dragging) return;
        scrollBegin();
        scAX += dx; scAY += dy;
        flushScroll();
        main.removeCallbacks(scrollEnd);
        main.postDelayed(scrollEnd, 200);
    }

    /** たまりが十分あって、前のなぞりが終わっていれば、1回ぶんのスクロールを送る(カーソルは動かさない)。 */
    private void flushScroll() {
        if (!scrolling || gestureBusy) return;
        if (Math.hypot(scAX, scAY) < SCROLL_STEP) return;
        float toX = PadInput.clamp(scOX + scAX, 0, w - 1);
        float toY = PadInput.clamp(scOY + scAY, 0, h - 1);
        scAX = 0; scAY = 0;
        swipeScroll(scOX, scOY, toX, toY);
    }

    private void endScroll() {
        main.removeCallbacks(scrollEnd);
        if (!scrolling) return;
        if (!gestureBusy && (scAX != 0 || scAY != 0)) {
            float toX = PadInput.clamp(scOX + scAX, 0, w - 1);
            float toY = PadInput.clamp(scOY + scAY, 0, h - 1);
            scAX = 0; scAY = 0;
            swipeScroll(scOX, scOY, toX, toY);
        }
        scrolling = false;
    }

    private void swipeScroll(float x1, float y1, float x2, float y2) {
        if (x1 == x2 && y1 == y2) return;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, SCROLL_MS)).build();
            gestureBusy = true;
            svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { gestureBusy = false; main.post(() -> flushScroll()); }
                @Override public void onCancelled(GestureDescription d) { gestureBusy = false; }
            }, main);
        } catch (Throwable t) {
            gestureBusy = false;
            DuoLog.error("汎用タッチパッド: スクロールを送れません " + DuoLog.why(t));
        }
    }

    private void tap(float px, float py) {
        Path p = new Path();
        p.moveTo(px, py);
        dispatch(new GestureDescription.StrokeDescription(p, 0, TAP_MS));
    }

    private void swipe(float x1, float y1, float x2, float y2, long dur) {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 == x2 && y1 == y2) p.lineTo(x2 + 1, y2);
        else p.lineTo(x2, y2);
        dispatch(new GestureDescription.StrokeDescription(p, 0, Math.max(1, dur)));
    }

    private void dispatch(GestureDescription.StrokeDescription stroke) {
        try {
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            svc.dispatchGesture(g, null, null);
        } catch (Throwable t) {
            DuoLog.error("汎用タッチパッド: 操作を送れません " + DuoLog.why(t));
        }
    }

    private void textUnsupported() {
        if (!warnedText) { warnedText = true; DuoLog.log("汎用モードでは文字入力は未対応(Duo Cruise の中だけ)"); }
    }

    private void show() {
        if (x < 0) { x = w / 2f; y = h / 2f; }
        if (!added) {
            try { wm.addView(cursor, lp); added = true; } catch (Exception e) { DuoLog.error("汎用タッチパッド: カーソルを出せません " + DuoLog.why(e)); }
        }
        cursor.setVisibility(View.VISIBLE);
        place();
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_MS);
    }

    private void place() {
        if (!added) return;
        lp.x = Math.round(x - ((PadCursor) cursor).hot());
        lp.y = Math.round(y - ((PadCursor) cursor).hot());
        try { wm.updateViewLayout(cursor, lp); } catch (Exception ignored) {}
    }

    private void hideCursor() {
        if (added) cursor.setVisibility(View.INVISIBLE);
    }

    private static float f(String s) { return Float.parseFloat(s); }


}
