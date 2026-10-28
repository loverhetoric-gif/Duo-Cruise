package com.yuki.duocruise.main;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 間接照明の発光。境界に光源を置き、そこから内側へ滑らかに減衰する光を落とす(LED の間接照明のように)。
 *
 * ・硬い輪郭線は出さない。各辺に「縁で明るく→内へ向かって透明」の線形グラデを敷くだけ。
 *   これで“帯”や“線”に見えず、ふわっと滲む本物の間接光になる。
 * ・角は四角(角丸にしない)。ぼかしも software レイヤーも使わないので、どの端末でも確実に出て、CarPlay も壊さない。
 * ・状態は「強弱(intensity)」と「色(color)」で表す。phase でゆっくり明滅させる。
 */
final class NeonGlow {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    NeonGlow() {}

    private static int withAlpha(int color, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF); }

    /** 既定(白)で描く。radius は使わない(間接照明は角丸にしない)。 */
    void draw(Canvas dst, RectF box, float dp, float radius, float intensity, float phase) {
        draw(dst, box, dp, radius, intensity, Theme.NEON_WHITE, phase);
    }

    /**
     * box の各辺に沿って発光させる。Duo Pad と同じ二層構造:
     *   ・リム … 縁のすぐ内側だけ明るい、細い光(実測ピーク≈144)
     *   ・裾  … そこから内側へ、広く・ゆるやかに減衰する微光(実測 51→20 を約140px かけて)
     * これで“線”や“帯”に見えず、本物の間接照明になる。intensity 0〜1、phase 0〜1(明滅)。
     */
    void draw(Canvas dst, RectF box, float dp, float radius, float intensity, int color, float phase) {
        if (intensity <= 0 || box.width() < 2 || box.height() < 2) return;
        float k = Math.min(1f, intensity);
        float breath = 0.86f + 0.14f * (float) Math.sin(phase * 2 * Math.PI);
        float shortSide = Math.min(box.width(), box.height());
        edges(dst, box, Math.min(52 * dp, shortSide * 0.5f), color, Math.round(40 * k * breath));  // 広い裾
        edges(dst, box, Math.min(3.5f * dp, shortSide * 0.3f), color, Math.round(140 * k * breath)); // 細いリム
    }

    /** 各辺に「縁で c0A → 内側へ透明」の線形グラデを depth ぶん敷く。 */
    private void edges(Canvas dst, RectF box, float depth, int color, int c0A) {
        if (depth < 1 || c0A <= 0) return;
        int c0 = withAlpha(color, c0A), c1 = withAlpha(color, 0);
        float l = box.left, t = box.top, r = box.right, b = box.bottom;
        p.setShader(new LinearGradient(0, t, 0, t + depth, c0, c1, Shader.TileMode.CLAMP));
        dst.drawRect(l, t, r, t + depth, p);
        p.setShader(new LinearGradient(0, b, 0, b - depth, c0, c1, Shader.TileMode.CLAMP));
        dst.drawRect(l, b - depth, r, b, p);
        p.setShader(new LinearGradient(l, 0, l + depth, 0, c0, c1, Shader.TileMode.CLAMP));
        dst.drawRect(l, t, l + depth, b, p);
        p.setShader(new LinearGradient(r, 0, r - depth, 0, c0, c1, Shader.TileMode.CLAMP));
        dst.drawRect(r - depth, t, r, b, p);
        p.setShader(null);
    }
}
