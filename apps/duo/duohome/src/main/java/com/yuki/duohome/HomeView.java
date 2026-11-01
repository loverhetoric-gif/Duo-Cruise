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
 * 箱のホーム。一流の車載UI(Rivian 等)に倣った二段組 ―― 上部ステータス・左ナビレール・右の内容パネル。
 * 画面遷移はしない。レールで選び、右のパネルがその項目の内容(起動カード / 設定のトグル)に差し替わる。
 * 書体は同梱の Inter(ラテン)、和文は端末ゴシック。発光・ぼかし・グラデは使わない。差し色は一点(シアン)。
 */
final class HomeView extends View {
    interface Listener {
        void onTile(int i);        // 0=DuoCruise 1=Claude
        void onToggle(int i);      // 0=AP 1=Wi-Fi 2=Bluetooth
        void onAndroidSettings();
    }

    static final String[] RAIL = {"Duo Cruise", "Claude", "設定"};
    static final String[] RAIL_SUB = {"CarPlay と YouTube を並べて", "AI アシスタント", "接続とシステム"};
    static final String[] SET_ROW = {"アクセスポイント", "Wi-Fi", "Bluetooth", "Android の設定を開く"};

    private static final int BG = 0xFF0A0B0D, PANEL = 0x0CFFFFFF, WHITE = 0xFFF4F6F8,
            DIM = 0xFF6A717A, DIM2 = 0xFF9AA2AB, ACCENT = 0xFF5CD6FF, HAIR = 0x18FFFFFF, SELBG = 0x12FFFFFF;

