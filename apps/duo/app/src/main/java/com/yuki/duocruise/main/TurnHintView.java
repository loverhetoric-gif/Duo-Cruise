package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.yuki.duocruise.carplay.Turn;

/**
 * 次の曲がり角の小さな案内(Duo Cruise Unlimited Edition)。YouTube の枠の右上に重ねる。
 * 左に曲がる向きの矢印、右に「300 m」と曲がった先の道路の名前。
 *
 * 見た目: 黒の透過の四角い板(角は丸めない)に白い文字だけ。映像の邪魔をしないよう小さく、
 * 出るときは 0.25 秒でふわっと、消えるときは 0.35 秒で引く。
 */
final class TurnHintView extends View {
    static final float W_DP = 280, H_DP = 76;

    private final float dp;
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private int turn = Turn.NONE, drivingSide = 1;
    private Integer exitAngle;
    private String dist = "", road = "";
    private boolean shown;

    TurnHintView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        plate.setColor(0xB3000000);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(6.5f * dp);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeJoin(Paint.Join.MITER);
        fill.setColor(0xFFFFFFFF);
        big.setColor(0xFFFFFFFF);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextSize(30 * dp);
        small.setColor(0xCCFFFFFF);
        small.setTextSize(15 * dp);
        setAlpha(0f);
        setVisibility(GONE);
    }

    /** 中身を入れ替えて、出す/引っ込める。 */
    void set(boolean show, int turn, Integer exitAngle, int drivingSide, String dist, String road) {
        this.turn = turn;
        this.exitAngle = exitAngle;
        this.drivingSide = drivingSide;
        this.dist = dist == null ? "" : dist;
        this.road = road == null ? "" : road;
        invalidate();
        if (show == shown) return;
        shown = show;
        animate().cancel();
        if (show) {
            setVisibility(VISIBLE);
            setTranslationY(-6 * dp);
            animate().alpha(1f).translationY(0).setDuration(250)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        } else {
            animate().alpha(0f).setDuration(350)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(() -> { if (!shown) setVisibility(GONE); }).start();
        }
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawRect(0, 0, w, h, plate);
        float icon = h - 20 * dp;                     // 矢印の正方形
        float ix = 14 * dp, iy = 10 * dp;
        r.set(ix, iy, ix + icon, iy + icon);
        boolean drew = drawIcon(cv, r);
        float tx = drew ? r.right + 14 * dp : 16 * dp;
        float right = w - 14 * dp;
        boolean two = !road.isEmpty();
        float base = two ? h * 0.50f : h * 0.5f + big.getTextSize() * 0.36f;
        cv.drawText(fit(dist, big, right - tx), tx, base, big);
        if (two) cv.drawText(fit(road, small, right - tx), tx, h * 0.50f + 24 * dp, small);
    }

    private static String fit(String s, Paint p, float max) {
        if (p.measureText(s) <= max) return s;
        String e = "…";
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n) + e) > max) n--;
        return s.substring(0, n) + e;
    }

    /** 曲がる向きの絵。描けなければ false(文字だけにする)。 */
    private boolean drawIcon(Canvas cv, RectF b) {
        float cx = b.centerX(), cy = b.centerY(), s = b.width();
        switch (turn) {
            case Turn.STRAIGHT: case Turn.FERRY: arrow(cv, cx, b.bottom, cy, 0, s); return true;
            case Turn.SLIGHT_LEFT: arrow(cv, cx, b.bottom, cy, -45, s); return true;
            case Turn.LEFT: arrow(cv, cx, b.bottom, cy, -90, s); return true;
            case Turn.SHARP_LEFT: arrow(cv, cx, b.bottom, cy, -135, s); return true;
            case Turn.SLIGHT_RIGHT: arrow(cv, cx, b.bottom, cy, 45, s); return true;
            case Turn.RIGHT: arrow(cv, cx, b.bottom, cy, 90, s); return true;
            case Turn.SHARP_RIGHT: arrow(cv, cx, b.bottom, cy, 135, s); return true;
            case Turn.U_TURN: uTurn(cv, b, drivingSide == 0 ? -1 : 1); return true;
            case Turn.ROUNDABOUT: roundabout(cv, b); return true;
            case Turn.ARRIVE: flag(cv, b); return true;
            default: return false;
        }
    }

    /**
     * 下から上がり、折れ点から angle(度、右がプラス)へ曲がる矢印。
     * 曲がる先の腕が枠に収まるよう、折れ点は曲がる側と反対へ寄せる。
     */
    private void arrow(Canvas cv, float cx, float bottom, float cy, float angle, float s) {
        double a = Math.toRadians(angle);
        float sx = (float) Math.sin(a), sy = (float) Math.cos(a);
        boolean sharp = sy < -0.1f;                                  // 大きく曲がる(後ろ向きに戻る)
        float k = s * (sharp ? 0.24f : 0.30f);                       // 矢じりの長さ
        float px = cx - sx * s * (sharp ? 0.32f : 0.20f);            // 折れ点(曲がる側の反対へ)
        float py = angle == 0 ? bottom - s * 0.02f : sharp ? cy - s * 0.36f : cy + s * 0.06f;
        float len = angle == 0 ? s * 0.92f : sharp ? s * 0.62f : s * 0.46f; // 折れ点から矢じりの先まで
        float ex = px + sx * len, ey = py - sy * len;
        path.rewind();
        path.moveTo(px, bottom);
        path.lineTo(px, py);
        path.lineTo(ex - sx * k * 0.7f, ey + sy * k * 0.7f);           // 線は矢じりの中で止める
        cv.drawPath(path, stroke);
        head(cv, ex, ey, a, k);
    }

    /** 矢じり(向き a、大きさ k)。先端は (x,y)。 */
    private void head(Canvas cv, float x, float y, double a, float k) {
        float bx = x - (float) Math.sin(a) * k, by = y + (float) Math.cos(a) * k;   // 根もと
        float px = (float) Math.cos(a) * k * 0.62f, py = (float) Math.sin(a) * k * 0.62f;
        path.rewind();
        path.moveTo(x, y);
        path.lineTo(bx + px, by + py);
        path.lineTo(bx - px, by - py);
        path.close();
        cv.drawPath(path, fill);
    }

    /** U ターン。dir=+1 で右回り(左側通行)、-1 で左回り。 */
    private void uTurn(Canvas cv, RectF b, int dir) {
        float s = b.width(), r0 = s * 0.2f;
        float x0 = b.centerX() - dir * r0, x1 = b.centerX() + dir * r0, top = b.top + s * 0.18f;
        path.rewind();
        path.moveTo(x0, b.bottom);
        path.lineTo(x0, top + r0);
        RectF arc = new RectF(Math.min(x0, x1), top, Math.max(x0, x1), top + 2 * r0);
        path.arcTo(arc, dir > 0 ? 180 : 0, dir * 180f, false);   // 上を回って反対側へ
        path.lineTo(x1, b.bottom - s * 0.30f);
        cv.drawPath(path, stroke);
        head(cv, x1, b.bottom - s * 0.02f, Math.PI, s * 0.30f);
    }

    /** ロータリー: 小さな輪と、出口の向き(角度が分からなければ真っすぐ)へ出る矢印。 */
    private void roundabout(Canvas cv, RectF b) {
        float s = b.width(), cx = b.centerX(), cy = b.top + s * 0.42f, rr = s * 0.17f;
        cv.drawCircle(cx, cy, rr, stroke);
        path.rewind();
        path.moveTo(cx, b.bottom);
        path.lineTo(cx, cy + rr);
        cv.drawPath(path, stroke);
        double a = Math.toRadians(exitAngle == null ? 0 : exitAngle);
        float sx = cx + (float) Math.sin(a) * rr, sy = cy - (float) Math.cos(a) * rr;
        float ex = cx + (float) Math.sin(a) * (rr + s * 0.30f), ey = cy - (float) Math.cos(a) * (rr + s * 0.30f);
        path.rewind();
        path.moveTo(sx, sy);
        path.lineTo(ex - (float) Math.sin(a) * s * 0.12f, ey + (float) Math.cos(a) * s * 0.12f);
        cv.drawPath(path, stroke);
        head(cv, ex, ey, a, s * 0.26f);
    }

    /** 到着: 旗。 */
    private void flag(Canvas cv, RectF b) {
        float s = b.width(), x = b.left + s * 0.30f;
        path.rewind();
        path.moveTo(x, b.bottom);
        path.lineTo(x, b.top + s * 0.08f);
        cv.drawPath(path, stroke);
        cv.drawRect(x, b.top + s * 0.08f, x + s * 0.48f, b.top + s * 0.42f, fill);
    }
}
