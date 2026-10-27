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
 * 汎用モード：ユーザー補助で、OTTO のホームや他のアプリを、Duo Pad のカーソルで操作する。
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

    // スクロール(2本指・ホイール)
    private boolean scrolling;
    private float scFromX, scFromY, scToX, scToY;
    private final Runnable scrollEnd = this::endScroll;
    private final Runnable hide = this::hideCursor;
    private boolean warnedText, warnedPinch;

    SystemPad(AccessibilityService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        cursor = new Arrow(s);
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
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        w = Math.max(1, dm.widthPixels);
        h = Math.max(1, dm.heightPixels);
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
            case "w": wheel(a); break;
            case "p": pinchUnsupported(); break;
            case "k":
                switch (a[1]) {
                    case "b": svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); break;
                    case "h": svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); break;
                    case "r": svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
                }
                break;
            case "t": case "x": case "e": textUnsupported(); break;
            default: break; // r / h / i などは無視
        }
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

    private void wheel(String[] a) {
        if (a[1].equals("end")) { endScroll(); return; }
        if (dragging) return;
        show();
        if (!scrolling) { scrolling = true; scFromX = x; scFromY = y; scToX = x; scToY = y; }
        scToX = PadInput.clamp(scToX + f(a[1]), 0, w - 1);
        scToY = PadInput.clamp(scToY + f(a[2]), 0, h - 1);
        x = scToX; y = scToY; place();
        main.removeCallbacks(scrollEnd);
        main.postDelayed(scrollEnd, 150);
    }

    private void endScroll() {
        main.removeCallbacks(scrollEnd);
        if (!scrolling) return;
        scrolling = false;
        if (scFromX != scToX || scFromY != scToY) swipe(scFromX, scFromY, scToX, scToY, SCROLL_MS);
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

    private void pinchUnsupported() {
        if (!warnedPinch) { warnedPinch = true; DuoLog.log("汎用モードでは2本指操作は未対応"); }
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
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        try { wm.updateViewLayout(cursor, lp); } catch (Exception ignored) {}
    }

    private void hideCursor() {
        if (added) cursor.setVisibility(View.INVISIBLE);
    }

    private static float f(String s) { return Float.parseFloat(s); }

    /** 白い矢印に黒い縁。左上の角が指している場所。 */
    private static final class Arrow extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float dp;

        Arrow(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            fill.setColor(Color.WHITE);
            edge.setColor(Color.BLACK);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.5f * dp);
            edge.setStrokeJoin(Paint.Join.ROUND);
            float u = dp;
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 17 * u);
            arrow.lineTo(4.5f * u, 13 * u);
            arrow.lineTo(7.5f * u, 20 * u);
            arrow.lineTo(10 * u, 19 * u);
            arrow.lineTo(7 * u, 12 * u);
            arrow.lineTo(12.5f * u, 12 * u);
            arrow.close();
            setWillNotDraw(false);
        }

        @Override protected void onMeasure(int wm, int hm) {
            int s = Math.round(24 * dp);
            setMeasuredDimension(s, s);
        }

        @Override protected void onDraw(Canvas cv) {
            cv.save();
            cv.translate(1.5f * dp, 1.5f * dp);
            cv.drawPath(arrow, fill);
            cv.drawPath(arrow, edge);
            cv.restore();
        }
    }
}
