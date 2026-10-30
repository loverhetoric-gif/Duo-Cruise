package com.yuki.duocruise.main;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * 自動で画面が変わったとき、何のモードに入った/戻ったかを、画面の端からすっと出して伝える小さな帯。
 *
 * ねらいは「混乱させない」。駐車で YouTube が全画面になった、曲がり角で地図が全画面になった——
 * そういう自動の切り替えに名前(Parking Mode など)を添えて、数秒で引っ込める。
 *
 * 見た目は縁の光と同じ考え方で、さりげなく:
 *   ・端(左か右)に、そのモードの色の細い光の筋。そこから内側へ柔らかくにじむ。
 *   ・黒の透過の板(角は丸めない)に、白いモード名。初めてのときだけ、下に小さな一行の説明。
 *   ・出入りは Motion の緩急で、端からスライド。入って約2秒そのまま、引く。
 */
final class ModeTag extends View {
    private final float dp;
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String label = "", caption = "";
    private int color = Theme.NEON_WHITE;
    private boolean fromLeft = true;
    /** 0＝端の外(隠れている)、1＝端にぴたり。入って保って引く。 */
    private float in;
    private ValueAnimator anim;
    private final Runnable retract = this::retract;

    ModeTag(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        plate.setColor(0xCC0A0A0C);
        core.setStyle(Paint.Style.FILL);
        name.setColor(0xFFFFFFFF);
        name.setFakeBoldText(true);
        name.setTextSize(19 * dp);
        cap.setColor(0xB8FFFFFF);
        cap.setTextSize(12.5f * dp);
        setVisibility(GONE);
    }

    /** モードの帯を出す。label＝名前、caption＝初回だけの一行説明(無ければ空)、color＝そのモードの色、fromLeft＝左端から。 */
    void show(String label, String caption, int color, boolean fromLeft) {
        this.label = label != null ? label : "";
        this.caption = caption != null ? caption : "";
        this.color = color;
        this.fromLeft = fromLeft;
        removeCallbacks(retract);
        setVisibility(VISIBLE);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(in, 1f);
        anim.setDuration(Math.round(Motion.MS * (1 - in)));
        anim.setInterpolator(Motion.EASE);
        anim.addUpdateListener(a -> { in = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
        // 初回の説明つきは少し長く置く
        postDelayed(retract, this.caption.isEmpty() ? 2200 : 3400);
    }

    private void retract() {
        removeCallbacks(retract);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(in, 0f);
        anim.setDuration(Math.round(Motion.MS * in));
        anim.setInterpolator(Motion.EASE);
        anim.addUpdateListener(a -> { in = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { if (in <= 0f) setVisibility(GONE); }
        });
        anim.start();
    }

    /** いま出ているか(引っ込め中も含む)。 */
    boolean showing() { return getVisibility() == VISIBLE && in > 0; }

    @Override protected void onDraw(Canvas cv) {
        if (in <= 0 || label.isEmpty()) return;
        int h = getHeight(), w = getWidth();
        float pad = 16 * dp, gap = 10 * dp;
        boolean hasCap = !caption.isEmpty();
        float tagH = (hasCap ? 60 : 44) * dp;
        float contentW = Math.max(name.measureText(label), hasCap ? cap.measureText(caption) : 0);
        float tagW = contentW + pad * 2;
        float top = (h - tagH) / 2f;
        // 端からスライド: in=0 で端の外、in=1 で端にぴたり
        float left = fromLeft ? tagW * (in - 1) : w - tagW * in;
        float right = left + tagW;

        int a = Math.round(255 * Math.min(1f, in * 1.3f));
        plate.setAlpha(Math.round((0xCC / 255f) * a));
        cv.drawRect(left, top, right, top + tagH, plate);

        // 端(画面の縁側)の光の筋と、内側へのにじみ
        float edge = fromLeft ? left : right;
        float dir = fromLeft ? 1 : -1;
        float g = 26 * dp;
        glow.setShader(new LinearGradient(edge, 0, edge + dir * g, 0,
                new int[]{withA(color, Math.round(0x4D / 255f * a)), withA(color, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(Math.min(edge, edge + dir * g), top, Math.max(edge, edge + dir * g), top + tagH, glow);
        core.setColor(withA(color, a));
        cv.drawRect(fromLeft ? left : right - 2 * dp, top, fromLeft ? left + 2 * dp : right, top + tagH, core);

        // 文字
        name.setAlpha(a);
        cap.setAlpha(Math.round(0xB8 / 255f * a));
        float tx = left + pad;
        if (hasCap) {
            cv.drawText(label, tx, top + 26 * dp, name);
            cv.drawText(caption, tx, top + 46 * dp, cap);
        } else {
            cv.drawText(label, tx, top + tagH / 2 + name.getTextSize() * 0.36f, name);
        }
    }

    private static int withA(int color, int a) { return (Math.min(255, Math.max(0, a)) << 24) | (color & 0xFFFFFF); }
}
