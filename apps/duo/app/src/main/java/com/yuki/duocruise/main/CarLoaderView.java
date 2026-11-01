package com.yuki.duocruise.main;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * CarPlay がつながるまでの演出。CarPlay の枠いっぱいに重ねる。
 *
 * 起動演出と地続きにする: 同じシンボル(夜の素材: 一色版=白・色付き版=黒地用)を同じ重ね方で置き、
 * 「墨が抜けて色が出る」を、ゆっくり行き来させる(3.6 秒で一往復。ease-in-out)。
 * 自分ではほとんど動かない(大きさ・位置は変えない)。その下に、今の様子の文を小さく添える。
 * 映像が届いたら 0.4 秒で溶けて消え、CarPlay の画面が現れる。
 */
final class CarLoaderView extends View {
    private static final long CYCLE_MS = 3600;

    private final Bitmap mono, color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float dp;
    private String message = "";
    private StaticLayout layout;
    private int layoutW = -1;
    private ValueAnimator loop;
    private float phase;   // 0〜1 で一往復
    private boolean shown = true;

    CarLoaderView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF000000);
        mono = bmp(c, R.drawable.boot_symbol_mono_white);
        color = bmp(c, R.drawable.boot_symbol_for_black);
        text.setColor(0xB3FFFFFF);
        text.setTextSize(15 * dp);
    }

    private static Bitmap bmp(Context c, int id) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        return BitmapFactory.decodeResource(c.getResources(), id, o);
    }

    /** 出す(つながるまで)/引っ込める(映像が届いた)。文は今の様子。 */
    void set(boolean show, String msg) {
        if (msg != null && !msg.equals(message)) { message = msg; layout = null; invalidate(); }
        if (show == shown) return;
        shown = show;
        animate().cancel();
        if (show) {
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(300).start();
            startLoop();
        } else {
            animate().alpha(0f).setDuration(400).withEndAction(() -> {
                if (!shown) { setVisibility(GONE); stopLoop(); }
            }).start();
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (shown) startLoop(); }
    @Override protected void onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow(); }

    private void startLoop() {
        if (loop != null) return;
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(CYCLE_MS);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new android.view.animation.LinearInterpolator());
        loop.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
        loop.start();
    }

    private void stopLoop() { if (loop != null) { loop.cancel(); loop = null; } }

    /** ease-in-out(起動演出と同じ三次の S 字)。 */
    private static float ease(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w < 2 || h < 2 || mono == null || color == null) return;
        // 一往復: 前半で色が出て、後半で墨に戻る。色付き版を下、一色版を上に重ねる(起動演出と同じ)
        float k = phase < 0.5f ? ease(phase * 2) : 1 - ease((phase - 0.5f) * 2);
        float side = Math.min(h * 0.34f, w * 0.30f);
        float textH = layoutFor(w).getHeight();
        float gap = 20 * dp;
        float top = (h - side - (message.isEmpty() ? 0 : gap + textH)) / 2f;
        box.set((w - side) / 2f, top, (w + side) / 2f, top + side);
        draw(cv, color, 0.25f + 0.75f * k);
        draw(cv, mono, 0.85f * (1 - k));
        if (!message.isEmpty()) {
            cv.save();
            cv.translate((w - layout.getWidth()) / 2f, box.bottom + gap);
            layout.draw(cv);
            cv.restore();
        }
    }

    private StaticLayout layoutFor(int w) {
        int lw = Math.max(1, Math.round(w * 0.86f));
        if (layout == null || layoutW != lw) {
            layoutW = lw;
            layout = StaticLayout.Builder.obtain(message, 0, message.length(), text, lw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.35f).build();
        }
        return layout;
    }

    private void draw(Canvas cv, Bitmap b, float a) {
        if (a <= 0.001f) return;
        p.setAlpha(Math.round(255 * Math.min(1f, a)));
        cv.drawBitmap(b, null, box, p);
    }
}
