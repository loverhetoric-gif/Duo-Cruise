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
 * 画面の縁を巡る、細い光の筋。白が主役で、色はほのかに差すだけ。
 *
 * 縁の上に細長い光の筋を何本も置き、別々の速さ・向きで巡らせる。
 * 筋は縁にぴたりと沿い、内側へは浅くしか届かない(霧のように広がらない)。
 * それぞれの筋は「芯(細く明るい線)」と「にじみ(その周りの薄い光)」の二層で、
 * 長さと明るさが個別にゆっくり揺れる。重なった所は加算で白く明るくなる。
 * 全体も静かに呼吸する。
 *
 * ぼかしも software レイヤーも使わない(ハードウェア描画)。CarPlay の映像も壊さない。
 * phase は 0〜1 で一周。巡る速さも揺れの周波数も整数なので、一周で継ぎ目なくつながる。
 */
final class NeonGlow {
    /** メニュー用。白が基調で、桃・空・薄荷・藤がほのかに混じる。 */
    static final int[] AURORA = { 0xFFFFFFFF, 0xFFFFCFE2, 0xFFFFFFFF, 0xFFCDDDFF, 0xFFFFFFFF, 0xFFCFFFEC, 0xFFFFFFFF, 0xFFE4D6FF, 0xFFFFFFFF, 0xFFFFE6D2, 0xFFFFFFFF };
    /** @deprecated 旧名。AURORA を使う。 */
    static final int[] VIVID = AURORA;

    // 光の筋: 巡る速さ(整数・符号=向き), 出発位置, 長さの倍率, 明るさ, 揺れの周波数(整数)
    private static final int[]   SPEED  = {  1, -1,  2, -2,  1, -3,  2, -1,  3, -2,  1 };
    private static final float[] START  = { 0.00f, 0.09f, 0.21f, 0.30f, 0.41f, 0.50f, 0.58f, 0.67f, 0.77f, 0.86f, 0.94f };
    private static final float[] LEN    = { 1.30f, 0.80f, 1.05f, 0.65f, 1.45f, 0.55f, 0.95f, 0.75f, 1.15f, 0.60f, 1.00f };
    private static final float[] BRIGHT = { 0.70f, 0.52f, 0.62f, 0.46f, 0.76f, 0.42f, 0.58f, 0.50f, 0.66f, 0.44f, 0.60f };
    private static final int[]   WOBBLE = {  3,  5,  2,  7,  4,  6,  3,  5,  2,  4,  6 };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint add = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final Map<Integer, RadialGradient> soft = new HashMap<>();
    private final Map<Integer, RadialGradient> core = new HashMap<>();
    private final float[] pt = new float[3]; // x, y, 縁の向き(度)

    NeonGlow() {
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // 重なりは白く明るく
    }

    /** 単色(状態色)で描く。色そのものと、白に寄せた色を混ぜて光らせる。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int color, float phase) {
        int glow = mix(color, 0xFFFFFFFF, 0.45f), hot = mix(color, 0xFFFFFFFF, 0.75f);
        draw(dst, box, dp, depthDp, intensity, new int[]{ color, glow, hot, color, glow, hot, color, glow, hot, color, glow }, phase);
    }

    /** 既定(白)。 */
    void draw(Canvas dst, RectF box, float dp, float intensity, float phase) {
        draw(dst, box, dp, 0, intensity, Theme.NEON_WHITE, phase);
    }

    /** 色の並びで描く。各筋が順に色を受け持つ。 */
    void draw(Canvas dst, RectF box, float dp, float depthDp, float intensity, int[] palette, float phase) {
        if (intensity <= 0 || box.width() < 2 || box.height() < 2) return;
        float k = Math.min(1.5f, intensity);
        float depth = Math.min((depthDp > 0 ? depthDp : 64) * dp, Math.min(box.width(), box.height()) * 0.5f);
        float breath = 0.84f + 0.16f * (float) Math.sin(2 * Math.PI * phase * 2); // 6 秒で一呼吸

        // 縁にごく薄く敷く下地。筋が来ていない所も、縁が灯っていると分かる程度に
        base(dst, box, depth * 0.22f, palette[0], Math.round(255 * Math.min(1f, 0.11f * breath * k)));

        int save = dst.save();
        dst.clipRect(box);
        for (int i = 0; i < SPEED.length; i++) {
            float u = frac(START[i] + phase * SPEED[i]);
            perimeter(box, u, pt);
            double w = 2 * Math.PI * (phase * WOBBLE[i] + START[i]);
            float stretch = 0.84f + 0.16f * (float) Math.sin(w);         // 長さがゆっくり揺れる
            float flicker = 0.78f + 0.22f * (float) Math.sin(w * 1.5);   // 明るさも別の調子で揺れる
            float len = depth * LEN[i] * stretch;                        // 縁に沿った半径
            float a = Math.min(1f, BRIGHT[i] * flicker * breath * k);
            int tint = palette[i % palette.length];

            // にじみ: 縁に沿って長く、内側へは浅く(長さの 0.28)
            m.setScale(len, len * 0.28f);
            m.postRotate(pt[2]);
            m.postTranslate(pt[0], pt[1]);
            RadialGradient s = shader(soft, tint, 0xA6, 0x2E);
            s.setLocalMatrix(m);
            add.setShader(s);
            add.setAlpha(Math.round(255 * a * 0.55f));
            dst.drawRect(box, add);

            // 芯: 縁にぴたりと沿う細く明るい線。端は柔らかく消える
            m.setScale(len * 0.72f, len * 0.055f);
            m.postRotate(pt[2]);
            m.postTranslate(pt[0], pt[1]);
            RadialGradient c = shader(core, mix(tint, 0xFFFFFFFF, 0.6f), 0xFF, 0x66);
            c.setLocalMatrix(m);
            add.setShader(c);
            add.setAlpha(Math.round(255 * a * 0.80f));
            dst.drawRect(box, add);
        }
        add.setShader(null);
        dst.restoreToCount(save);
    }

    /** 単位円の光(中心が明るく、外へ柔らかく消える)。色ごとに一度だけ作る。 */
    private static RadialGradient shader(Map<Integer, RadialGradient> cache, int tint, int a1, int a2) {
        RadialGradient g = cache.get(tint);
        if (g == null) {
            int c = tint & 0x00FFFFFF;
            g = new RadialGradient(0, 0, 1,
                    new int[]{ 0xFF000000 | c, (a1 << 24) | c, (a2 << 24) | c, c },
                    new float[]{ 0f, 0.22f, 0.58f, 1f }, Shader.TileMode.CLAMP);
            cache.put(tint, g);
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
