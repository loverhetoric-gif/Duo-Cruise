package com.yuki.duocruise.main;

import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * 画面の縁から内側へ流れる光(Siri・Gemini の起動時の光のように)。
 *
 * ・各辺の帯に「回転する多色の角度グラデ」×「縁で不透明→内側で透明」を合成して塗る。
 *   色が縁に沿って流れ、内側へ柔らかく消える。硬い線は出ない。
 * ・3層(広い裾／中間／細く明るい縁)を、別々の速さ・逆向きに回して重ね、流体のような動きにする。
 * ・ぼかしも software レイヤーも使わない(ハードウェア描画)。CarPlay の映像も壊さない。
 *
 * phase は 0〜1 で一周。各層の回転は phase の整数倍なので、1 周で継ぎ目なくつながる。
 */
final class NeonGlow {
    /** Siri/Gemini 風の多色(最初と最後を同じにして継ぎ目を消す)。 */
    static final int[] VIVID = { 0xFFFF5FA2, 0xFFB46CFF, 0xFF5E8BFF, 0xFF4FE3FF, 0xFF7CFFB2, 0xFFFFB25E, 0xFFFF5FA2 };

    // 層: 深さの割合, 強さ, 回転の速さ(整数・符号=向き), 位相のずれ
    private static final float[] DEPTH = { 1.00f, 0.42f, 0.10f };
    private static final float[] ALPHA = { 0.55f, 0.75f, 1.00f };
    private static final int[]   SPEED = { 1, -2, 3 };
    private static final float[] SHIFT = { 0f, 0.33f, 0.66f };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();

    /** 単色(状態色)で描く。色の濃淡で流れを作る。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int color, float phase) {
        int light = mix(color, 0xFFFFFFFF, 0.55f), dim = mix(color, 0xFF000000, 0.35f);
        draw(dst, box, dp, depthDp, intensity, new int[]{ color, light, dim, color, light, color }, phase);
    }

    /** 既定(白)。 */
    void draw(Canvas dst, RectF box, float dp, float intensity, float phase) {
        draw(dst, box, dp, 0, intensity, Theme.NEON_WHITE, phase);
    }

    /** 多色で描く。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int[] palette, float phase) {
        if (intensity <= 0 || box.width() < 2 || box.height() < 2) return;
        float k = Math.min(1.4f, intensity);
        float depth = Math.min((depthDp > 0 ? depthDp : 64) * dp, Math.min(box.width(), box.height()) * 0.5f);
        float cx = box.centerX(), cy = box.centerY();
        for (int i = 0; i < DEPTH.length; i++) {
            double t = 2 * Math.PI * (phase * (i + 1) + SHIFT[i]);
            float wobble = 0.78f + 0.22f * (float) Math.sin(t);            // 層ごとに明るさが揺らぐ
            SweepGradient sweep = new SweepGradient(cx, cy, palette, null);
            m.setRotate((phase * SPEED[i] + SHIFT[i]) * 360f, cx, cy);      // 色が縁を流れる
            sweep.setLocalMatrix(m);
            int a = Math.round(255 * Math.min(1f, ALPHA[i] * wobble * k));
            bands(dst, box, depth * DEPTH[i], sweep, a);
        }
    }

    /** 4辺に「色(sweep) × 縁→内側の透明」の帯を塗る。 */
    private void bands(Canvas dst, RectF box, float d, Shader color, int alpha) {
        if (d < 1 || alpha <= 0) return;
        float l = box.left, t = box.top, r = box.right, b = box.bottom;
        int on = 0xFFFFFFFF, off = 0x00FFFFFF;
        p.setAlpha(alpha);
        band(dst, color, new LinearGradient(0, t, 0, t + d, on, off, Shader.TileMode.CLAMP), l, t, r, t + d);
        band(dst, color, new LinearGradient(0, b, 0, b - d, on, off, Shader.TileMode.CLAMP), l, b - d, r, b);
        band(dst, color, new LinearGradient(l, 0, l + d, 0, on, off, Shader.TileMode.CLAMP), l, t, l + d, b);
        band(dst, color, new LinearGradient(r, 0, r - d, 0, on, off, Shader.TileMode.CLAMP), r - d, t, r, b);
        p.setShader(null);
    }

    private void band(Canvas dst, Shader color, Shader mask, float l, float t, float r, float b) {
        p.setShader(new ComposeShader(color, mask, PorterDuff.Mode.DST_IN)); // 色を、縁→内側の透明で切り抜く
        dst.drawRect(l, t, r, b, p);
    }

    private static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
