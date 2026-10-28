package com.yuki.duocruise.main;

import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 縁の光。設計の要点(Duo Slate の録画を計測して分かったこと):
 *   ・光は自分ではほとんど動かない(起伏 ±25%、ごく遅く巡る、呼吸なし)。繊細。
 *   ・生きて見えるのは操作への応答。触れた所・選んだ所の縁が 0.15 秒で灯り 0.6 秒で引く。大胆。
 *   ・普段は控えめにして、応答の落差を残す。
 *   ・Android の blur 半径は σ=0.577r+0.5。Apple の数字は約 1.7 倍して使う(28→48、7→11)。
 *
 * 縁の光。Duo Slate の Lights と同じ三層(外側の細い線・中のにじみ・広い裾)で一本の縁を作る。
 *
 * 明るさの模様は「縁に沿った距離」で進める。角度(SweepGradient)で回すと、
 * 1480×480 のような横長では長い辺が角度のごく一部しか受け持たず、長い辺の光がほぼ動かなくなる。
 * 縁に沿って一定の速さで模様が流れれば、どの辺でも同じように光が巡る。
 *
 * 模様は明るさの起伏(PATTERN)を一周に cycles 回並べたもので、層ごとに向きと回数を変えて重ねる。
 * 重なりが干渉して、同じ形が二度と来ない。そこに「生きている」感じが出る。
 *
 * 帯は四隅を 45°で突き合わせた台形。角で重ならない。
 * ぼかしは GlowLayer(GPU の RenderEffect)が受け持つ。ここでは線と、ぼかし無しの代替(draw)を描く。
 */
final class NeonGlow {
    /** 白。模様は明るさの起伏だけ(Duo Slate の switched と同じ考え)。 */
    static final int[] AURORA = { 0xFFFFFFFF };
    /** @deprecated 旧名。 */
    static final int[] VIVID = AURORA;

    /**
     * 一周期ぶんの明るさの起伏(0〜1)。Duo Slate の [glow, 0.75, glow, 0.85] と同じ振幅(谷は 0.75・0.85)。
     * 計測では、Duo Slate の自律的な動きはこの程度で、ほとんど意識に上らない。生きて見えるのは操作への応答の方。
     */
    static final float[] PATTERN = { 1.00f, 0.75f, 1.00f, 0.85f };

    static final float LINE_DP = 1.6f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    /** 応答の光(指やコマンダーの位置に寄る光)。次の ribbon だけに掛かる。 */
    private Shader spot;

    /** 次の ribbon を、(cx,cy) を中心に半径 r で丸く切り抜く(中心 1 → 外 0)。null で解除。 */
    void spot(float cx, float cy, float r) {
        spot = r > 0 ? new android.graphics.RadialGradient(cx, cy, r, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) : null;
    }

