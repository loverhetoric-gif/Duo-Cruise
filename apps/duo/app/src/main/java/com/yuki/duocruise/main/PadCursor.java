package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Duo Slate のカーソル(丸いオーブ)。Duo Cruise の中でも、ホーム等(汎用モード)でも同じものを使う。
 *
 * 見た目: 半透明の丸い玉。中心ほど明るいガラスのような面に、白い細い輪と外側の薄い影の輪、
 * その外にネオンの青の柔らかい光。暗い車内でも明るい地図の上でも、輪のどちらかが背景から浮く。
 * 指している所は玉の中心(置くときは hot() だけ左上へずらす)。
 */
final class PadCursor extends View {
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp, r;

    PadCursor(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        r = 11 * dp;
        // ぼかしは software でしか効かないので、この小さな View だけ software 描画にする
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        glow.setColor(Theme.ACCENT);
        glow.setAlpha(120);
        glow.setMaskFilter(new BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL));

        body.setStyle(Paint.Style.FILL);

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.3f * dp);
        ring.setColor(0xDDFFFFFF);

        shade.setStyle(Paint.Style.STROKE);
        shade.setStrokeWidth(1f * dp);
        shade.setColor(0x47000000);
        setWillNotDraw(false);
    }

    int size() { return Math.round(40 * dp); }

    /** 指している所(玉の中心)の、View の左上からのずれ。 */
    float hot() { return size() / 2f; }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(size(), size()); }

    @Override protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        // 面: 中心が明るく、縁へ向かって透ける(ガラスの玉)
        body.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.25f,
                new int[]{0xB3FFFFFF, 0x80E9F0FF, 0x4DC9D6EE}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r + 1.5f * dp, glow);   // 柔らかい青い光
        cv.drawCircle(cx, cy, r, body);               // 半透明の面
        cv.drawCircle(cx, cy, r + 1.1f * dp, shade);  // 外側の薄い影の輪(明るい背景で輪郭を残す)
        cv.drawCircle(cx, cy, r, ring);               // 白い細い輪
    }
}
