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
 * 箱のホーム。一流の車載UIに倣う ―― フラット・高コントラスト・タイポグラフィ主役・縁のドック・差し色一点。
 * 発光もぼかしもグラデも使わない。時計を大きな主役に、行き先は左の静かなドック、選択は細いアクセントのバーだけ。
 *  ホーム: 左に Duo Cruise / Claude / 設定、右に時計。
 *  設定:   接続トグル(AP / Wi-Fi / Bluetooth)と Android の設定。
 */
final class HomeView extends View {
    interface Listener {
        void onTile(int i);        // 0=DuoCruise 1=Claude
        void onToggle(int i);      // 0=AP 1=Wi-Fi 2=Bluetooth
        void onAndroidSettings();
    }

    static final String[] HOME = {"Duo Cruise", "Claude", "設定"};
    static final String[] SET_ROW = {"アクセスポイント", "Wi-Fi", "Bluetooth", "Android の設定を開く", "戻る"};

    private static final int BG = 0xFF0A0B0D, WHITE = 0xFFF2F4F7, DIM = 0xFF697079, ACCENT = 0xFF5CD6FF, HAIR = 0x16FFFFFF;

    private final Listener l;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] marks = new RectF[3];
    private final RectF[] rows = new RectF[5];
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.JAPAN);
    private final SimpleDateFormat date = new SimpleDateFormat("M月d日 (EEE)", Locale.JAPAN);
    private final Typeface thin = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);

    private final int[] toggleState = {-1, -1, -1};
    private int page = 0;
    private int selected = 0;

    HomeView(Context c, Listener l) { super(c); this.l = l; }

    void setToggle(int i, int state) { if (toggleState[i] != state) { toggleState[i] = state; invalidate(); } }
    int selected() { return selected; }
    private int count() { return page == 0 ? 3 : 5; }
    void move(int dir) { selected = (selected + (dir > 0 ? 1 : -1) + count()) % count(); invalidate(); }
    void activate() { dispatch(selected); }
    boolean back() { if (page == 1) { setPage(0); return true; } return false; }
    private void setPage(int pg) { page = pg; selected = 0; invalidate(); }

    private void dispatch(int i) {
        if (page == 0) {
            if (i == 2) setPage(1);
            else l.onTile(i);
        } else {
            switch (i) {
                case 0: case 1: case 2: l.onToggle(i); break;
                case 3: l.onAndroidSettings(); break;
                case 4: setPage(0); break;
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        // 左のドック(3項目、縦に中央寄せ)
        float xL = w * 0.065f, itemH = h * 0.12f, gap = h * 0.055f, blockH = 3 * itemH + 2 * gap;
        float startY = (h - blockH) / 2f, markW = w * 0.3f;
        for (int i = 0; i < 3; i++) {
            float y = startY + i * (itemH + gap);
            marks[i] = new RectF(xL, y, xL + markW, y + itemH);
        }
        // 設定の行
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

        // 右: 時計(主役)と日付
        float xR = w * 0.66f;
        p.setTypeface(thin);
        p.setColor(WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setLetterSpacing(0f);
        p.setTextSize(h * 0.34f);
        float clockY = h * 0.5f + p.getTextSize() * 0.35f;
        cv.drawText(clock.format(new Date()), xR, clockY, p);
        p.setTypeface(reg);
        p.setColor(DIM);
        p.setTextSize(h * 0.06f);
        p.setLetterSpacing(0.18f);
        cv.drawText(date.format(new Date()), xR, clockY + h * 0.17f, p);
        p.setLetterSpacing(0f);

        // 左上: 小さな名乗り
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(DIM);
        p.setTextSize(h * 0.045f);
        p.setLetterSpacing(0.26f);
        cv.drawText("DUO HOME", w * 0.065f, h * 0.13f, p);
        p.setLetterSpacing(0f);

        // 左: ドック(行き先)。選択だけ白＋細いアクセントのバー。
        for (int i = 0; i < 3; i++) {
            boolean sel = selected == i;
            RectF r = marks[i];
            if (sel) {
                p.setColor(ACCENT);
                cv.drawRect(r.left - w * 0.022f, r.centerY() - r.height() * 0.32f, r.left - w * 0.022f + 4f, r.centerY() + r.height() * 0.32f, p);
            }
            p.setTypeface(sel ? reg : thin);
            p.setColor(sel ? WHITE : DIM);
            p.setTextSize(h * 0.09f);
            p.setTextAlign(Paint.Align.LEFT);
            cv.drawText(HOME[i], r.left, r.centerY() + p.getTextSize() * 0.35f, p);
        }
    }

    private void drawSettings(Canvas cv) {
        int w = getWidth(), h = getHeight();
        p.setTypeface(reg);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(DIM);
        p.setTextSize(h * 0.05f);
        p.setLetterSpacing(0.2f);
        cv.drawText("設定", w * 0.065f, h * 0.14f, p);
        p.setLetterSpacing(0f);
        p.setTypeface(thin);
        p.setColor(WHITE);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(h * 0.09f);
        cv.drawText(clock.format(new Date()), w * (1 - 0.065f), h * 0.145f, p);

        for (int i = 0; i < 5; i++) drawRow(cv, rows[i], i, selected == i);
    }

    private void drawRow(Canvas cv, RectF r, int i, boolean sel) {
        boolean isToggle = i < 3;
        boolean on = isToggle && toggleState[i] == 1;
        // 上辺の細いヘアライン(区切り)
        p.setColor(HAIR);
        cv.drawRect(r.left, r.top, r.right, r.top + 1f, p);
        // 選択: 左に差し色のバー
        if (sel) {
            p.setColor(ACCENT);
            cv.drawRect(r.left, r.centerY() - r.height() * 0.3f, r.left + 4f, r.centerY() + r.height() * 0.3f, p);
        }
        p.setTypeface(sel ? reg : thin);
        p.setColor(sel ? WHITE : (on || !isToggle ? 0xFFBBC2CB : DIM));
        p.setTextSize(r.height() * 0.34f);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(SET_ROW[i], r.left + r.height() * 0.5f, r.centerY() + p.getTextSize() * 0.35f, p);
        // 右: 状態 / しるし
        p.setTextAlign(Paint.Align.RIGHT);
        if (isToggle) {
            p.setColor(on ? ACCENT : DIM);
            cv.drawText(toggleState[i] == -1 ? "—" : on ? "ON" : "OFF", r.right, r.centerY() + p.getTextSize() * 0.35f, p);
        } else {
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
