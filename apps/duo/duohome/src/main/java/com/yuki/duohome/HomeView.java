package com.yuki.duohome;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 箱のホーム。黒の奥行きに、実際のアプリのアイコンと、生きたネオンの光。角丸は使わない。
 *  ホームページ: 入口4つ(Duo Cruise / CarPlay / Claude / 設定)だけ。すっきり大きく。
 *  設定ページ:   接続トグル(AP / Wi-Fi / Bluetooth)と「Android の設定を開く」。「設定」タイルから入る。
 */
final class HomeView extends View {
    interface Listener {
        void onTile(int i);        // 0=DuoCruise 1=CarPlay 2=Claude
        void onToggle(int i);      // 0=AP 1=Wi-Fi 2=Bluetooth
        void onAndroidSettings();
    }

    static final String[] TILE = {"Duo Cruise", "CarPlay", "Claude", "設定"};
    static final int[] TILE_COLOR = {0xFF66E8FF, 0xFF5B9CFF, 0xFFFF9E57, 0xFF9AA7B8};
    static final String[] SET_ROW = {"アクセスポイント", "Wi-Fi", "Bluetooth", "Android の設定を開く", "戻る"};

    private final Listener l;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] tiles = new RectF[4];
    private final RectF[] rows = new RectF[5];
    private final SimpleDateFormat clock = new SimpleDateFormat("H:mm", Locale.JAPAN);

    private final Drawable[] icons = new Drawable[4];
    private final int[] toggleState = {-1, -1, -1};   // -1=不明 0=オフ 1=オン
    private int page = 0;                              // 0=ホーム 1=設定
    private int selected = 0;

    HomeView(Context c, Listener l) {
        super(c);
        this.l = l;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // 本物のにじみ(BlurMaskFilter)
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    void setIcons(Drawable[] ic) { System.arraycopy(ic, 0, icons, 0, 4); invalidate(); }
    void setToggle(int i, int state) { if (toggleState[i] != state) { toggleState[i] = state; invalidate(); } }
    int selected() { return selected; }

    private int count() { return page == 0 ? 4 : 5; }
    void move(int dir) { selected = (selected + (dir > 0 ? 1 : -1) + count()) % count(); invalidate(); }
    void activate() { dispatch(selected); }

    /** 戻る(コマンダー/キーの戻る)。設定ページならホームへ戻し、扱ったら true。 */
    boolean back() { if (page == 1) { setPage(0); return true; } return false; }

    private void setPage(int p) { page = p; selected = 0; invalidate(); }

    private void dispatch(int i) {
        if (page == 0) {
            if (i == 3) setPage(1);        // 設定 → 設定ページ
            else l.onTile(i);              // Duo Cruise / CarPlay / Claude
        } else {
            switch (i) {
                case 0: case 1: case 2: l.onToggle(i); break;   // AP / Wi-Fi / Bluetooth
                case 3: l.onAndroidSettings(); break;           // Android の設定
                case 4: setPage(0); break;                      // 戻る
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float side = w * 0.033f, gap = w * 0.018f;
        // ホーム: 4タイル(大きめ・中央寄り)
        float top = h * 0.2f, tileH = h * 0.58f;
        float tileW = (w - 2 * side - 3 * gap) / 4f;
        for (int i = 0; i < 4; i++) {
            float x = side + i * (tileW + gap);
            tiles[i] = new RectF(x, top, x + tileW, top + tileH);
        }
        // 設定: 縦に5行
        float rTop = h * 0.26f, rH = h * 0.12f, rGap = h * 0.025f;
        float rW = w - 2 * side;
        for (int i = 0; i < 5; i++) {
            float y = rTop + i * (rH + rGap);
            rows[i] = new RectF(side, y, side + rW, y + rH);
        }
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF0B0F17, 0xFF05070B, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        text.setTextSize(h * 0.044f);
        glowText(cv, page == 0 ? "DUO HOME" : "設定", w * 0.033f, h * 0.12f, 0x88AEB8C6, Paint.Align.LEFT, 6f);
        text.setTextSize(h * 0.075f);
        glowText(cv, clock.format(new Date()), w * (1f - 0.033f), h * 0.125f, 0xE6EAF2FF, Paint.Align.RIGHT, 9f);

        if (page == 0) for (int i = 0; i < 4; i++) drawTile(cv, tiles[i], i, selected == i);
        else for (int i = 0; i < 5; i++) drawRow(cv, rows[i], i, selected == i);
    }

    private void drawTile(Canvas cv, RectF r, int i, boolean sel) {
        int color = TILE_COLOR[i];
        int topA = sel ? 0x30 : 0x15;
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                (color & 0xFFFFFF) | (topA << 24), (color & 0xFFFFFF), Shader.TileMode.CLAMP));
        cv.drawRect(r, fill);
        fill.setShader(null);
        fill.setColor(0x33000000);
        cv.drawRect(r, fill);

        glowRect(cv, r, (color & 0xFFFFFF) | (sel ? 0xCC000000 : 0x77000000), sel ? 30f : 14f, sel ? 3.5f : 2f);
        glow.setMaskFilter(null);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(1f);
        glow.setColor((color & 0xFFFFFF) | 0x40000000);
        cv.drawRect(r, glow);

        float iconSz = Math.min(r.width(), r.height()) * 0.42f;
        float cx = r.centerX(), iy = r.top + r.height() * 0.36f;
        Drawable d = icons[i];
        if (d != null) {
            d.setBounds((int) (cx - iconSz / 2), (int) (iy - iconSz / 2), (int) (cx + iconSz / 2), (int) (iy + iconSz / 2));
            d.draw(cv);
        } else if (i == 1) drawWheel(cv, cx, iy, iconSz * 0.46f, color, sel);
        else drawSpark(cv, cx, iy, iconSz * 0.42f, color, sel);

        text.setTextSize(r.height() * 0.11f);
        glowText(cv, TILE[i], cx, r.bottom - r.height() * 0.1f,
                (color & 0xFFFFFF) | (sel ? 0xFF000000 : 0xDD000000), Paint.Align.CENTER, sel ? 14f : 8f);
    }

    private void drawRow(Canvas cv, RectF r, int i, boolean sel) {
        boolean isToggle = i < 3;
        boolean on = isToggle && toggleState[i] == 1;
        int base = isToggle ? (on ? 0xFF59E3B0 : 0xFF49535F) : (i == 4 ? 0xFF9AA7B8 : 0xFF7FB2FF);
        int color = sel ? brighten(base) : base;

        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                (color & 0xFFFFFF) | (sel ? 0x22000000 : 0x10000000), 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(r, fill);
        fill.setShader(null);
        glowRect(cv, r, (color & 0xFFFFFF) | ((on || sel) ? 0x99000000 : 0x44000000), sel ? 22f : (on ? 12f : 6f), sel ? 3f : 2f);

        float padL = r.height() * 0.5f;
        if (isToggle) {
            float dotR = r.height() * 0.14f, dx = r.left + padL, dy = r.centerY();
            glow.setStyle(Paint.Style.FILL);
            glow.setMaskFilter(new BlurMaskFilter(on ? 16f : 6f, BlurMaskFilter.Blur.NORMAL));
            glow.setColor((color & 0xFFFFFF) | (on ? 0xCC000000 : 0x55000000));
            cv.drawCircle(dx, dy, dotR, glow);
            glow.setMaskFilter(null);
            glow.setColor((color & 0xFFFFFF) | 0xFF000000);
            cv.drawCircle(dx, dy, dotR * (on ? 0.7f : 0.45f), glow);
            text.setTextSize(r.height() * 0.32f);
            text.setColor(on || sel ? 0xFFEAF2FF : 0xFF8A94A2);
            text.setTextAlign(Paint.Align.LEFT);
            cv.drawText(SET_ROW[i], dx + dotR + r.height() * 0.4f, r.centerY() + text.getTextSize() * 0.35f, text);
            glowText(cv, toggleState[i] == -1 ? "—" : on ? "ON" : "OFF", r.right - r.height() * 0.4f,
                    r.centerY() + text.getTextSize() * 0.35f, (color & 0xFFFFFF) | 0xFF000000, Paint.Align.RIGHT, on ? 10f : 0f);
        } else {
            text.setTextSize(r.height() * 0.32f);
            text.setTextAlign(Paint.Align.LEFT);
            glowText(cv, SET_ROW[i], r.left + padL, r.centerY() + text.getTextSize() * 0.35f,
                    (color & 0xFFFFFF) | 0xFF000000, Paint.Align.LEFT, sel ? 10f : 0f);
            // 右端にしるし(> は進む、< は戻る)
            glowText(cv, i == 4 ? "‹" : "›", r.right - r.height() * 0.45f, r.centerY() + r.height() * 0.17f,
                    (color & 0xFFFFFF) | 0xFF000000, Paint.Align.RIGHT, sel ? 10f : 0f);
        }
    }

    private static int brighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 40), g = Math.min(255, ((c >> 8) & 0xFF) + 40), b = Math.min(255, (c & 0xFF) + 40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ───────── 光の部品 ─────────
    private void glowRect(Canvas cv, RectF r, int color, float blur, float sw) {
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(sw);
        glow.setColor(color);
        glow.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        cv.drawRect(r, glow);
        glow.setMaskFilter(null);
    }

    private void glowText(Canvas cv, String s, float x, float y, int color, Paint.Align a, float blur) {
        text.setTextAlign(a);
        if (blur > 0) {
            glow.setStyle(Paint.Style.FILL);
            glow.setTextSize(text.getTextSize());
            glow.setTypeface(text.getTypeface());
            glow.setTextAlign(a);
            glow.setColor((color & 0xFFFFFF) | 0x66000000);
            glow.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            cv.drawText(s, x, y, glow);
            glow.setMaskFilter(null);
        }
        text.setColor(color);
        cv.drawText(s, x, y, text);
    }

    private void drawWheel(Canvas cv, float cx, float cy, float rad, int color, boolean sel) {
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(sel ? 4f : 3f);
        glow.setColor((color & 0xFFFFFF) | 0xFF000000);
        glow.setMaskFilter(new BlurMaskFilter(sel ? 16f : 9f, BlurMaskFilter.Blur.NORMAL));
        cv.drawCircle(cx, cy, rad, glow);
        cv.drawCircle(cx, cy, rad * 0.26f, glow);
        for (int k = 0; k < 3; k++) {
            double ang = Math.toRadians(90 + k * 120);
            cv.drawLine(cx + (float) Math.cos(ang) * rad * 0.26f, cy + (float) Math.sin(ang) * rad * 0.26f,
                    cx + (float) Math.cos(ang) * rad, cy + (float) Math.sin(ang) * rad, glow);
        }
        glow.setMaskFilter(null);
    }

    private void drawSpark(Canvas cv, float cx, float cy, float rad, int color, boolean sel) {
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(sel ? 4f : 3f);
        glow.setColor((color & 0xFFFFFF) | 0xFF000000);
        glow.setMaskFilter(new BlurMaskFilter(sel ? 16f : 9f, BlurMaskFilter.Blur.NORMAL));
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(k * 45);
            float dx = (float) Math.cos(a) * rad, dy = (float) Math.sin(a) * rad;
            cv.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, glow);
        }
        glow.setMaskFilter(null);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            RectF[] set = page == 0 ? tiles : rows;
            int n = count();
            for (int i = 0; i < n; i++) if (set[i] != null && set[i].contains(x, y)) { selected = i; invalidate(); dispatch(i); return true; }
        }
        return true;
    }
}
