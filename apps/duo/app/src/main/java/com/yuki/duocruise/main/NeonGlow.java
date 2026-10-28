package com.yuki.duocruise.main;

import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * 縁の光。Duo Pad(iPhone)の Lights と同じ作り。
 *
 * 三層で一本の縁を作る:
 *   1. 外側の細い線(1.6dp)。くっきりと、しかし淡く。これが「縁が灯っている」輪郭になる
 *   2. その内側の中くらいのにじみ(約 12dp)
 *   3. さらに内側へ広く薄く引く光(約 64dp)。ぼかした筆の跡のように、なだらかに消える
 * 三層とも、縁を一周する明るさの並び(4 段階・差はわずか)を共有し、それが 36 秒で一周する。
 * だから光は縁の全周に途切れず在り(大胆)、明るさの差はごくわずかで、ゆっくりと巡る(繊細)。
 * 強さは Duo Pad と同じ桁(普段 0.55)。文字より目立ってはいけない。
 *
 * 帯は四隅で 45°に突き合わせた台形で塗るので、角で重なって明るくならない。
 * ぼかしも software レイヤーも使わない(ハードウェア描画)。CarPlay の映像も壊さない。
 * phase は 0〜1 で一周。
 */
final class NeonGlow {
    /** メニュー・ターゲット切替の白。明るさの差だけで流れを作る(Duo Pad の switched と同じ)。 */
    static final int[] AURORA = { 0xFFFFFFFF, 0xBFFFFFFF, 0xFFFFFFFF, 0xD9FFFFFF };
    /** @deprecated 旧名。AURORA を使う。 */
    static final int[] VIVID = AURORA;

    // 層: 深さ(dp), 強さの係数。外から 線・中・広
    private static final float LINE_DP = 1.6f;
    private static final float MID_DP  = 12f;
    private static final float WIDE_DP = 64f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final Path path = new Path();

    /** 単色(状態色)。Duo Pad の通知色と同じく、近い色を少し白寄りに混ぜて並べる。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int color, float phase) {
        int a = mix(color, 0xFFFFFFFF, 0.30f), b = mix(color, 0xFFFFFFFF, 0.12f), c = mix(color, 0xFFFFFFFF, 0.42f);
        draw(dst, box, dp, depthDp, intensity, new int[]{ color, a, b, c }, phase);
    }

    /** 既定(白)。 */
    void draw(Canvas dst, RectF box, float dp, float intensity, float phase) {
        draw(dst, box, dp, 0, intensity, AURORA, phase);
    }

    /** 色の並びで描く。並びは縁を一周し、phase で回る。depthDp は広い層の深さ(0 で既定)。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int[] palette, float phase) {
        if (intensity <= 0 || box.width() < 2 || box.height() < 2) return;
        float k = Math.min(1.5f, intensity);
        float wide = Math.min((depthDp > 0 ? depthDp : WIDE_DP) * dp, Math.min(box.width(), box.height()) * 0.45f);

        int[] cols = new int[palette.length + 1];
        System.arraycopy(palette, 0, cols, 0, palette.length);
        cols[palette.length] = palette[0];
        SweepGradient sweep = new SweepGradient(box.centerX(), box.centerY(), cols, null);
        m.setRotate(phase * 360f, box.centerX(), box.centerY());
        sweep.setLocalMatrix(m);

        // 3. 広く薄く(ぼかした太い筆: なだらかな裾)
        halo(dst, box, wide, sweep, 0.34f * k, new float[]{ 0f, 0.15f, 0.40f, 0.70f, 1f }, new float[]{ 1f, 0.72f, 0.36f, 0.10f, 0f });
        // 2. 中くらい
        halo(dst, box, MID_DP * dp, sweep, 0.55f * k, new float[]{ 0f, 0.5f, 1f }, new float[]{ 1f, 0.45f, 0f });
        // 1. 外側の細い線
        halo(dst, box, LINE_DP * dp, sweep, Math.min(1f, 0.8f * k), new float[]{ 0f, 1f }, new float[]{ 1f, 1f });
    }

    /** 外側の細い線だけを引く(ぼかした層は GlowLayer が受け持つとき用)。 */
    void line(Canvas dst, RectF box, float dp, float strength, int[] palette, float phase) {
        if (strength <= 0 || box.width() < 2 || box.height() < 2) return;
        int[] cols = new int[palette.length + 1];
        System.arraycopy(palette, 0, cols, 0, palette.length);
        cols[palette.length] = palette[0];
        SweepGradient sweep = new SweepGradient(box.centerX(), box.centerY(), cols, null);
        m.setRotate(phase * 360f, box.centerX(), box.centerY());
        sweep.setLocalMatrix(m);
        halo(dst, box, LINE_DP * dp, sweep, Math.min(1f, strength), new float[]{ 0f, 1f }, new float[]{ 1f, 1f });
    }

    /**
     * 縁から内側へ深さ d の帯を、四隅を 45°で突き合わせた台形で塗る。
     * 色は sweep、濃さは縁からの距離の曲線(pos/alpha)で決める。
     */
    private void halo(Canvas dst, RectF b, float d, Shader sweep, float strength, float[] pos, float[] alpha) {
        if (d < 0.5f || strength <= 0) return;
        int[] mask = new int[alpha.length];
        for (int i = 0; i < alpha.length; i++) mask[i] = (Math.round(255 * alpha[i]) << 24) | 0xFFFFFF;
        p.setAlpha(Math.round(255 * Math.min(1f, strength)));
        float l = b.left, t = b.top, r = b.right, bt = b.bottom;
        // 上
        trapezoid(l, t, r, t, r - d, t + d, l + d, t + d);
        fill(dst, sweep, new LinearGradient(0, t, 0, t + d, mask, pos, Shader.TileMode.CLAMP));
        // 下
        trapezoid(l, bt, r, bt, r - d, bt - d, l + d, bt - d);
        fill(dst, sweep, new LinearGradient(0, bt, 0, bt - d, mask, pos, Shader.TileMode.CLAMP));
        // 左
        trapezoid(l, t, l, bt, l + d, bt - d, l + d, t + d);
        fill(dst, sweep, new LinearGradient(l, 0, l + d, 0, mask, pos, Shader.TileMode.CLAMP));
        // 右
        trapezoid(r, t, r, bt, r - d, bt - d, r - d, t + d);
        fill(dst, sweep, new LinearGradient(r, 0, r - d, 0, mask, pos, Shader.TileMode.CLAMP));
        p.setShader(null);
    }

    private void trapezoid(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.rewind();
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close();
    }

    private void fill(Canvas dst, Shader color, Shader mask) {
        p.setShader(new ComposeShader(color, mask, PorterDuff.Mode.DST_IN)); // 色を、縁→内側の濃さで切り抜く
        dst.drawPath(path, p);
    }

    private static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
