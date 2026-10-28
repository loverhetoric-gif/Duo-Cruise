package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * Duo Slate のカーソル(矢印)。Duo Cruise の中でも、ホーム等(汎用モード)でも同じものを使う。
 *
 * 見た目: 柔らかい発光の縁 + 白い本体 + ほのかな青みのハイライト。
 * 暗い車内でも、明るい地図の上でも、輪郭が消えないように、外側にぼかした光を一枚敷く。
 * 指している所は矢印の左上の角。
 */
final class PadCursor extends View {
    private static final int ACCENT = Theme.ACCENT; // 柔らかい青

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float dp;

    PadCursor(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        // ぼかしは software でしか効かないので、この小さな View だけ software 描画にする
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        glow.setColor(ACCENT);
        glow.setStyle(Paint.Style.FILL_AND_STROKE);
        glow.setStrokeWidth(3 * dp);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setMaskFilter(new BlurMaskFilter(5 * dp, BlurMaskFilter.Blur.NORMAL));

        body.setStyle(Paint.Style.FILL);

        edge.setColor(0x7A0B1220); // うっすい濃紺の縁(明るい背景でも輪郭が残る)
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.4f * dp);
        edge.setStrokeJoin(Paint.Join.ROUND);

        float u = dp;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * u);
        arrow.lineTo(4.5f * u, 13 * u);
        arrow.lineTo(7.5f * u, 20 * u);
        arrow.lineTo(10 * u, 19 * u);
        arrow.lineTo(7 * u, 12 * u);
        arrow.lineTo(12.5f * u, 12 * u);
        arrow.close();
        setWillNotDraw(false);
    }

    int size() { return Math.round(28 * dp); }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(size(), size()); }

    @Override protected void onDraw(Canvas cv) {
        // 本体は白から、わずかに青みがかった白へ(つやを出す)
        body.setShader(new LinearGradient(0, 0, 0, 20 * dp,
                Color.WHITE, 0xFFEAF2FF, Shader.TileMode.CLAMP));
        cv.save();
        cv.translate(2.2f * dp, 2.2f * dp);
        glow.setAlpha(150);
        cv.drawPath(arrow, glow);   // 柔らかい青い光
        cv.drawPath(arrow, body);   // 白い本体
        cv.drawPath(arrow, edge);   // 細い縁
        cv.restore();
    }
}
