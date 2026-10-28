package com.yuki.duocruise.main;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * DuoActivity の土台。左右2つの枠・操作側の枠線・操作盤を、SplitGeometry の計算どおりに置く。
 * 入れ替えは「置き場所を変えて並べ直す」だけなので、中身(YouTube など)は読み込み直さない。
 */
final class SplitRoot extends ViewGroup {
    interface Listener {
        /** 並べた結果の大きさ(記録用)。 */
        void laidOut(int w, int h, String car, String tv);
        /** 操作盤が出ていないときに、画面のどちらかが触られた。 */
        void touched(int side);
    }

    private final float dp;
    private final Listener listener;
    private final Frame frame;
    View car, tv;
    /** タッチパッドのカーソル(いちばん上に置く)。 */
    View cursor;
    /** スクロール中は、触れた側への切り替えをしない。 */
    boolean padScrolling;
    PanelView panel;
    boolean swapped, panelHalf, single;
    int side;
    /** YouTube の幅の割合(%)。50＝半々。 */
    int ytPct = 50;
    private String lastSizes = "";
    /** 入れ替えの動き: 並べ直す前の左端。並べ直した後、ここから新しい場所へ動かす。 */
    private int swapFromCar = Integer.MIN_VALUE, swapFromTv = Integer.MIN_VALUE;
    private ValueAnimator swapAnim;
    static final long SWAP_MS = 380, GLOW_MS = 700;

