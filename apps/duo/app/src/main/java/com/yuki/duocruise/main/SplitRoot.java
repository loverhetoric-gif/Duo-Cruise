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
    static final long SWAP_MS = 380, GLOW_MS = 2400; // 光は Duo Pad と同じ 2.4 秒

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
        frame.requestLayout();
        frame.invalidate();
    }

    /**
     * メインビューのイベント発光(瞬間のみ)。操作中の画面の縁を、その色・強さで一瞬だけ光らせて消す。
     * 色・強さはイベントごとに設定で変えられる(ターゲット切替／接続完了／接続不良)。strength は 0〜1。
     */
    void glow(int color, float strength) { frame.glow(color, strength); }

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
                frame.update();
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
        if (panel != null && panel.getVisibility() == VISIBLE) {
            // メニュー表示中: メニューの中はメニューが扱う。外(後ろの YouTube など)への操作は一切通さない
            float x = e.getX(), y = e.getY();
            boolean inPanel = x >= panel.getLeft() && x < panel.getRight() && y >= panel.getTop() && y < panel.getBottom();
            return !inPanel; // 外側は横取りして捨てる
        }
        if (!padScrolling && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = e.getX() - getPaddingLeft();
            listener.touched(x < contentW() / 2f ? SplitGeometry.SIDE_LEFT : SplitGeometry.SIDE_RIGHT);
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        // メニュー表示中に横取りした「メニュー外」のタッチを飲み込む(後ろの画面を操作させない)
        return panel != null && panel.getVisibility() == VISIBLE;
    }

    /**
     * メインビューの縁の光。イベント(ターゲット切替=白・接続完了=青・接続不良=赤)の瞬間だけ、
     * 操作中の画面の縁を光らせて消す。常時は出さない(没入優先)。
     *
     * 作りは設定ビューと同じ三層(Duo Pad の band と同じ。NeonGlow の説明を参照):
     *   広い裾(46dp を GPU で 48 ぼかし)・中のにじみ(10dp を 11 ぼかし)・外側の細い線(1.6dp)。
     * 出方は Duo Pad の Envelope と同じ: 0.35 秒で灯り、1.3 秒まで保ち、2.4 秒でゆっくり消える。
     * その間、明るさの起伏が縁に沿って流れる(Duo Pad の spin e×90°/秒 = 0.25 周/秒)。
     * 四角(半径0)。ペインの端にぴたりと合わせる。
     */
    private final class Frame extends ViewGroup {
        private final NeonGlow line = new NeonGlow();
        private final GlowLayer wide, mid;
        private final RectF box = new RectF();
        private ValueAnimator glowAnim;
        private float edge = 0;                      // 出来事の光の強さ(0〜1)。Duo Pad の Envelope.edge
        private float phase = 0;                     // 明るさの起伏の位置(0〜1 で縁を一周)
        private float strength = 0.55f;              // そのイベントの発光の強さ(設定で変わる)
        private int color = Theme.NEON_WHITE;        // 発光の色(イベント・設定で変わる)
        /** 入れ替えで動いている間は縁を描かない。 */
        boolean moving;

        Frame(Context c) {
            super(c);
            setWillNotDraw(false);
            wide = new GlowLayer(c, 46, 48, 1, +1);
            mid = new GlowLayer(c, 10, 11, 1, -1);
            addView(wide);
            addView(mid);
        }

        /** イベントの瞬間だけ、操作中の画面の縁をその色・強さで光らせて消す。 */
        void glow(int c, float s) {
            color = c;
            strength = Math.max(0f, Math.min(1f, s));
            if (glowAnim != null) glowAnim.cancel();
            glowAnim = ValueAnimator.ofFloat(0f, GLOW_MS / 1000f);
            glowAnim.setDuration(GLOW_MS);
            glowAnim.setInterpolator(new android.view.animation.LinearInterpolator());
            glowAnim.addUpdateListener(a -> {
                float e = (float) a.getAnimatedValue();   // 経過秒
                float d = GLOW_MS / 1000f;
                edge = e < 0.35f ? e / 0.35f : e < 1.3f ? 1f : Math.max(0f, 1 - (e - 1.3f) / (d - 1.3f));
                phase = e * 0.25f;
                update();
            });
            glowAnim.start();
        }

        void update() {
            boolean off = edge <= 0 || moving || (panel != null && panel.getVisibility() == VISIBLE);
            float k = off ? 0 : edge * strength;
            wide.set(0.38f * k, NeonGlow.mix(color, 0xFFFFFFFF, 0.30f), phase);
            mid.set(0.60f * k, NeonGlow.mix(color, 0xFFFFFFFF, 0.15f), phase);
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
            int[] r = activeRect(getMeasuredWidth(), getMeasuredHeight());
            for (View v : new View[]{wide, mid}) v.measure(MeasureSpec.makeMeasureSpec(r[2] - r[0], MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(r[3] - r[1], MeasureSpec.EXACTLY));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int[] a = activeRect(r - l, b - t);
            wide.layout(a[0], a[1], a[2], a[3]);
            mid.layout(a[0], a[1], a[2], a[3]);
        }

        @Override protected void dispatchDraw(Canvas cv) {
            super.dispatchDraw(cv);
            if (edge <= 0 || moving || (panel != null && panel.getVisibility() == VISIBLE)) return;
            int[] r = activeRect(getWidth(), getHeight());
            box.set(r[0], r[1], r[2], r[3]);
            line.alpha(Math.min(1f, 1.1f * edge * strength));
            line.ribbon(cv, box, NeonGlow.LINE_DP * dp, NeonGlow.mix(color, 0xFFFFFFFF, 0.45f), 1, phase, null, null);
        }
    }
}
