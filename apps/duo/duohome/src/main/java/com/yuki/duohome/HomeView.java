package com.yuki.duohome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 箱のホーム。フラット・高コントラスト・タイポグラフィ主役・縁のドック・差し色一点(一流の車載UIに倣う)。
 * 書体は同梱の Inter(可変。ラテン)を使い、和文は端末のゴシックで出し分ける。発光・ぼかし・グラデは使わない。
 */
final class HomeView extends View {
    interface Listener {
        void onTile(int i);        // 0=DuoCruise 1=Claude
        void onToggle(int i);
        void onAndroidSettings();
    }

    static final String[] HOME = {"Duo Cruise", "Claude", "設定"};
    static final String[] SET_ROW = {"アクセスポイント", "Wi-Fi", "Bluetooth", "Android の設定を開く", "戻る"};
    private static final int BG = 0xFF0A0B0D, WHITE = 0xFFF4F6F8, DIM = 0xFF6A717A, ACCENT = 0xFF5CD6FF, HAIR = 0x16FFFFFF;

    private final Listener l;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] marks = new RectF[3];
    private final RectF[] rows = new RectF[5];
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.JAPAN);
    private final SimpleDateFormat date = new SimpleDateFormat("M月d日 EEE", Locale.JAPAN);

    private Typeface iThin, iLight, iReg, iMed;             // Inter(ラテン)
    private final Typeface jp = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface jpL = Typeface.create("sans-serif-light", Typeface.NORMAL);

    private final int[] toggleState = {-1, -1, -1};
    private int page = 0, selected = 0;

    HomeView(Context c, Listener l) {
        super(c);
        this.l = l;
        try {
            iThin = inter(c, "'wght' 250");
            iLight = inter(c, "'wght' 300");
            iReg = inter(c, "'wght' 400");
            iMed = inter(c, "'wght' 500");
        } catch (Throwable t) {
            iThin = jpL; iLight = jpL; iReg = jp; iMed = jp;
        }
        if (iReg == null) { iThin = jpL; iLight = jpL; iReg = jp; iMed = jp; }
    }

    private static Typeface inter(Context c, String var) {
        return new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf").setFontVariationSettings(var).build();
    }

    /** ラテン(ASCII)だけなら Inter、和文を含むなら端末ゴシック。 */
    private Typeface tf(String s, Typeface latin, Typeface jpTf) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return jpTf;
        return latin;
    }

    void setToggle(int i, int state) { if (toggleState[i] != state) { toggleState[i] = state; invalidate(); } }
    int selected() { return selected; }
    private int count() { return page == 0 ? 3 : 5; }
    void move(int dir) { selected = (selected + (dir > 0 ? 1 : -1) + count()) % count(); invalidate(); }
    void activate() { dispatch(selected); }
    boolean back() { if (page == 1) { setPage(0); return true; } return false; }
    private void setPage(int pg) { page = pg; selected = 0; invalidate(); }

    private void dispatch(int i) {
        if (page == 0) { if (i == 2) setPage(1); else l.onTile(i); }
        else switch (i) {
            case 0: case 1: case 2: l.onToggle(i); break;
            case 3: l.onAndroidSettings(); break;
            case 4: setPage(0); break;
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float xL = w * 0.065f, itemH = h * 0.12f, gap = h * 0.055f, blockH = 3 * itemH + 2 * gap;
        float startY = (h - blockH) / 2f + h * 0.03f, markW = w * 0.3f;
        for (int i = 0; i < 3; i++) {
            float y = startY + i * (itemH + gap);
            marks[i] = new RectF(xL, y, xL + markW, y + itemH);
        }
        float rTop = h * 0.26f, rH = h * 0.12f, rGap = h * 0.022f, side = w * 0.065f, rW = w - 2 * side;
        for (int i = 0; i < 5; i++) {
            float y = rTop + i * (rH + rGap);
            rows[i] = new RectF(side, y, side + rW, y + rH);
        }
    }

    @Override protected void onDraw(Canvas cv) {
        cv.drawColor(BG);
        if (page == 0) drawHome(cv); else drawSettings(cv);
    }

    private void drawHome(Canvas cv) {
        int w = getWidth(), h = getHeight();

        // 名乗り(左上・小さく字間を広く)
        p.setTypeface(iReg);
        p.setColor(DIM);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(h * 0.04f);
        p.setLetterSpacing(0.32f);
        cv.drawText("DUO HOME", w * 0.065f, h * 0.135f, p);
        p.setLetterSpacing(0f);

        // 時計(主役)と日付(右)
        float xR = w * 0.66f;
        p.setTypeface(iLight);
        p.setColor(WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.36f);
        p.setLetterSpacing(-0.02f);
        float clockY = h * 0.5f + p.getTextSize() * 0.35f;
        cv.drawText(clock.format(new Date()), xR, clockY, p);
        p.setLetterSpacing(0f);
        p.setTypeface(jp);
        p.setColor(DIM);
        p.setTextSize(h * 0.055f);
        p.setLetterSpacing(0.18f);
        cv.drawText(date.format(new Date()), xR, clockY + h * 0.16f, p);
        p.setLetterSpacing(0f);

        // ドック(行き先)。選択だけ白＋細い差し色のバー。
        p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < 3; i++) {
            boolean sel = selected == i;
            RectF r = marks[i];
            if (sel) {
                p.setColor(ACCENT);
                cv.drawRect(r.left - w * 0.022f, r.centerY() - r.height() * 0.3f, r.left - w * 0.022f + 4f, r.centerY() + r.height() * 0.3f, p);
            }
            p.setColor(sel ? WHITE : DIM);
            p.setTextSize(h * 0.088f);
            p.setTypeface(tf(HOME[i], sel ? iMed : iLight, sel ? jp : jpL));
            cv.drawText(HOME[i], r.left, r.centerY() + p.getTextSize() * 0.35f, p);
        }
    }

    private void drawSettings(Canvas cv) {
        int w = getWidth(), h = getHeight();
        p.setTypeface(jp);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(DIM);
        p.setTextSize(h * 0.05f);
        p.setLetterSpacing(0.2f);
        cv.drawText("設定", w * 0.065f, h * 0.14f, p);
        p.setLetterSpacing(0f);
        p.setTypeface(iLight);
        p.setColor(WHITE);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(h * 0.09f);
        cv.drawText(clock.format(new Date()), w * (1 - 0.065f), h * 0.145f, p);
        for (int i = 0; i < 5; i++) drawRow(cv, rows[i], i, selected == i);
    }

    private void drawRow(Canvas cv, RectF r, int i, boolean sel) {
        boolean isToggle = i < 3;
        boolean on = isToggle && toggleState[i] == 1;
        p.setColor(HAIR);
        cv.drawRect(r.left, r.top, r.right, r.top + 1f, p);
        if (sel) {
            p.setColor(ACCENT);
            cv.drawRect(r.left, r.centerY() - r.height() * 0.3f, r.left + 4f, r.centerY() + r.height() * 0.3f, p);
        }
        p.setColor(sel ? WHITE : (on || !isToggle ? 0xFFB8BFC8 : DIM));
        p.setTextSize(r.height() * 0.33f);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(tf(SET_ROW[i], sel ? iMed : iReg, sel ? jp : jp));
        cv.drawText(SET_ROW[i], r.left + r.height() * 0.5f, r.centerY() + p.getTextSize() * 0.35f, p);
        p.setTextAlign(Paint.Align.RIGHT);
        if (isToggle) {
            p.setTypeface(iMed);
            p.setColor(on ? ACCENT : DIM);
            cv.drawText(toggleState[i] == -1 ? "—" : on ? "ON" : "OFF", r.right, r.centerY() + p.getTextSize() * 0.35f, p);
        } else {
            p.setTypeface(iReg);
            p.setColor(sel ? WHITE : DIM);
            cv.drawText(i == 4 ? "‹" : "›", r.right, r.centerY() + r.height() * 0.15f, p);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            RectF[] set = page == 0 ? marks : rows;
            for (int i = 0; i < count(); i++) if (set[i] != null && set[i].contains(x, y)) { selected = i; invalidate(); dispatch(i); return true; }
        }
        return true;
    }
}