    SplitRoot(Context c, Listener l) {
        super(c);
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.BG);
        frame = new Frame(c);
    }

    void setPanes(View carView, View tvView, PanelView p) {
        removeAllViews();
        car = carView;
        tv = tvView;
        panel = p;
        addView(car);
        addView(tv);
        addView(frame);
        addView(panel);
        if (cursor != null) { if (cursor.getParent() != null) ((ViewGroup) cursor.getParent()).removeView(cursor); addView(cursor); }
    }

    /** 並べ直す(入れ替え・操作側・操作盤の場所が変わったとき)。 */
    /** 1画面にするか。apply の前に呼ぶ。 */
    void setSingle(boolean v) { single = v; }

    /** 1画面のとき YouTube を出しているか。 */
    private boolean tvShown() { return SplitGeometry.tvOn(side, swapped); }

    private int[] carRect(int w, int h) {
        if (single) return tvShown() ? SplitGeometry.offscreen(w, h) : SplitGeometry.single(w, h, false);
        // YouTube が全画面に近いとき、CarPlay は幅0になり映像面が壊れるので、画面の外へ置く
        if (ytPct >= 98) return SplitGeometry.offscreen(w, h);
        return SplitGeometry.car(w, h, swapped, ytPct);
    }

    private int[] tvRect(int w, int h) {
        if (single) return tvShown() ? SplitGeometry.single(w, h, true) : SplitGeometry.offscreen(w, h);
        if (ytPct >= 98) return SplitGeometry.single(w, h, true);
        return SplitGeometry.tv(w, h, swapped, ytPct);
    }

    /** 操作している枠(線と光を描く場所)。 */
    int[] activeRect(int w, int h) {
        if (single) return SplitGeometry.single(w, h, tvShown());
        if (ytPct >= 98) return SplitGeometry.single(w, h, tvShown());
        return SplitGeometry.activeHalf(w, h, side, swapped, ytPct);
    }

    void apply(boolean swapped, int side, boolean panelHalf, int ytPct) {
        if (swapped != this.swapped && car != null && tv != null && car.getWidth() > 0) {
            swapFromCar = car.getLeft();
            swapFromTv = tv.getLeft();
        }
        this.swapped = swapped;
        this.side = side;
        this.panelHalf = panelHalf;
        this.ytPct = ytPct;
        requestLayout();
        frame.invalidate();
    }

    void showLabel(String text) { frame.label(text); }

    /** 操作側を切り替えた直後: 操作側の輪郭を細い虹色の光が一瞬だけ囲う。 */
    void glow() { frame.glow(); }

    /**
     * 入れ替えの動き: 2つの枠が左右に「くるっと」すれ違う。
     * 片方は少し小さく(奥を通る)、もう片方は少し大きく(手前を通る)なりながら、新しい場所へ動く。
     * CarPlay の映像は SurfaceView のため、回転ではなく移動と拡大縮小だけで見せる。
     */
    private void startSwapAnimation() {
        final int dCar = swapFromCar - car.getLeft(), dTv = swapFromTv - tv.getLeft();
        swapFromCar = swapFromTv = Integer.MIN_VALUE;
        if (swapAnim != null) swapAnim.cancel();
        final View back = car, front = tv;
        swapAnim = ValueAnimator.ofFloat(0f, 1f);
        swapAnim.setDuration(SWAP_MS);
        swapAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        swapAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float arc = (float) Math.sin(Math.PI * f); // 真ん中で一番ふくらむ
            // 移動だけ(拡大縮小は CarPlay の映像面と YouTube の描き直しが重く、カクついた)。
            // すれ違う感じは、手前を通る側を少しだけ上へ逃がして出す
            back.setTranslationX(dCar * (1 - f));
            front.setTranslationX(dTv * (1 - f));
            front.setTranslationY(-6 * dp * arc);
            frame.moving = f < 1f;
        });
        swapAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                for (View v : new View[]{back, front}) { v.setTranslationX(0); v.setTranslationY(0); }
                front.setLayerType(View.LAYER_TYPE_NONE, null);
                frame.moving = false;
                frame.invalidate();
            }
        });
        front.setLayerType(View.LAYER_TYPE_HARDWARE, null); // 動く間は描き直さず、絵として動かす
        front.bringToFront();
        frame.bringToFront();
        if (cursor != null) cursor.bringToFront();
        if (panel != null) panel.bringToFront();
        swapAnim.start();
    }

    private int[] inner(int w, int h, int[] r) {
        // 余白(WindowInsets を padding として受けている)を足す
        return new int[]{r[0] + getPaddingLeft(), r[1] + getPaddingTop(), r[2] + getPaddingLeft(), r[3] + getPaddingTop()};
    }

    private int contentW() { return Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight()); }
    private int contentH() { return Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom()); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        int w = contentW(), h = contentH();
        measureTo(car, carRect(w, h));
        measureTo(tv, tvRect(w, h));
        measureTo(frame, new int[]{0, 0, w, h});
        if (panel != null) measureTo(panel, SplitGeometry.panel(w, h, panelHalf && !single, side));
        if (cursor != null) cursor.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
    }

    private static void measureTo(View v, int[] r) {
        if (v == null) return;
        v.measure(MeasureSpec.makeMeasureSpec(r[2] - r[0], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r[3] - r[1], MeasureSpec.EXACTLY));
    }

    private void layoutTo(View v, int[] r) {
        if (v == null) return;
        int[] a = inner(0, 0, r);
        v.layout(a[0], a[1], a[2], a[3]);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = contentW(), h = contentH();
        int[] c = carRect(w, h), v = tvRect(w, h);
        layoutTo(car, c);
        layoutTo(tv, v);
        layoutTo(frame, new int[]{0, 0, w, h});
        if (panel != null) layoutTo(panel, SplitGeometry.panel(w, h, panelHalf && !single, side));
        if (cursor != null) cursor.layout(getPaddingLeft(), getPaddingTop(),
                getPaddingLeft() + cursor.getMeasuredWidth(), getPaddingTop() + cursor.getMeasuredHeight());
        if (swapFromCar != Integer.MIN_VALUE) startSwapAnimation();
        String sizes = w + "×" + h + " car=" + SplitGeometry.str(c) + " tv=" + SplitGeometry.str(v);
        if (!sizes.equals(lastSizes)) {
            lastSizes = sizes;
            listener.laidOut(w, h, SplitGeometry.str(c), SplitGeometry.str(v));
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!padScrolling && e.getActionMasked() == MotionEvent.ACTION_DOWN && (panel == null || panel.getVisibility() != VISIBLE)) {
            float x = e.getX() - getPaddingLeft();
            listener.touched(x < contentW() / 2f ? SplitGeometry.SIDE_LEFT : SplitGeometry.SIDE_RIGHT);
        }
        return false;
    }

    /** 操作している側の周りの線(3dp)と、切り替えたときに少しだけ出る札。 */
    private final class Frame extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rainbow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix spin = new Matrix();
        private ValueAnimator glowAnim;
        /** 虹色の光の強さ(0〜1)と、光の回り具合(0〜1)。 */
        private float glow = 0, turn = 0;
        /** 入れ替えで動いている間は、操作側の線を描かない。 */
        boolean moving;
        private String label = "";
        private float alpha = 0;
        private final Runnable fade = new Runnable() {
            @Override public void run() {
                alpha -= 0.1f;
                if (alpha > 0) postDelayed(this, 30); else alpha = 0;
                invalidate();
            }
        };

        Frame(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3 * dp);
            line.setColor(Theme.ACCENT);
            pill.setColor(Theme.ACCENT);
            text.setColor(Color.WHITE);
            text.setTextSize(16 * dp);
            text.setFakeBoldText(true);
            rainbow.setStyle(Paint.Style.STROKE);
            rainbow.setStrokeWidth(2 * dp);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(7 * dp);
            setWillNotDraw(false);
        }

        void glow() {
            if (glowAnim != null) glowAnim.cancel();
            glowAnim = ValueAnimator.ofFloat(0f, 1f);
            glowAnim.setDuration(GLOW_MS);
            glowAnim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                turn = f;
                glow = f < 0.2f ? f / 0.2f : 1 - (f - 0.2f) / 0.8f; // すっと出て、ゆっくり消える
                invalidate();
            });
            glowAnim.start();
        }

        private void drawGlow(Canvas cv, RectF box) {
            if (glow <= 0) return;
            float cx = box.centerX(), cy = box.centerY();
            SweepGradient g = new SweepGradient(cx, cy, new int[]{
                    0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7FF, 0xFF5856D6, 0xFFFF2D9A, 0xFFFF3B30}, null);
            spin.setRotate(360 * turn, cx, cy); // 光が輪郭を回る
            g.setLocalMatrix(spin);
            rainbow.setShader(g);
            halo.setShader(g);
            rainbow.setAlpha(Math.round(255 * glow));
            halo.setAlpha(Math.round(70 * glow));
            float r = 6 * dp;
            cv.drawRoundRect(box, r, r, halo);
            cv.drawRoundRect(box, r, r, rainbow);
        }

        void label(String s) {
            label = s;
            alpha = 1;
            removeCallbacks(fade);
            postDelayed(fade, 1400);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            int[] r = activeRect(w, h);
            float s = line.getStrokeWidth() / 2;
            // 操作側の太い線は常には描かない(本人: 邪魔)。切り替えた直後の虹色の光だけ
            if ((panel == null || panel.getVisibility() != VISIBLE) && !moving) {
                RectF box = new RectF(r[0] + s, r[1] + s, r[2] - s, r[3] - s);
                drawGlow(cv, box);
            }
            if (alpha > 0 && !label.isEmpty()) {
                float tw = text.measureText(label);
                float pw = tw + 28 * dp, ph = 36 * dp;
                float cx = (r[0] + r[2]) / 2f, top = r[1] + 16 * dp;
                pill.setAlpha(Math.round(230 * alpha));
                text.setAlpha(Math.round(255 * alpha));
                cv.drawRoundRect(new RectF(cx - pw / 2, top, cx + pw / 2, top + ph), ph / 2, ph / 2, pill);
                cv.drawText(label, cx - tw / 2, top + ph / 2 + text.getTextSize() * 0.35f, text);
            }
        }
    }
}
