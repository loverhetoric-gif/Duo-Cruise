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
    private float spotX, spotY, spotR, spotK; // 応答の光: 位置・半径・強さ

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

    /** 応答の光。(x,y) の周り半径 r に、強さ k で灯る。k=0 で消す。 */
    void spot(float x, float y, float r, float k) {
        spotX = x; spotY = y; spotR = r; spotK = k;
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w < 2 || h < 2) return;
        box.set(0, 0, w, h);
        float d = widthDp * dp * 0.5f;
        if (strength > 0) {
            glow.alpha(strength);
            glow.ribbon(cv, box, d, color, cycles, dir * phase, null, null);
        }
        if (spotK > 0 && spotR > 0) {   // 一様な明るさの帯を、丸く切り抜いて重ねる
            glow.alpha(spotK);
            glow.spot(spotX, spotY, spotR);
            glow.ribbon(cv, box, d, color, 0, 0, null, null);
        }
    }
}
