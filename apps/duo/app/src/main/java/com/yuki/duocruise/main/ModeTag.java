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
 *   ・画面の中央・上から降りてくる。上端にそのモードの色の細い光の筋、そこから下へ柔らかくにじむ。
 *   ・黒の透過の板(角は丸めない)に、中央寄せの白いモード名。初めてのときだけ、下に小さな一行の説明。
 *   ・出入りは Motion の緩急で、上からスライド。入って約2秒そのまま、引く。
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
        name.setTextAlign(Paint.Align.CENTER);
        cap.setColor(0xB8FFFFFF);
        cap.setTextSize(12.5f * dp);
        cap.setTextAlign(Paint.Align.CENTER);
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
        int w = getWidth();
        float pad = 16 * dp;
        boolean hasCap = !caption.isEmpty();
        float tagH = (hasCap ? 60 : 44) * dp;
        float contentW = Math.max(name.measureText(label), hasCap ? cap.measureText(caption) : 0);
        float tagW = contentW + pad * 2;
        float margin = 14 * dp;                         // 上端からの余白
        float left = (w - tagW) / 2f;                   // 画面中央(横)
        float right = left + tagW;
        float cx = (left + right) / 2f;
        // 上からスライド: in=0 で画面の上に隠れ、in=1 で上端の余白までぴたり
        float top = -tagH + (tagH + margin) * in;
        float bottom = top + tagH;

        int a = Math.round(255 * Math.min(1f, in * 1.3f));
        plate.setAlpha(Math.round((0xCC / 255f) * a));
        cv.drawRect(left, top, right, bottom, plate);

        // 上端の色の筋と、下へのにじみ
        float g = 22 * dp;
        glow.setShader(new LinearGradient(0, top, 0, top + g,
                new int[]{withA(color, Math.round(0x4D / 255f * a)), withA(color, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(left, top, right, top + g, glow);
        core.setColor(withA(color, a));
        cv.drawRect(left, top, right, top + 2 * dp, core);

        // 文字(中央寄せ)
        name.setAlpha(a);
        cap.setAlpha(Math.round(0xB8 / 255f * a));
        if (hasCap) {
            cv.drawText(label, cx, top + 26 * dp, name);
            cv.drawText(caption, cx, top + 46 * dp, cap);
        } else {
            cv.drawText(label, cx, top + tagH / 2 + name.getTextSize() * 0.36f, name);
        }
    }

    private static int withA(int color, int a) { return (Math.min(255, Math.max(0, a)) << 24) | (color & 0xFFFFFF); }
}