    /** 次に描く帯の濃さ(0〜1)。 */
    void alpha(float a) { p.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, a)))); }

    /** 外側の細い線。模様 cycles 回・位置 u(0〜1 で一周)。 */
    void line(Canvas dst, RectF box, float dp, float strength, int color, int cycles, float u) {
        if (strength <= 0) return;
        p.setAlpha(Math.round(255 * Math.min(1f, strength)));
        ribbon(dst, box, LINE_DP * dp, color, cycles, u, null, null);
    }

    /**
     * 縁から内側へ深さ d の帯を、縁に沿って流れる明るさの模様で塗る。
     * pos/alpha を渡すと、縁→内側の濃さの曲線で切り抜く(ぼかし無しの代替用)。
     */
    void ribbon(Canvas dst, RectF b, float d, int color, int cycles, float u, float[] pos, float[] alpha) {
        if (d < 0.5f || b.width() < 2 || b.height() < 2) return;
        float w = b.width(), h = b.height(), per = 2 * (w + h);
        float l = b.left, t = b.top, r = b.right, bt = b.bottom;
        int[] mask = null;
        if (pos != null) {
            mask = new int[alpha.length];
            for (int i = 0; i < alpha.length; i++) mask[i] = (Math.round(255 * alpha[i]) << 24) | 0xFFFFFF;
        }
        // 上(左→右)
        trapezoid(l, t, r, t, r - d, t + d, l + d, t + d);
        fill(dst, along(l, t, r, t, 0, w, per, color, cycles, u), mask == null ? null : new LinearGradient(0, t, 0, t + d, mask, pos, Shader.TileMode.CLAMP));
        // 右(上→下)
        trapezoid(r, t, r, bt, r - d, bt - d, r - d, t + d);
        fill(dst, along(r, t, r, bt, w, w + h, per, color, cycles, u), mask == null ? null : new LinearGradient(r, 0, r - d, 0, mask, pos, Shader.TileMode.CLAMP));
        // 下(右→左)
        trapezoid(r, bt, l, bt, l + d, bt - d, r - d, bt - d);
        fill(dst, along(r, bt, l, bt, w + h, 2 * w + h, per, color, cycles, u), mask == null ? null : new LinearGradient(0, bt, 0, bt - d, mask, pos, Shader.TileMode.CLAMP));
        // 左(下→上)
        trapezoid(l, bt, l, t, l + d, t + d, l + d, bt - d);
        fill(dst, along(l, bt, l, t, 2 * w + h, per, per, color, cycles, u), mask == null ? null : new LinearGradient(l, 0, l + d, 0, mask, pos, Shader.TileMode.CLAMP));
        p.setShader(null);
        spot = null;
    }

    /** 一辺に沿った明るさのグラデ。辺は縁の距離 s0〜s1 を受け持ち、模様はその距離で決まる。 */
    private static Shader along(float x0, float y0, float x1, float y1, float s0, float s1, float per, int color, int cycles, float u) {
        int n = 1 + Math.max(4, Math.round((s1 - s0) / per * cycles * PATTERN.length)); // 辺の長さに応じた標本数
        int[] cols = new int[n];
        float[] pos = new float[n];
        for (int i = 0; i < n; i++) {
            float f = i / (float) (n - 1);
            float s = s0 + (s1 - s0) * f;
            float v = pattern(frac(s / per * cycles + u));
            cols[i] = (Math.round(255 * v) << 24) | (color & 0xFFFFFF);
            pos[i] = f;
        }
        return new LinearGradient(x0, y0, x1, y1, cols, pos, Shader.TileMode.CLAMP);
    }

    /** PATTERN を滑らかに(cos 補間で)引く。x は 0〜1。 */
    static float pattern(float x) {
        float fx = x * PATTERN.length;
        int i = (int) Math.floor(fx);
        float f = fx - i;
        float a = PATTERN[i % PATTERN.length], b = PATTERN[(i + 1) % PATTERN.length];
        float k = (1 - (float) Math.cos(Math.PI * f)) * 0.5f;
        return a + (b - a) * k;
    }

    private void trapezoid(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.rewind();
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close();
    }

    private void fill(Canvas dst, Shader color, Shader inward) {
        Shader sh = inward == null ? color : new ComposeShader(color, inward, PorterDuff.Mode.DST_IN);
        if (spot != null) sh = new ComposeShader(sh, spot, PorterDuff.Mode.DST_IN);
        p.setShader(sh);
        dst.drawPath(path, p);
    }

    static float frac(float v) { v = v % 1f; return v < 0 ? v + 1f : v; }

    // ───────── ぼかし無しの代替(メインビューの一瞬の光など、GlowLayer を持たない所) ─────────

    private static final float[] WIDE_POS = { 0f, 0.15f, 0.40f, 0.70f, 1f }, WIDE_A = { 1f, 0.72f, 0.36f, 0.10f, 0f };
    private static final float[] MID_POS = { 0f, 0.5f, 1f }, MID_A = { 1f, 0.45f, 0f };

    /** 単色(状態色)。三層を、ぼかしの代わりに縁→内側の濃さの曲線で描く。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int color, float phase) {
        if (intensity <= 0) return;
        float k = Math.min(1.5f, intensity);
        float wide = Math.min((depthDp > 0 ? depthDp : 64) * dp, Math.min(box.width(), box.height()) * 0.45f);
        p.setAlpha(Math.round(255 * Math.min(1f, 0.34f * k)));
        ribbon(dst, box, wide, mix(color, 0xFFFFFFFF, 0.3f), 2, phase, WIDE_POS, WIDE_A);
        p.setAlpha(Math.round(255 * Math.min(1f, 0.55f * k)));
        ribbon(dst, box, 12 * dp, mix(color, 0xFFFFFFFF, 0.15f), 3, -phase, MID_POS, MID_A);
        p.setAlpha(Math.round(255 * Math.min(1f, 0.8f * k)));
        ribbon(dst, box, LINE_DP * dp, mix(color, 0xFFFFFFFF, 0.45f), 2, phase, null, null);
    }

    /** 既定(白)。 */
    void draw(Canvas dst, RectF box, float dp, float intensity, float phase) {
        draw(dst, box, dp, 0, intensity, Theme.NEON_WHITE, phase);
    }

    /** 旧 API(色の並び)。先頭色で描く。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int[] palette, float phase) {
        draw(dst, box, dp, depthDp, intensity, palette[0], phase);
    }

    static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
