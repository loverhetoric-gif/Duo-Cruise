package com.yuki.duocruise.main;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * コマンダーのキーを受けて、いま操作している側へ届ける。
 * ・登録した「切替キー」を長押し → 操作する側(左右)を切り替え、枠で表示
 * ・登録した「入替キー」を長押し → 左右の窓を入れ替え
 * ・操作側が YouTube のとき → キーを YouTube の窓へ届ける
 * ・操作側が CarPlay のとき → キーはそのまま CarPlay へ通す
 */
public class PaneService extends AccessibilityService {
    static final long LONG_MS = 600;
    /** 次に押されたキーを登録する(0=しない / 1=切替キー / 2=入替キー)。 */
    static volatile int learn = 0;

    private static volatile PaneService live;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View frame;
    private int heldKey = 0;
    private boolean heldFired = false;
    private Runnable heldTask;

    static boolean isLive() { return live != null; }

    static void refreshFrame() {
        PaneService s = live;
        if (s != null) s.main.post(s::drawFrame);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        live = this;
        Store.log("操作の切り替え(ユーザー補助)が動き始めました");
        drawFrame();
    }

    @Override public boolean onUnbind(android.content.Intent i) {
        live = null;
        removeFrame();
        return super.onUnbind(i);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override public void onInterrupt() {}

    /** 画面上のその側に、YouTube があるか。 */
    private boolean tvOn(int side) {
        boolean sw = Store.swapped(this);
        return sw ? side == Store.SIDE_LEFT : side == Store.SIDE_RIGHT;
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();

        // キーの登録
        if (learn != 0 && e.getAction() == KeyEvent.ACTION_DOWN) {
            if (learn == 1) Store.setSwitchKey(this, code); else Store.setSwapKey(this, code);
            Store.log((learn == 1 ? "切替キー" : "入替キー") + "を登録: " + KeyEvent.keyCodeToString(code));
            learn = 0;
            return true;
        }
        if (learn != 0) return true;

        // 長押しの判定(切替キー・入替キー)
        int sw = Store.switchKey(this), sp = Store.swapKey(this);
        if (code != 0 && (code == sw || code == sp)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                heldKey = code;
                heldFired = false;
                final boolean isSwap = code == sp;
                heldTask = () -> {
                    heldFired = true;
                    if (isSwap) Layout.swap(this); else toggleSide();
                };
                main.postDelayed(heldTask, LONG_MS);
                return true; // 短く押しただけなら、離したときに通常のキーとして届ける
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) return true; // 押しっぱなしの繰り返し
            if (e.getAction() == KeyEvent.ACTION_UP && code == heldKey) {
                main.removeCallbacks(heldTask);
                heldKey = 0;
                if (heldFired) return true;
                // 短押し: 操作側が YouTube なら、押す・離すをまとめて届ける
                if (tvOn(Store.side(this))) {
                    TvActivity.deliver(new KeyEvent(e.getDownTime(), e.getDownTime(), KeyEvent.ACTION_DOWN, code, 0));
                    TvActivity.deliver(e);
                }
                return true;
            }
        }

        // 操作側が YouTube のときは、YouTube の窓へ届ける
        if (tvOn(Store.side(this)) && TvActivity.isLive()) {
            return TvActivity.deliver(e);
        }
        return false; // CarPlay 側: そのまま通す
    }

    private void toggleSide() {
        int now = Store.side(this) == Store.SIDE_LEFT ? Store.SIDE_RIGHT : Store.SIDE_LEFT;
        Store.setSide(this, now);
        Store.log("操作する側: " + (now == Store.SIDE_LEFT ? "左" : "右") + (tvOn(now) ? "(YouTube)" : "(CarPlay)"));
        drawFrame();
        if (!tvOn(now)) focusCarWindow(now);
    }

    /** CarPlay の窓にキーが届くよう、その窓の上の端を一度だけ軽く押して前に出す。 */
    private void focusCarWindow(int side) {
        Rect r = Layout.half(this, side);
        Path p = new Path();
        p.moveTo(r.centerX(), r.top + 6);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 40)).build();
        dispatchGesture(g, null, null);
    }

    /** 操作している側に枠を出す。 */
    private void drawFrame() {
        removeFrame();
        if (!TvActivity.isLive()) return;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Rect r = Layout.half(this, Store.side(this));
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        int px = Math.round(3 * getResources().getDisplayMetrics().density);
        g.setStroke(px, Color.parseColor("#4F8CFF"));
        g.setCornerRadius(px * 2);
        v.setBackground(g);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                r.width(), r.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = r.left;
        lp.y = r.top;
        try { wm.addView(v, lp); frame = v; } catch (Exception ex) { Store.log("枠を出せません: " + ex.getMessage()); }
    }

    private void removeFrame() {
        if (frame == null) return;
        try { ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(frame); } catch (Exception ignored) {}
        frame = null;
    }
}
