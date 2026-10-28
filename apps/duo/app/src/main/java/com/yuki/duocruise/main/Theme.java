package com.yuki.duocruise.main;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** 色と形。暗い車内で読みやすく、選んでいるものがひと目で分かること。 */
final class Theme {
    private Theme() {}

    // ── 発光する縁(このアプリの統一した見た目)。基本は白、状態で色が変わる ──────
    /** 既定(白)。状態に応じて下の色に変わる。 */
    static final int NEON_WHITE = 0xFFFFFFFF;
    /** CarPlay が iPhone とつながっている(良好)。 */
    static final int NEON_LINK = 0xFF59E3B0;   // ティール
    /** ナビ案内中。 */
    static final int NEON_NAV = 0xFF4F8CFF;    // 青
    /** 注意・切断など。 */
    static final int NEON_ALERT = 0xFFFF6B6B;  // コーラル

    private static final Paint NEON_HALO = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint NEON_EDGE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static BlurMaskFilter neonBlur;
    static {
        NEON_HALO.setStyle(Paint.Style.STROKE);
        NEON_EDGE.setStyle(Paint.Style.STROKE);
    }

    private static int withAlpha(int color, int a) { return (Math.min(255, Math.max(0, a)) << 24) | (color & 0xFFFFFF); }

    /** 白く発光する縁(既定の色)。 */
    static void neonEdge(Canvas cv, RectF box, float dp, float radius, float intensity) {
        neonEdge(cv, box, dp, radius, intensity, NEON_WHITE);
    }

    /**
     * 発光する縁を描く。選択行・メニュー枠・操作中の画面など、強調したい輪郭はすべてこれで統一する。
     * 基本は白(NEON_WHITE)。状態に応じて color を変える。
     * ぼかしを効かせるため、描く View は software レイヤーにしておくこと。intensity は 0〜1。
     */
    static void neonEdge(Canvas cv, RectF box, float dp, float radius, float intensity, int color) {
        if (intensity <= 0) return;
        if (neonBlur == null) neonBlur = new BlurMaskFilter(12 * dp, BlurMaskFilter.Blur.NORMAL);
        float k = Math.min(1f, intensity);
        NEON_HALO.setStrokeWidth(8 * dp);
        NEON_HALO.setMaskFilter(neonBlur);
        NEON_HALO.setColor(withAlpha(color, Math.round(95 * k)));
        cv.drawRoundRect(box, radius, radius, NEON_HALO);
        NEON_EDGE.setStrokeWidth(1.8f * dp);
        NEON_EDGE.setColor(withAlpha(color, Math.round(255 * k)));
        cv.drawRoundRect(box, radius, radius, NEON_EDGE);
    }

    static final int BG = Color.parseColor("#0E1420");
    static final int SCRIM = Color.parseColor("#F00E1420");
    static final int CARD = Color.parseColor("#172033");
    static final int CARD_FOCUS = Color.parseColor("#24314D");
    static final int ACCENT = Color.parseColor("#4F8CFF");
    static final int GREEN = Color.parseColor("#1F8F6E");
    static final int AMBER = Color.parseColor("#B07A1E");
    static final int RED = Color.parseColor("#E5484D");
    static final int MUTED = Color.parseColor("#3A4660");
    static final int TEXT = Color.parseColor("#E8EEF8");
    static final int SUB = Color.parseColor("#8FA0BC");

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** ボタンの背景。選んでいる間は明るく、白い縁(3dp)。 */
    static Drawable tile(int color, float dp) {
        GradientDrawable on = round(CARD_FOCUS, 14 * dp);
        on.setStroke(Math.round(3 * dp), Color.WHITE);
        GradientDrawable off = round(color, 14 * dp);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{}, off);
        return s;
    }
}
