package com.yuki.duocruise.main;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.HashMap;
import java.util.Map;

/**
 * 画面の縁から内側へにじむ光。白が主役で、色はほのかに差すだけ。
 *
 * 縁に沿って一定の帯を塗るのではなく、いくつかの「光のかたまり」を縁の上に置いて、
 * それぞれ別の速さ・向きで縁を巡らせる。かたまりは縁に沿って細長く、
 * 内側へ向かって柔らかく消える。重なった所は加算で白く明るくなる。
 * 大きさと明るさは個別にゆっくり揺れ、全体も静かに呼吸する。
 * だから縁の光は場所によって厚みが違い、絶えず形を変える。
 *
 * ぼかしも software レイヤーも使わない(ハードウェア描画)。CarPlay の映像も壊さない。
 * phase は 0〜1 で一周。巡る速さも揺れの周波数も整数なので、一周で継ぎ目なくつながる。
 */
final class NeonGlow {
    /** メニュー用。白が基調で、桃・空・薄荷がほのかに混じる。 */
    static final int[] AURORA = { 0xFFFFFFFF, 0xFFFFC4DC, 0xFFFFFFFF, 0xFFBFD6FF, 0xFFFFFFFF, 0xFFC4FFE8, 0xFFE6D2FF };
    /** @deprecated 旧名。AURORA を使う。 */
    static final int[] VIVID = AURORA;

    // 光のかたまり: 巡る速さ(整数・符号=向き), 出発位置, 大きさの倍率, 明るさ, 揺れの周波数(整数)
    private static final int[]   SPEED  = {  1, -1,  2, -2,  1, -3,  2 };
    private static final float[] START  = { 0.00f, 0.31f, 0.52f, 0.68f, 0.84f, 0.14f, 0.44f };
    private static final float[] SIZE   = { 2.6f, 1.9f, 2.2f, 1.6f, 2.9f, 1.4f, 2.0f };
    private static final float[] BRIGHT = { 0.85f, 0.70f, 0.78f, 0.62f, 0.90f, 0.56f, 0.72f };
    private static final int[]   WOBBLE = {  3,  5,  2,  7,  4,  6,  3 };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint add = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final Map<Integer, RadialGradient> blobs = new HashMap<>();
    private final float[] pt = new float[3]; // x, y, 縁の向き(度)

    NeonGlow() {
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // 重なりは白く明るく
    }

    /** 単色(状態色)で描く。色そのものと、白に寄せた色を混ぜて光らせる。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int color, float phase) {
        int glow = mix(color, 0xFFFFFFFF, 0.45f), core = mix(color, 0xFFFFFFFF, 0.75f);
        draw(dst, box, dp, depthDp, intensity, new int[]{ color, glow, core, color, glow, core, color }, phase);
    }

    /** 既定(白)。 */
    void draw(Canvas dst, RectF box, float dp, float intensity, float phase) {
        draw(dst, box, dp, 0, intensity, Theme.NEON_WHITE, phase);
    }

