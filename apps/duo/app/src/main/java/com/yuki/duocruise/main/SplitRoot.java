package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

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
    PanelView panel;
    boolean swapped, panelHalf;
    int side;
    private String lastSizes = "";

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
    }

    /** 並べ直す(入れ替え・操作側・操作盤の場所が変わったとき)。 */
    void apply(boolean swapped, int side, boolean panelHalf) {
        this.swapped = swapped;
        this.side = side;
        this.panelHalf = panelHalf;
        requestLayout();
        frame.invalidate();
    }

    void showLabel(String text) { frame.label(text); }

    private int[] inner(int w, int h, int[] r) {
        // 余白(WindowInsets を padding として受けている)を足す
        return new int[]{r[0] + getPaddingLeft(), r[1] + getPaddingTop(), r[2] + getPaddingLeft(), r[3] + getPaddingTop()};
    }

    private int contentW() { return Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight()); }
    private int contentH() { return Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom()); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        int w = contentW(), h = contentH();
        measureTo(car, SplitGeometry.car(w, h, swapped));
        measureTo(tv, SplitGeometry.tv(w, h, swapped));
        measureTo(frame, new int[]{0, 0, w, h});
        if (panel != null) measureTo(panel, SplitGeometry.panel(w, h, panelHalf, side));
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
        int[] c = SplitGeometry.car(w, h, swapped), v = SplitGeometry.tv(w, h, swapped);
        layoutTo(car, c);
        layoutTo(tv, v);
        layoutTo(frame, new int[]{0, 0, w, h});
        if (panel != null) layoutTo(panel, SplitGeometry.panel(w, h, panelHalf, side));
        String sizes = w + "×" + h + " car=" + SplitGeometry.str(c) + " tv=" + SplitGeometry.str(v);
        if (!sizes.equals(lastSizes)) {
            lastSizes = sizes;
            listener.laidOut(w, h, SplitGeometry.str(c), SplitGeometry.str(v));
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && (panel == null || panel.getVisibility() != VISIBLE)) {
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
            setWillNotDraw(false);
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
            int[] r = SplitGeometry.half(w, h, side);
            float s = line.getStrokeWidth() / 2;
            if (panel == null || panel.getVisibility() != VISIBLE) {
                cv.drawRoundRect(new RectF(r[0] + s, r[1] + s, r[2] - s, r[3] - s), 6 * dp, 6 * dp, line);
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
