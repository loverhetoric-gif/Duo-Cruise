package com.yuki.duohome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 箱のホーム(ランチャー)。実アプリのアイコンを均一サイズで並べる。枠は無彩色で引き、色はアイコンに任せる。
 * 多様なアプリ(カーアプリ＋自作＋設定)が並んでも、黒地とグリッドの規律で束ねる。差し色は時間帯で変わる一点。
 * コマンダー(回転/決定)・Duo Slate・タップでフォーカスを動かして起動。
 */
final class HomeView extends View {
    interface Listener { void onSelect(int i); }

    private static final int WHITE = 0xFFF4F6F8, DIM = 0xFF767D86, DIM2 = 0xFF9AA2AB, HAIR = 0x16FFFFFF, SELBG = 0x14FFFFFF;

    private final Listener l;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.JAPAN);
    private final SimpleDateFormat date = new SimpleDateFormat("M月d日 EEE", Locale.JAPAN);

    private Typeface iReg, iMed, iLight;
    private final Typeface jp = Typeface.create("sans-serif", Typeface.NORMAL);

    private String[] labels = new String[0];
    private Drawable[] icons = new Drawable[0];
    private RectF[] tiles = new RectF[0];
    private final int[] toggleState = {-1, -1, -1};   // AP / Wi-Fi / BT(上部の点)
    private int sel = 0;

    HomeView(Context c, Listener l) {
        super(c);
        this.l = l;
        try {
            iLight = inter(c, "'wght' 300"); iReg = inter(c, "'wght' 400"); iMed = inter(c, "'wght' 500");
        } catch (Throwable t) { iLight = jp; iReg = jp; iMed = jp; }
        if (iReg == null) { iLight = jp; iReg = jp; iMed = jp; }
    }
    private static Typeface inter(Context c, String v) {
        return new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf").setFontVariationSettings(v).build();
    }
    private Typeface tf(String s, Typeface latin) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return jp;
        return latin;
    }

    void setItems(String[] labels, Drawable[] icons) {
        this.labels = labels; this.icons = icons;
        if (sel >= labels.length) sel = 0;
        layoutTiles(getWidth(), getHeight());
        invalidate();
    }
    void setToggle(int i, int s) { if (toggleState[i] != s) { toggleState[i] = s; invalidate(); } }
    int selected() { return sel; }
    void move(int dir) { int n = labels.length; if (n == 0) return; sel = (sel + (dir > 0 ? 1 : -1) + n) % n; invalidate(); }
    void activate() { if (sel >= 0 && sel < labels.length) l.onSelect(sel); }
    boolean back() { return false; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layoutTiles(w, h); }

    private void layoutTiles(int w, int h) {
        int n = labels.length;
        if (n == 0 || w == 0) { tiles = new RectF[0]; return; }
        float margin = w * 0.04f, gapR = 0.16f;
        float avail = w - 2 * margin;
        float tileW = Math.min(w * 0.135f, avail / (n + (n - 1) * gapR));
        float gap = tileW * gapR;
        float total = n * tileW + (n - 1) * gap;
        float x0 = (w - total) / 2f, cy = h * 0.58f, tileH = h * 0.56f;
        tiles = new RectF[n];
        for (int i = 0; i < n; i++) {
            float x = x0 + i * (tileW + gap);
            tiles[i] = new RectF(x, cy - tileH / 2, x + tileW, cy + tileH / 2);
        }
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        int[] pal = palette();          // {bg, accent}
        cv.drawColor(pal[0]);
        drawStatus(cv, w, h, pal[1]);
        for (int i = 0; i < tiles.length; i++) drawTile(cv, i, pal[1], h);
    }

    /** 朝・昼・夕・夜で地の色と差し色を変える。 */
    private int[] palette() {
        int heure = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (heure >= 5 && heure < 10) return new int[]{0xFF0B0E13, 0xFF72B4FF};   // 朝(涼しい藍)
        if (heure >= 10 && heure < 16) return new int[]{0xFF0A0C10, 0xFF5CD6FF};  // 昼(中立)
        if (heure >= 16 && heure < 19) return new int[]{0xFF120F0B, 0xFFFFB25C};  // 夕(琥珀)
        return new int[]{0xFF07090F, 0xFF8C94FF};                                  // 夜(深い藍)
    }

    private void drawStatus(Canvas cv, int w, int h, int accent) {
        float by = h * 0.1f;
        p.setColor(DIM); p.setTypeface(iReg); p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(h * 0.038f); p.setLetterSpacing(0.34f);
        cv.drawText("DUO HOME", w * 0.04f, by, p);
        p.setLetterSpacing(0f);
        float rx = w * 0.96f;
        p.setColor(WHITE); p.setTypeface(iMed); p.setTextAlign(Paint.Align.RIGHT); p.setTextSize(h * 0.07f);
        String t = clock.format(new Date());
        cv.drawText(t, rx, by + h * 0.004f, p);
        float tW = p.measureText(t);
        p.setColor(DIM); p.setTypeface(jp); p.setTextSize(h * 0.04f);
        String ds = date.format(new Date());
        cv.drawText(ds, rx - tW - w * 0.02f, by - h * 0.002f, p);
        float dW = p.measureText(ds);
        float px = rx - tW - w * 0.02f - dW - w * 0.03f;
        for (int i = 2; i >= 0; i--) {
            p.setColor(toggleState[i] == 1 ? accent : 0xFF3A4048);
            cv.drawCircle(px, by - h * 0.02f, h * 0.012f, p);
            px -= w * 0.018f;
        }
        p.setColor(HAIR);
        cv.drawRect(w * 0.04f, h * 0.17f, w * 0.96f, h * 0.17f + 1f, p);
    }

    private void drawTile(Canvas cv, int i, int accent, int h) {
        RectF r = tiles[i];
        boolean f = sel == i;
        float iconSz = r.width() * 0.62f;
        float icx = r.centerX(), icy = r.top + r.height() * 0.34f;
        if (f) {
            p.setColor(SELBG);
            cv.drawRect(r.left, r.top, r.right, r.bottom, p);
        }
        Drawable d = i < icons.length ? icons[i] : null;
        if (d != null) {
            d.setBounds((int) (icx - iconSz / 2), (int) (icy - iconSz / 2), (int) (icx + iconSz / 2), (int) (icy + iconSz / 2));
            d.draw(cv);
        }
        // ラベル
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(f ? WHITE : DIM2);
        p.setTextSize(h * 0.044f);
        String s = i < labels.length ? labels[i] : "";
        p.setTypeface(tf(s, f ? iMed : iReg));
        s = ellipsize(s, r.width() * 0.98f);
        cv.drawText(s, icx, r.bottom - r.height() * 0.06f, p);
        // フォーカス: 下に差し色のバー
        if (f) {
            p.setColor(accent);
            float bw = r.width() * 0.4f;
            cv.drawRect(icx - bw / 2, r.bottom + h * 0.01f, icx + bw / 2, r.bottom + h * 0.01f + 3f, p);
        }
    }

    private String ellipsize(String s, float max) {
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            for (int i = 0; i < tiles.length; i++) if (tiles[i].contains(x, y)) { sel = i; invalidate(); l.onSelect(i); return true; }
        }
        return true;
    }
}