    private final Listener l;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.JAPAN);
    private final SimpleDateFormat date = new SimpleDateFormat("M月d日 EEE", Locale.JAPAN);

    private Typeface iThin, iLight, iReg, iMed;
    private final Typeface jp = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface jpL = Typeface.create("sans-serif-light", Typeface.NORMAL);

    private final RectF[] rail = new RectF[3];
    private final RectF[] pRows = new RectF[4];     // 設定パネルの行
    private final RectF action = new RectF();        // 起動カードの「開く」
    private RectF panel = new RectF();

    private final int[] toggleState = {-1, -1, -1};
    private int zone = 0;      // 0=レール 1=右パネル(設定のとき)
    private int rSel = 0;      // レールの選択
    private int pSel = 0;      // パネルの選択

    HomeView(Context c, Listener l) {
        super(c);
        this.l = l;
        try {
            iThin = inter(c, "'wght' 250"); iLight = inter(c, "'wght' 300");
            iReg = inter(c, "'wght' 400"); iMed = inter(c, "'wght' 500");
        } catch (Throwable t) { iThin = jpL; iLight = jpL; iReg = jp; iMed = jp; }
        if (iReg == null) { iThin = jpL; iLight = jpL; iReg = jp; iMed = jp; }
    }

    private static Typeface inter(Context c, String v) {
        return new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf").setFontVariationSettings(v).build();
    }
    private Typeface tf(String s, Typeface latin, Typeface jpTf) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return jpTf;
        return latin;
    }

    void setToggle(int i, int s) { if (toggleState[i] != s) { toggleState[i] = s; invalidate(); } }
    int selected() { return zone == 0 ? rSel : pSel; }

    void move(int dir) {
        int d = dir > 0 ? 1 : -1;
        if (zone == 0) rSel = (rSel + d + 3) % 3;
        else pSel = (pSel + d + 4) % 4;
        invalidate();
    }
    void activate() {
        if (zone == 0) {
            if (rSel == 2) { zone = 1; pSel = 0; invalidate(); }
            else l.onTile(rSel);
        } else panelAct(pSel);
    }
    boolean back() { if (zone == 1) { zone = 0; invalidate(); return true; } return false; }

    private void panelAct(int i) {
        if (i < 3) l.onToggle(i);
        else l.onAndroidSettings();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float rx0 = w * 0.04f, rx1 = w * 0.3f, top = h * 0.26f, ih = h * 0.19f, gap = h * 0.035f;
        for (int i = 0; i < 3; i++) {
            float y = top + i * (ih + gap);
            rail[i] = new RectF(rx0, y, rx1, y + ih);
        }
        panel = new RectF(w * 0.34f, h * 0.26f, w * 0.96f, h * 0.9f);
        float pad = panel.height() * 0.08f, rowH = (panel.height() - 2 * pad) / 4f;
        for (int i = 0; i < 4; i++) {
            float y = panel.top + pad + i * rowH;
            pRows[i] = new RectF(panel.left + pad, y, panel.right - pad, y + rowH);
        }
        float aw = w * 0.11f, ah = h * 0.12f;
        action.set(panel.left + pad, panel.bottom - pad - ah, panel.left + pad + aw, panel.bottom - pad);
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawColor(BG);
        drawStatus(cv, w, h);
        drawRail(cv, w, h);
        drawPanel(cv, w, h);
    }

    private void drawStatus(Canvas cv, int w, int h) {
        float by = h * 0.1f;
        p.setColor(DIM); p.setTypeface(iReg); p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(h * 0.038f); p.setLetterSpacing(0.34f);
        cv.drawText("DUO HOME", w * 0.04f, by, p);
        p.setLetterSpacing(0f);
        // 右: 時刻 → 日付 → 接続の点
        float rx = w * 0.96f;
        p.setColor(WHITE); p.setTypeface(iMed); p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(h * 0.07f);
        cv.drawText(clock.format(new Date()), rx, by + h * 0.004f, p);
        float tW = p.measureText(clock.format(new Date()));
        p.setColor(DIM); p.setTypeface(jp); p.setTextSize(h * 0.04f);
        String ds = date.format(new Date());
        cv.drawText(ds, rx - tW - w * 0.02f, by - h * 0.002f, p);
        float dW = p.measureText(ds);
        // 接続の点(AP / Wi-Fi / BT)
        float px = rx - tW - w * 0.02f - dW - w * 0.03f;
        for (int i = 2; i >= 0; i--) {
            boolean on = toggleState[i] == 1;
            p.setColor(on ? ACCENT : 0xFF3A4048);
            cv.drawCircle(px, by - h * 0.02f, h * 0.012f, p);
            px -= w * 0.018f;
        }
        p.setColor(HAIR);
        cv.drawRect(w * 0.04f, h * 0.17f, w * 0.96f, h * 0.17f + 1f, p);
    }

    private void drawRail(Canvas cv, int w, int h) {
        for (int i = 0; i < 3; i++) {
            RectF r = rail[i];
            boolean sel = zone == 0 && rSel == i;
            boolean cur = rSel == i;
            if (sel) { p.setColor(SELBG); cv.drawRect(r, p); }
            if (cur) { p.setColor(ACCENT); cv.drawRect(r.left, r.top, r.left + 4f, r.bottom, p); }
            float ix = r.left + r.height() * 0.42f, iy = r.centerY() - r.height() * 0.12f;
            drawGlyph(cv, i, ix, iy, r.height() * 0.2f, cur ? WHITE : DIM);
            float tx = r.left + r.height() * 0.85f;
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(cur ? WHITE : DIM);
            p.setTextSize(h * 0.07f);
            p.setTypeface(tf(RAIL[i], cur ? iMed : iLight, cur ? jp : jpL));
            cv.drawText(RAIL[i], tx, r.centerY() + h * 0.01f, p);
            p.setColor(cur ? DIM2 : 0xFF50565E);
            p.setTextSize(h * 0.034f);
            p.setTypeface(tf(RAIL_SUB[i], iReg, jp));
            cv.drawText(RAIL_SUB[i], tx, r.centerY() + h * 0.07f, p);
        }
    }

    private void drawPanel(Canvas cv, int w, int h) {
        p.setColor(PANEL); cv.drawRect(panel, p);
        p.setColor(HAIR); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f); cv.drawRect(panel, p); p.setStyle(Paint.Style.FILL);
        float px = panel.left + panel.height() * 0.1f;
        if (rSel < 2) {
            // 起動カード
            p.setColor(DIM); p.setTypeface(iReg); p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(h * 0.04f); p.setLetterSpacing(0.3f);
            cv.drawText(rSel == 0 ? "LAUNCH" : "LAUNCH", px, panel.top + panel.height() * 0.2f, p);
            p.setLetterSpacing(0f);
            p.setColor(WHITE); p.setTextSize(h * 0.16f);
            p.setTypeface(tf(RAIL[rSel], iMed, jp));
            cv.drawText(RAIL[rSel], px, panel.top + panel.height() * 0.5f, p);
            p.setColor(DIM2); p.setTextSize(h * 0.05f);
            p.setTypeface(tf(RAIL_SUB[rSel], iReg, jp));
            cv.drawText(RAIL_SUB[rSel], px, panel.top + panel.height() * 0.66f, p);
            // 「開く」チップ
            boolean asel = zone == 0; // レールにいる間はチップも候補として灯す
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(ACCENT); cv.drawRect(action, p); p.setStyle(Paint.Style.FILL);
            p.setColor(ACCENT); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(jp); p.setTextSize(h * 0.045f);
            cv.drawText("開く", action.centerX(), action.centerY() + h * 0.016f, p);
        } else {
            // 設定: 接続トグルと Android 設定
            for (int i = 0; i < 4; i++) drawPanelRow(cv, pRows[i], i, h);
        }
    }

    private void drawPanelRow(Canvas cv, RectF r, int i, int h) {
        boolean sel = zone == 1 && pSel == i;
        boolean toggle = i < 3;
        boolean on = toggle && toggleState[i] == 1;
        if (i > 0) { p.setColor(HAIR); cv.drawRect(r.left, r.top, r.right, r.top + 1f, p); }
        if (sel) { p.setColor(ACCENT); cv.drawRect(r.left, r.centerY() - r.height() * 0.28f, r.left + 4f, r.centerY() + r.height() * 0.28f, p); }
        p.setColor(sel ? WHITE : DIM2); p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(h * 0.05f);
        p.setTypeface(tf(SET_ROW[i], sel ? iMed : iReg, jp));
        cv.drawText(SET_ROW[i], r.left + r.height() * 0.3f, r.centerY() + h * 0.018f, p);
        p.setTextAlign(Paint.Align.RIGHT);
        if (toggle) {
            p.setTypeface(iMed); p.setColor(on ? ACCENT : DIM);
            cv.drawText(toggleState[i] == -1 ? "—" : on ? "ON" : "OFF", r.right - r.height() * 0.2f, r.centerY() + h * 0.018f, p);
        } else {
            p.setTypeface(iReg); p.setColor(sel ? WHITE : DIM);
            cv.drawText("›", r.right - r.height() * 0.2f, r.centerY() + h * 0.03f, p);
        }
    }

    /** 小さなグリフ(線のみ・角丸なし)。0=二分割 1=スパーク 2=スライダー。 */
    private void drawGlyph(Canvas cv, int kind, float cx, float cy, float s, int color) {
        p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f);
        if (kind == 0) {
            cv.drawRect(cx - s, cy - s * 0.8f, cx - s * 0.1f, cy + s * 0.8f, p);
            cv.drawRect(cx + s * 0.1f, cy - s * 0.8f, cx + s, cy + s * 0.8f, p);
        } else if (kind == 1) {
            for (int k = 0; k < 4; k++) {
                double a = Math.toRadians(k * 45);
                cv.drawLine(cx - (float) Math.cos(a) * s, cy - (float) Math.sin(a) * s,
                        cx + (float) Math.cos(a) * s, cy + (float) Math.sin(a) * s, p);
            }
        } else {
            for (int k = -1; k <= 1; k++) {
                float y = cy + k * s * 0.6f;
                cv.drawLine(cx - s, y, cx + s, y, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx + k * s * 0.5f, y, s * 0.18f, p);
                p.setStyle(Paint.Style.STROKE);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            for (int i = 0; i < 3; i++) if (rail[i].contains(x, y)) {
                rSel = i; zone = 0;
                if (i < 2) l.onTile(i); else { zone = 1; pSel = 0; }
                invalidate(); return true;
            }
            if (rSel == 2) { for (int i = 0; i < 4; i++) if (pRows[i].contains(x, y)) { zone = 1; pSel = i; invalidate(); panelAct(i); return true; } }
            else if (action.contains(x, y)) { l.onTile(rSel); return true; }
        }
        return true;
    }
}
