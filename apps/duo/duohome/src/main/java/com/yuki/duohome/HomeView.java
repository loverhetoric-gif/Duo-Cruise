package com.yuki.duohome;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 箱のホーム。起動演出の延長 ―― 中心に呼吸する生きた光(オーブ)、行き先は周囲に最小限のネオンの印。
 *  ホーム: Duo Cruise / Claude / 設定(オーブの下に静かな3つの印)。
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
    private static final int CYAN = 0x66E8FF, VIOLET = 0x8E7BFF;

    private final Listener l;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] marks = new RectF[3];     // ホームの3つの印(当たり判定)
    private final RectF[] rows = new RectF[5];       // 設定の行
    private final SimpleDateFormat clock = new SimpleDateFormat("H:mm", Locale.JAPAN);

    private final int[] toggleState = {-1, -1, -1};
    private int page = 0;
    private int selected = 0;
    private final long t0 = System.nanoTime();
    private float cx, cy, orbR;

    HomeView(Context c, Listener l) {
        super(c);
        this.l = l;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    void setToggle(int i, int state) { if (toggleState[i] != state) { toggleState[i] = state; invalidate(); } }
    int selected() { return selected; }
    private int count() { return page == 0 ? 3 : 5; }
    void move(int dir) { selected = (selected + (dir > 0 ? 1 : -1) + count()) % count(); invalidate(); }
    void activate() { dispatch(selected); }
    boolean back() { if (page == 1) { setPage(0); return true; } return false; }
    private void setPage(int p) { page = p; selected = 0; invalidate(); }

    private void dispatch(int i) {
        if (page == 0) {
            if (i == 2) setPage(1);       // 設定
            else l.onTile(i);             // 0=Duo Cruise 1=Claude
        } else {
            switch (i) {
                case 0: case 1: case 2: l.onToggle(i); break;
                case 3: l.onAndroidSettings(); break;
                case 4: setPage(0); break;
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w * 0.5f;
        cy = h * 0.4f;
        orbR = h * 0.2f;
        float my = cy + orbR + h * 0.24f, mw = w * 0.2f, mh = h * 0.16f;
        float[] mx = {w * 0.3f, w * 0.5f, w * 0.7f};
        for (int i = 0; i < 3; i++) marks[i] = new RectF(mx[i] - mw / 2, my - mh / 2, mx[i] + mw / 2, my + mh / 2);

        float rTop = h * 0.26f, rH = h * 0.12f, rGap = h * 0.025f, side = w * 0.033f, rW = w - 2 * side;
        for (int i = 0; i < 5; i++) {
            float y = rTop + i * (rH + rGap);
            rows[i] = new RectF(side, y, side + rW, y + rH);
        }
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        // 奥行きのある暗がり(中心をわずかに持ち上げる)
        fill.setShader(new RadialGradient(cx, cy, Math.max(w, h) * 0.7f,
                new int[]{0xFF0C1016, 0xFF06080C, 0xFF040509}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        if (page == 0) drawHome(cv, w, h);
        else drawSettings(cv, w, h);
    }

    private void drawHome(Canvas cv, int w, int h) {
        float ph = (System.nanoTime() - t0) / 1e9f;
        float breathe = 0.5f + 0.5f * (float) Math.sin(ph * (2 * Math.PI / 4.2)); // 4.2秒で1呼吸
        float hue = 0.5f + 0.5f * (float) Math.sin(ph * (2 * Math.PI / 13));        // ゆっくり色が流れる
        int color = mix(CYAN, VIOLET, hue);
        drawOrb(cv, cx, cy, orbR * (0.94f + 0.06f * breathe), color, breathe);

        // 時計(オーブの上・細く大きく、淡く発光)
        text.setTextSize(h * 0.11f);
        glowText(cv, clock.format(new Date()), cx, cy - orbR - h * 0.05f, 0xCCEAF2FF, Paint.Align.CENTER, 10f);

        // 行き先(オーブの下・静かな印。選択だけ灯る)
        for (int i = 0; i < 3; i++) {
            boolean sel = selected == i;
            float mcx = marks[i].centerX(), mcy = marks[i].centerY();
            int c = i == 2 ? 0xFFAEB8C6 : (color | 0xFF000000);
            // 小さな光点
            float dotY = mcy - h * 0.055f;
            glow.setStyle(Paint.Style.FILL);
            glow.setMaskFilter(new BlurMaskFilter(sel ? 14f : 5f, BlurMaskFilter.Blur.NORMAL));
            glow.setColor((c & 0xFFFFFF) | (sel ? 0xCC000000 : 0x44000000));
            cv.drawCircle(mcx, dotY, h * (sel ? 0.012f : 0.008f), glow);
            glow.setMaskFilter(null);
            glow.setColor((c & 0xFFFFFF) | 0xFF000000);
            cv.drawCircle(mcx, dotY, h * (sel ? 0.009f : 0.006f), glow);
            // ラベル
            text.setTextSize(h * 0.062f);
            glowText(cv, HOME[i], mcx, mcy + h * 0.025f,
                    (c & 0xFFFFFF) | (sel ? 0xFF000000 : 0x77000000), Paint.Align.CENTER, sel ? 14f : 0f);
        }

        if (page == 0) postInvalidateOnAnimation(); // 生きた光を動かし続ける
    }

    /** 呼吸する生きた光。白い芯 → 色 → 闇へ、層を重ねてにじませる。 */
    private void drawOrb(Canvas cv, float cx, float cy, float r, int color, float breathe) {
        // 外側の大きなハロー
        glow.setStyle(Paint.Style.FILL);
        glow.setMaskFilter(new BlurMaskFilter(r * 0.9f, BlurMaskFilter.Blur.NORMAL));
        glow.setColor((color & 0xFFFFFF) | (0x22000000 + (int) (0x1A * breathe) * 0x1000000));
        cv.drawCircle(cx, cy, r * 1.05f, glow);
        // 中間のにじみ
        glow.setMaskFilter(new BlurMaskFilter(r * 0.35f, BlurMaskFilter.Blur.NORMAL));
        glow.setColor((color & 0xFFFFFF) | 0x55000000);
        cv.drawCircle(cx, cy, r * 0.8f, glow);
        glow.setMaskFilter(null);
        // 芯(放射グラデ: 白っぽい中心 → 色 → 透明)
        fill.setShader(new RadialGradient(cx, cy, r,
                new int[]{0xF2FFFFFF, (color & 0xFFFFFF) | 0xDD000000, (color & 0xFFFFFF) | 0x33000000, 0x00000000},
                new float[]{0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, fill);
        fill.setShader(null);
    }

    private void drawSettings(Canvas cv, int w, int h) {
        text.setTextSize(h * 0.044f);
        glowText(cv, "設定", w * 0.033f, h * 0.12f, 0x99AEB8C6, Paint.Align.LEFT, 6f);
        text.setTextSize(h * 0.075f);
        glowText(cv, clock.format(new Date()), w * (1f - 0.033f), h * 0.125f, 0xE6EAF2FF, Paint.Align.RIGHT, 9f);
        for (int i = 0; i < 5; i++) drawRow(cv, rows[i], i, selected == i);
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
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(sel ? 3f : 2f);
        glow.setColor((color & 0xFFFFFF) | ((on || sel) ? 0x99000000 : 0x44000000));
        glow.setMaskFilter(new BlurMaskFilter(sel ? 22f : (on ? 12f : 6f), BlurMaskFilter.Blur.NORMAL));
        cv.drawRect(r, glow);
        glow.setMaskFilter(null);

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
            glowText(cv, SET_ROW[i], r.left + padL, r.centerY() + text.getTextSize() * 0.35f,
                    (color & 0xFFFFFF) | 0xFF000000, Paint.Align.LEFT, sel ? 10f : 0f);
            glowText(cv, i == 4 ? "‹" : "›", r.right - r.height() * 0.45f, r.centerY() + r.height() * 0.17f,
                    (color & 0xFFFFFF) | 0xFF000000, Paint.Align.RIGHT, sel ? 10f : 0f);
        }
    }

    // ───────── 部品 ─────────
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

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t), g = Math.round(ag + (bg - ag) * t), bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int brighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 40), g = Math.min(255, ((c >> 8) & 0xFF) + 40), b = Math.min(255, (c & 0xFF) + 40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
