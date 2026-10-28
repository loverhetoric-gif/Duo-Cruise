package com.yuki.duocruise.main;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;

/**
 * Duo Pad と同じ質感の、柔らかく滲んで“生きて揺らぐ”白い発光の縁。アプリ全体で使い、一体感を出す。
 *
 * 肝: ぼかし(BlurMaskFilter)は使うが、CarPlay の映像(SurfaceView)の上に software レイヤーの View は重ねない。
 * ぼかしは「低解像度のオフスクリーン画像」に焼き、それをハードウェアで拡大合成する。
 * こうすると、映像の透過穴を塞がずに、本物の柔らかい発光が出せる(しかも軽い)。
 *
 * 1つの View につき1つ持ち、onDraw から draw() を呼ぶ。phase を少しずつ動かすと光が揺らぐ。
 */
final class NeonGlow {
    private static final float SCALE = 0.28f; // 低解像度(この倍率でぼかす。十分滑らか・軽い)

    private Bitmap bmp;
    private Canvas bc;
    private int bw, bh;
    private BlurMaskFilter blurFilter;
    private int blurKey = -1;

    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();

    NeonGlow() {
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeJoin(Paint.Join.ROUND);
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeJoin(Paint.Join.ROUND);
    }

    private static int withAlpha(int color, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF); }

    /** 既定(白)で描く。 */
    void draw(Canvas dst, RectF box, float dp, float radius, float intensity, float phase) {
        draw(dst, box, dp, radius, intensity, Theme.NEON_WHITE, phase);
    }

    /**
     * box に沿って発光する縁を描く。intensity は 0〜1、phase は 0〜1(揺らぎ)。
     * 柔らかい外側の光は低解像度のぼかし画像、くっきりした芯はベクタで重ねる。
     */
    void draw(Canvas dst, RectF box, float dp, float radius, float intensity, int color, float phase) {
        if (intensity <= 0 || box.width() < 2 || box.height() < 2) return;
        float k = Math.min(1f, intensity);
        // 全体のゆっくりした明滅(生きている光)
        float shimmer = 0.80f + 0.20f * (float) Math.sin(phase * 2 * Math.PI);

        int w = Math.max(2, Math.round(box.width() * SCALE));
        int h = Math.max(2, Math.round(box.height() * SCALE));
        if (bmp == null || bw != w || bh != h) {
            if (bmp != null) bmp.recycle();
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bc = new Canvas(bmp);
            bw = w; bh = h;
        }
        bmp.eraseColor(0);

        float cx = w / 2f, cy = h / 2f;
        // ぼかし半径は絵の大きさに合わせる。細い光源を強くぼかして“柔らかいブルーム”にする(硬い線は出さない)
        int blurR = Math.max(3, Math.round(Math.min(w, h) * 0.11f));
        float rad = Math.max(0, radius * SCALE);

        // 角度グラデ(明るい弧が縁を巡る)。phase で回す = 光が縁を流れる(Duo Pad と同じ)
        int hi = Math.round(165 * k * shimmer), lo = Math.round(28 * k * shimmer), mid = Math.round(95 * k * shimmer);
        int[] cols = { withAlpha(color, hi), withAlpha(color, lo), withAlpha(color, mid), withAlpha(color, lo), withAlpha(color, hi) };
        SweepGradient sg = new SweepGradient(cx, cy, cols, null);
        sweepM.setRotate(phase * 360f, cx, cy);
        sg.setLocalMatrix(sweepM);

        // 近い所の明るいブルーム(細い光源＋大きめのぼかし)
        float inset1 = blurR + 2;
        RectF r1 = new RectF(inset1, inset1, w - inset1, h - inset1);
        halo.setShader(sg);
        halo.setMaskFilter(blur(blurR));
        halo.setStrokeWidth(Math.max(1f, blurR * 0.5f));
        bc.drawRoundRect(r1, rad, rad, halo);
        halo.setShader(null);

        // 外へ広がる、ごく淡い光(一様)。輪郭の“帯”に見えないよう、さらに大きくぼかす
        float inset2 = blurR * 1.6f + 2;
        RectF r2 = new RectF(inset2, inset2, w - inset2, h - inset2);
        halo.setMaskFilter(blur(Math.round(blurR * 2.1f)));
        halo.setStrokeWidth(Math.max(1f, blurR * 0.4f));
        halo.setColor(withAlpha(color, Math.round(42 * k * shimmer)));
        bc.drawRoundRect(r2, rad, rad, halo);

        // 本体へ拡大合成(双線形でなめらか)。くっきりした芯は描かない(安っぽく見えるため)
        m.setScale(box.width() / w, box.height() / h);
        m.postTranslate(box.left, box.top);
        dst.drawBitmap(bmp, m, blit);
    }

    private BlurMaskFilter blur(int r) {
        if (blurFilter == null || blurKey != r) { blurFilter = new BlurMaskFilter(Math.max(1, r), BlurMaskFilter.Blur.NORMAL); blurKey = r; }
        return blurFilter;
    }

    private final Matrix sweepM = new Matrix();
}
