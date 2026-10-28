package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;
import android.view.View;

/**
 * 縁の光の一層。Duo Pad の band() の一行ぶんに当たる:
 *   shape.stroke(gradient, lineWidth: w).blur(radius: r).opacity(strength)
 *
 * 縁に太さ w の線を引き、その View ごと GPU でぼかす(RenderEffect。software レイヤーではないので
 * CarPlay の SurfaceView を壊さない)。ぼかしは本物のガウスなので、裾は滑らかに消える。
 * 線は縁の上に中心を置くので、半分は画面の外。ぼかしの端は CLAMP で、縁がいちばん明るいまま内側へ引く。
 *
 * Android 12 未満では RenderEffect が無いので、ぼかさずに描く(その場合は淡い帯になるだけ)。
 */
final class GlowLayer extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final float dp;
    private float widthDp, strength, phase;
    private int[] palette = NeonGlow.AURORA;
    private boolean screen;

    GlowLayer(Context c, float widthDp, float blurDp, boolean screenBlend) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        this.widthDp = widthDp;
        this.screen = screenBlend;
        p.setStyle(Paint.Style.STROKE);
        if (screenBlend) p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        if (blurDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(blurDp * dp, blurDp * dp, Shader.TileMode.CLAMP));
        }
        setWillNotDraw(false);
    }

    /** 今の強さ・色の並び・位相を入れて描き直す。 */
    void set(float strength, int[] palette, float phase) {
        this.strength = strength;
        this.palette = palette;
        this.phase = phase;
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (strength <= 0 || w < 2 || h < 2) return;
        int[] cols = new int[palette.length + 1];
        System.arraycopy(palette, 0, cols, 0, palette.length);
        cols[palette.length] = palette[0];
        SweepGradient sweep = new SweepGradient(w / 2f, h / 2f, cols, null);
        m.setRotate(phase * 360f, w / 2f, h / 2f);
        sweep.setLocalMatrix(m);
        p.setShader(sweep);
        p.setStrokeWidth(widthDp * dp);
        p.setAlpha(Math.round(255 * Math.min(1f, strength)));
        cv.drawRect(0, 0, w, h, p); // 線の中心が縁。半分は外
        p.setShader(null);
    }
}
