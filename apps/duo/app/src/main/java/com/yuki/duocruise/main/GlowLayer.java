package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * 縁の光の一層。Duo Pad の band() の一行ぶんに当たる:
 *   shape.stroke(gradient, lineWidth: w).blur(radius: r).opacity(strength)
 *
 * 縁に幅 w の帯(内側半分。外側半分は画面の外なので描かない)を引き、その View ごと GPU でぼかす
 * (RenderEffect。software レイヤーではないので CarPlay の SurfaceView を壊さない)。
 * ぼかしの端は CLAMP なので、縁がいちばん明るいまま内側へ引く。
 * 明るさの模様は縁に沿って流れる(NeonGlow.ribbon)。層ごとに向き(dir)と回数(cycles)が違う。
 *
 * Android 12 未満では RenderEffect が無いので、ぼかさずに描く(その場合は淡い帯になるだけ)。
 */
final class GlowLayer extends View {
    private final NeonGlow glow = new NeonGlow();
    private final RectF box = new RectF();
    private final float dp;
    private final float widthDp;
    private final int cycles, dir;
    private float strength, phase;
    private int color = 0xFFFFFFFF;

    GlowLayer(Context c, float widthDp, float blurDp, int cycles, int dir) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        this.widthDp = widthDp;
        this.cycles = cycles;
        this.dir = dir;
        if (blurDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(blurDp * dp, blurDp * dp, Shader.TileMode.CLAMP));
        }
        setWillNotDraw(false);
    }

    /** 今の強さ・色・位相(0〜1 で模様が一周)を入れて描き直す。 */
    void set(float strength, int color, float phase) {
        this.strength = strength;
        this.color = color;
        this.phase = phase;
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (strength <= 0 || w < 2 || h < 2) return;
        box.set(0, 0, w, h);
        glow.alpha(strength);
        glow.ribbon(cv, box, widthDp * dp * 0.5f, color, cycles, dir * phase, null, null);
    }
}