    /** 色の並びで描く。各かたまりが順に色を受け持つ。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int[] palette, float phase) {
        if (intensity <= 0 || box.width() < 2 || box.height() < 2) return;
        float k = Math.min(1.5f, intensity);
        float depth = Math.min((depthDp > 0 ? depthDp : 64) * dp, Math.min(box.width(), box.height()) * 0.5f);
        float breath = 0.82f + 0.18f * (float) Math.sin(2 * Math.PI * phase * 2); // 6 秒で一呼吸

        // 縁にうっすら敷く下地。かたまりが来ていない所も、縁は灯っていると分かる程度に
        base(dst, box, depth * 0.45f, palette[0], Math.round(255 * Math.min(1f, 0.16f * breath * k)));

        int save = dst.save();
        dst.clipRect(box);
        for (int i = 0; i < SPEED.length; i++) {
            float u = frac(START[i] + phase * SPEED[i]);
            perimeter(box, u, pt);
            double w = 2 * Math.PI * (phase * WOBBLE[i] + START[i]);
            float swell = 0.86f + 0.14f * (float) Math.sin(w);          // 大きさがゆっくり揺れる
            float flicker = 0.80f + 0.20f * (float) Math.sin(w * 1.5);  // 明るさも別の調子で揺れる
            float r = depth * SIZE[i] * swell;
            int tint = palette[i % palette.length];
            m.setScale(r * 1.9f, r);                 // 縁に沿って細長く、内側へは短く
            m.postRotate(pt[2]);
            m.postTranslate(pt[0], pt[1]);
            RadialGradient g = blob(tint);
            g.setLocalMatrix(m);
            add.setShader(g);
            add.setAlpha(Math.round(255 * Math.min(1f, BRIGHT[i] * flicker * breath * k)));
            dst.drawRect(box, add);
            // 芯: 縁のすぐ内側だけが白く締まって光る。これが「光源がそこにある」感を作る
            m.setScale(r * 0.75f, r * 0.30f);
            m.postRotate(pt[2]);
            m.postTranslate(pt[0], pt[1]);
            RadialGradient c = blob(0xFFFFFFFF);
            c.setLocalMatrix(m);
            add.setShader(c);
            add.setAlpha(Math.round(255 * Math.min(1f, 0.34f * BRIGHT[i] * flicker * breath * k)));
            dst.drawRect(box, add);
        }
        add.setShader(null);
        dst.restoreToCount(save);
    }

    /** 単位円のかたまり(中心が明るく、外へ柔らかく消える)。色ごとに一度だけ作る。 */
    private RadialGradient blob(int tint) {
        RadialGradient g = blobs.get(tint);
        if (g == null) {
            int c = tint & 0x00FFFFFF;
            g = new RadialGradient(0, 0, 1,
                    new int[]{ 0xFF000000 | c, 0x8C000000 | c, 0x24000000 | c, c },
                    new float[]{ 0f, 0.25f, 0.60f, 1f }, Shader.TileMode.CLAMP);
            blobs.put(tint, g);
        }
        return g;
    }

    /** 縁の上の位置 u(0〜1 で一周) を座標と縁の向きに直す。上辺→右辺→下辺→左辺。 */
    private static void perimeter(RectF b, float u, float[] out) {
        float w = b.width(), h = b.height(), s = u * 2 * (w + h);
        if (s < w)             { out[0] = b.left + s;           out[1] = b.top;              out[2] = 0;   return; }
        s -= w;
        if (s < h)             { out[0] = b.right;              out[1] = b.top + s;          out[2] = 90;  return; }
        s -= h;
        if (s < w)             { out[0] = b.right - s;          out[1] = b.bottom;           out[2] = 0;   return; }
        s -= w;
        out[0] = b.left; out[1] = b.bottom - s; out[2] = 90;
    }

    /** 4辺に「縁で色、内側で透明」の薄い帯を敷く。 */
    private void base(Canvas dst, RectF box, float d, int tint, int alpha) {
        if (d < 1 || alpha <= 0) return;
        float l = box.left, t = box.top, r = box.right, b = box.bottom;
        int on = 0xFF000000 | (tint & 0x00FFFFFF), off = tint & 0x00FFFFFF;
        p.setAlpha(alpha);
        p.setShader(new LinearGradient(0, t, 0, t + d, on, off, Shader.TileMode.CLAMP)); dst.drawRect(l, t, r, t + d, p);
        p.setShader(new LinearGradient(0, b, 0, b - d, on, off, Shader.TileMode.CLAMP)); dst.drawRect(l, b - d, r, b, p);
        p.setShader(new LinearGradient(l, 0, l + d, 0, on, off, Shader.TileMode.CLAMP)); dst.drawRect(l, t, l + d, b, p);
        p.setShader(new LinearGradient(r, 0, r - d, 0, on, off, Shader.TileMode.CLAMP)); dst.drawRect(r - d, t, r, b, p);
        p.setShader(null);
    }

    private static float frac(float v) { v = v % 1f; return v < 0 ? v + 1f : v; }

    private static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
