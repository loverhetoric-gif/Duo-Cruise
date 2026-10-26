package com.yuki.duocruise.main;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** 色と形。暗い車内で読みやすく、選んでいるものがひと目で分かること。 */
final class Theme {
    private Theme() {}

    static final int BG = Color.parseColor("#0E1420");
    static final int SCRIM = Color.parseColor("#F00E1420");
    static final int CARD = Color.parseColor("#172033");
    static final int CARD_FOCUS = Color.parseColor("#24314D");
    static final int CAR_BG = Color.parseColor("#101826");
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
