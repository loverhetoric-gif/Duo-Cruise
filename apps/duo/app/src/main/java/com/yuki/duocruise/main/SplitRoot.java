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
    /** 曲がり角の案内と、曲がる方向の光(Duo Cruise Unlimited Edition)。 */
    private final TurnHintView hint;
    private final TurnGlow turnGlow;
    /** 分割比の調整中だけ出す、境目の光と左右の割合。 */
    private final SplitLine splitLine;
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
    static final long SWAP_MS = 380, GLOW_MS = 2400; // 光は Duo Slate と同じ 2.4 秒

    SplitRoot(Context c, Listener l) {
        super(c);
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.BG);
        frame = new Frame(c);
        hint = new TurnHintView(c);
        turnGlow = new TurnGlow(c);
        splitLine = new SplitLine(c);
    }

    void setPanes(View carView, View tvView, PanelView p) {
        removeAllViews();
        car = carView;
        tv = tvView;
        panel = p;
        addView(car);
        addView(tv);
        addView(frame);
        addView(hint);
        addView(turnGlow);
        addView(splitLine);
        addView(panel);
        if (cursor != null) { if (cursor.getParent() != null) ((ViewGroup) cursor.getParent()).removeView(cursor); addView(cursor); }
    }

    /** 並べ直す(入れ替え・操作側・操作盤の場所が変わったとき)。 */
    /** 1画面にするか。apply の前に呼ぶ。 */
    void setSingle(boolean v) { single = v; }

    /** CarPlay(地図)が画面に見えているか。 */
    boolean carVisible() {
        if (single) return !tvShown();
        return ytPct < 98;
    }

    /** 曲がり角の案内を出す/引っ込める(YouTube の枠の右上)。 */
    void turnHint(boolean show, int turn, Integer exitAngle, int drivingSide, String dist, String road) {
        hint.set(show, turn, exitAngle, drivingSide, dist, road);
    }

    /** 分割比の調整中の表示。show=false で消す。left/right は境目の左右に出す文字。 */
    void splitLine(boolean show, String left, String right) { splitLine.set(show, left, right); }

    /** 曲がる方向の縁を一瞬光らせる。side は "L"/"R"。 */
    void turnGlow(String side, int color, float strength) { turnGlow.glow(side, color, strength); }

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
        hint.bringToFront();
        turnGlow.bringToFront();
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
        measureTo(turnGlow, new int[]{0, 0, w, h});
        measureTo(splitLine, new int[]{0, 0, w, h});
        hint.measure(MeasureSpec.makeMeasureSpec(Math.round(TurnHintView.W_DP * dp), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(TurnHintView.H_DP * dp), MeasureSpec.EXACTLY));
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
        layoutTo(turnGlow, new int[]{0, 0, w, h});
        layoutTo(splitLine, new int[]{0, 0, w, h});
        splitLine.invalidate();
        // 案内は YouTube の枠の右上。枠が画面の外(1画面で地図だけ)なら、画面の右上
        int[] at = v[0] >= w || v[2] <= 0 ? new int[]{0, 0, w, h} : v;
        int m = Math.round(16 * dp), hw = hint.getMeasuredWidth(), hh = hint.getMeasuredHeight();
        layoutTo(hint, new int[]{at[2] - m - hw, at[1] + m, at[2] - m, at[1] + m + hh});
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
     * 分割比の調整中の表示。左右の枠の境目に、縦に細い光の筋(芯 2dp・両側へ 28dp にじむ)を立て、
     * 境目のすぐ左右に、それぞれの中身と割合を出す(黒の透過の四角い板に白い文字。角は丸めない)。
     * 境目は枠の実際の位置から求めるので、分割比のアニメーションにそのまま付いていく。
     */
    private final class SplitLine extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plate = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String left = "", right = "";
        private boolean shown;

        SplitLine(Context c) {
            super(c);
            core.setColor(0xFFFFFFFF);
            plate.setColor(0xB3000000);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(20 * dp);
            text.setFakeBoldText(true);
            hint.setColor(0xB3FFFFFF);
            hint.setTextSize(13 * dp);
            setAlpha(0f);
            setVisibility(GONE);
        }

        void set(boolean show, String l, String r) {
            if (l != null) left = l;
            if (r != null) right = r;
            invalidate();
            if (show == shown) return;
            shown = show;
            animate().cancel();
            if (show) {
                setVisibility(VISIBLE);
                animate().alpha(1f).setDuration(200).start();
            } else {
                animate().alpha(0f).setDuration(300).withEndAction(() -> { if (!shown) setVisibility(GONE); }).start();
            }
        }

        /** 境目の x(右側の枠の左端)。 */
        private float boundary() {
            View r = swapped ? car : tv;
            if (r == null) return getWidth() / 2f;
            return Math.max(0, Math.min(getWidth(), r.getLeft() + r.getTranslationX() - getPaddingLeft()));
        }

        @Override protected void onDraw(Canvas cv) {
            int h = getHeight();
            float x = boundary(), g = 28 * dp;
            glow.setShader(new android.graphics.LinearGradient(x - g, 0, x + g, 0,
                    // 芯の近くだけ明るく、外へ向かって速く・なめらかに消える(帯に見えないように)
                    new int[]{0x00FFFFFF, 0x08FFFFFF, 0x24FFFFFF, 0x66FFFFFF, 0x24FFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.25f, 0.40f, 0.5f, 0.60f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x - g, 0, x + g, h, glow);
            cv.drawRect(x - 1 * dp, 0, x + 1 * dp, h, core);
            // 左右の割合(境目の両側、上寄り)
            float pad = 12 * dp, ph = 40 * dp, top = 18 * dp, gap = 10 * dp;
            float lw = text.measureText(left) + pad * 2, rw = text.measureText(right) + pad * 2;
            float base = top + ph / 2 + text.getTextSize() * 0.36f;
            cv.drawRect(x - gap - lw, top, x - gap, top + ph, plate);
            cv.drawText(left, x - gap - lw + pad, base, text);
            cv.drawRect(x + gap, top, x + gap + rw, top + ph, plate);
            cv.drawText(right, x + gap + pad, base, text);
            // 操作の案内(下寄り・境目の中央)
            String tip = Tx.s(R.string.sp_adjust_hint);
            float tw = hint.measureText(tip) + pad * 2, tb = h - 18 * dp;
            float tx = Math.max(0, Math.min(getWidth() - tw, x - tw / 2));
            cv.drawRect(tx, tb - 30 * dp, tx + tw, tb, plate);
            cv.drawText(tip, tx + pad, tb - 10 * dp, hint);
        }
    }

    /**
     * 曲がる方向の光(Duo Cruise Unlimited Edition)。画面全体の左か右の縁だけが、その色で一瞬灯って消える。
     * 作りと出方はメインビューのイベント発光(Frame)と同じ三層・同じ 2.4 秒。違うのは、光を曲がる側の縁の
     * 真ん中を中心とした大きな円で切り抜くこと(GlowLayer.spot)。左右の縁と、その側の上下の縁の端が光る。
     */
    private final class TurnGlow extends ViewGroup {
        private final NeonGlow line = new NeonGlow();
        private final GlowLayer wide, mid;
        private final RectF box = new RectF();
        private ValueAnimator anim;
        private float edge, phase, strength = 0.55f;
        private int color = Store.NEON_TURN;
        private boolean left;

        TurnGlow(Context c) {
            super(c);
            setWillNotDraw(false);
            wide = new GlowLayer(c, 46, 48, 1, +1);
            mid = new GlowLayer(c, 10, 11, 1, -1);
            addView(wide);
            addView(mid);
        }

        void glow(String side, int c, float s) {
            left = "L".equals(side);
            color = c;
            strength = Math.max(0f, Math.min(1f, s));
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(0f, GLOW_MS / 1000f);
            anim.setDuration(GLOW_MS);
            anim.setInterpolator(new android.view.animation.LinearInterpolator());
            anim.addUpdateListener(a -> {
                float e = (float) a.getAnimatedValue(), d = GLOW_MS / 1000f;
                edge = e < 0.35f ? e / 0.35f : e < 1.3f ? 1f : Math.max(0f, 1 - (e - 1.3f) / (d - 1.3f));
                phase = e * 0.25f;
                update();
            });
            anim.start();
        }

        private float cx() { return left ? 0 : getWidth(); }
        private float radius() { return getHeight() * 1.2f; }
        private boolean off() { return edge <= 0 || (panel != null && panel.getVisibility() == VISIBLE); }

        private void update() {
            float k = off() ? 0 : edge * strength;
            float cy = getHeight() / 2f, r = radius();
            wide.set(0, NeonGlow.mix(color, 0xFFFFFFFF, 0.30f), phase);
            mid.set(0, NeonGlow.mix(color, 0xFFFFFFFF, 0.15f), phase);
            wide.spot(cx(), cy, r, 0.38f * k);
            mid.spot(cx(), cy, r, 0.60f * k);
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
            for (View v : new View[]{wide, mid}) v.measure(wSpec, hSpec);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            wide.layout(0, 0, r - l, b - t);
            mid.layout(0, 0, r - l, b - t);
        }

        @Override protected void dispatchDraw(Canvas cv) {
            super.dispatchDraw(cv);
            if (off()) return;
            box.set(0, 0, getWidth(), getHeight());
            line.alpha(Math.min(1f, 1.1f * edge * strength));
            line.spot(cx(), getHeight() / 2f, radius());
            line.ribbon(cv, box, NeonGlow.LINE_DP * dp, NeonGlow.mix(color, 0xFFFFFFFF, 0.45f), 0, 0, null, null);
        }
    }

    /**
     * メインビューの縁の光。イベント(ターゲット切替=白・接続完了=青・接続不良=赤)の瞬間だけ、
     * 操作中の画面の縁を光らせて消す。常時は出さない(没入優先)。
     *
     * 作りは設定ビューと同じ三層(Duo Slate の band と同じ。NeonGlow の説明を参照):
     *   広い裾(46dp を GPU で 48 ぼかし)・中のにじみ(10dp を 11 ぼかし)・外側の細い線(1.6dp)。
     * 出方は Duo Slate の Envelope と同じ: 0.35 秒で灯り、1.3 秒まで保ち、2.4 秒でゆっくり消える。
     * その間、明るさの起伏が縁に沿って流れる(Duo Slate の spin e×90°/秒 = 0.25 周/秒)。
     * 四角(半径0)。ペインの端にぴたりと合わせる。
     */
    private final class Frame extends ViewGroup {
        private final NeonGlow line = new NeonGlow();
        private final GlowLayer wide, mid;
        private final RectF box = new RectF();
        private ValueAnimator glowAnim;
        private float edge = 0;                      // 出来事の光の強さ(0〜1)。Duo Slate の Envelope.edge
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
