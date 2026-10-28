package com.yuki.duocruise.main;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;

/**
 * 柔らかく滲んで“生きて揺らぐ”発光する縁。アプリ全体で使い、一体感を出す。
 *
 * 肝: ぼかし(BlurMaskFilter)や software レイヤー、オフスクリーン画像は使わない。
 * この端末では「オフスクリーンにぼかしを焼いて合成」が描画されなかったため、
 * ハードウェア描画のまま「細→太の同心ストロークを多数、滑らかな濃淡で重ねて」本物のブルームにする。
 * CarPlay の映像(SurfaceView)も壊さない。
 *
 * 明るい弧が縁を巡る動きは、SweepGradient(角度グラデ)を phase で回して出す。
 */
final class NeonGlow {
    private static final int LAYERS = 16;      // 重ねる本数(多いほど滑らか)

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sweepM = new Matrix();

    NeonGlow() {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    private static int withAlpha(int color, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF); }

    /** 既定(白)で描く。 */
    void draw(Canvas dst, RectF box, float dp, float radius, float intensity, float phase) {
        draw(dst, box, dp, radius, intensity, Theme.NEON_WHITE, phase);
    }

    /**
     * box に沿って柔らかく発光する縁を描く。intensity は 0〜1、phase は 0〜1(揺らぎ・光の巡り)。
     * 細い芯は明るく、外へ向かって太く淡く — の重ねで、硬い線を出さずに滲ませる。
     */
    void draw(Canvas dst, RectF box, float dp, float radius, float intensity, int color, float phase) {
        if (intensity <= 0 || box.width() < 2 || box.height() < 2) return;
        float k = Math.min(1f, intensity);
        float shimmer = 0.82f + 0.18f * (float) Math.sin(phase * 2 * Math.PI);  // ゆっくり明滅
        float cx = box.centerX(), cy = box.centerY();

        // 明るい弧が縁を巡る(角度グラデを phase で回す)
        int[] cols = {
                withAlpha(color, 255), withAlpha(color, 85), withAlpha(color, 190),
                withAlpha(color, 85), withAlpha(color, 255)
        };
        SweepGradient sg = new SweepGradient(cx, cy, cols, null);
        sweepM.setRotate(phase * 360f, cx, cy);
        sg.setLocalMatrix(sweepM);
        p.setShader(sg);

        float maxW = 20 * dp;        // いちばん外側(いちばん太く淡い)の幅
        float coreW = 1.4f * dp;     // いちばん内側(芯)の幅
        for (int i = 0; i < LAYERS; i++) {
            float t = i / (float) (LAYERS - 1);          // 0=外(太・淡) … 1=内(細・明)
            float w = maxW * (1 - t) + coreW;
            // 芯に近いほど明るい。外側は広く薄い裾を引く(ガウスの裾のように)
            float a = (0.05f + 0.95f * t * t) * k * shimmer;
            p.setStrokeWidth(w);
            p.setAlpha(Math.round(255 * Math.min(1f, a) * 0.6f));
            dst.drawRoundRect(box, radius, radius, p);
        }
        p.setShader(null);
    }
}
