package com.yuki.duohome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 箱のホーム。黒地にシャープなネオンのタイル(角丸なし)。
 * 上段に4つの入口(Duo Cruise / CarPlay / Claude / 設定)、下段に接続トグル(AP / Wi-Fi / Bluetooth)。
 */
final class HomeView extends View {
    interface Listener {
        void onTile(int i);     // 0=DuoCruise 1=CarPlay 2=Claude 3=設定
        void onToggle(int i);   // 0=AP 1=Wi-Fi 2=Bluetooth
    }

    static final String[] TILE = {"Duo Cruise", "CarPlay", "Claude", "設定"};
    static final int[] TILE_COLOR = {0xFF7FE7FF, 0xFF4F8CFF, 0xFFFFB24F, 0xFFAEB8C6};
    static final String[] TOGGLE = {"アクセスポイント", "Wi-Fi", "Bluetooth"};

    private final Listener l;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] tiles = new RectF[4];
    private final RectF[] toggles = new RectF[3];
    private final SimpleDateFormat clock = new SimpleDateFormat("H:mm", Locale.JAPAN);

    /** トグルの状態。-1=不明 0=オフ 1=オン。 */
    private final int[] toggleState = {-1, -1, -1};

    /** コマンダー用の選択位置。0〜3=タイル、4〜6=トグル。回転/DPAD で動かし、決定で実行。 */
    private int selected = 0;

    void move(int dir) { selected = (selected + (dir > 0 ? 1 : -1) + 7) % 7; invalidate(); }
    int selected() { return selected; }

    HomeView(Context c, Listener l) {
        super(c);
        this.l = l;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // ネオンのにじみ(shadowLayer)を効かせる
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    void setToggle(int i, int state) {
        if (toggleState[i] != state) { toggleState[i] = state; invalidate(); }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float side = w * 0.033f, top = h * 0.19f;
        float gap = w * 0.02f;
        float tileW = (w - 2 * side - 3 * gap) / 4f;
        float tileH = h * 0.42f;
        for (int i = 0; i < 4; i++) {
            float x = side + i * (tileW + gap);
            tiles[i] = new RectF(x, top, x + tileW, top + tileH);
        }
        float ty = top + tileH + h * 0.08f, tH = h * 0.14f;
        float tgW = (w - 2 * side - 2 * gap) / 3f;
        for (int i = 0; i < 3; i++) {
            float x = side + i * (tgW + gap);
            toggles[i] = new RectF(x, ty, x + tgW, ty + tH);
        }
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawColor(0xFF06080B);

        // 見出しと時計
        text.setColor(0x66AEB8C6);
        text.setTextSize(h * 0.045f);
        text.setTextAlign(Paint.Align.LEFT);
        cv.drawText("DUO HOME", w * 0.033f, h * 0.12f, text);
        text.setColor(0xCCEAF2FF);
        text.setTextSize(h * 0.075f);
        text.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(clock.format(new Date()), w * (1f - 0.033f), h * 0.13f, text);

        // タイル
        for (int i = 0; i < 4; i++) drawTile(cv, tiles[i], TILE[i], TILE_COLOR[i], selected == i);

        // トグル
        for (int i = 0; i < 3; i++) drawToggle(cv, toggles[i], TOGGLE[i], toggleState[i], selected == 4 + i);
    }

    private void drawTile(Canvas cv, RectF r, String label, int color, boolean sel) {
        fill.setColor(sel ? 0xFF12202B : 0xFF0B0F14);
        fill.setShadowLayer(0, 0, 0, 0);
        cv.drawRect(r, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(sel ? 4f : 2f);
        stroke.setShadowLayer(sel ? 30f : 18f, 0, 0, (color & 0x00FFFFFF) | (sel ? 0x99000000 : 0x66000000));
        cv.drawRect(r, stroke);
        text.setColor(color);
        text.setShadowLayer(sel ? 20f : 14f, 0, 0, (color & 0x00FFFFFF) | 0x55000000);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(r.height() * 0.2f);
        cv.drawText(label, r.centerX(), r.centerY() + text.getTextSize() * 0.35f, text);
        text.setShadowLayer(0, 0, 0, 0);
    }

    private void drawToggle(Canvas cv, RectF r, String label, int state, boolean sel) {
        boolean on = state == 1;
        int color = on ? 0xFF59E3B0 : 0xFF3A424E;
        if (sel) color = on ? 0xFF7CF0C6 : 0xFF8A94A2;
        fill.setColor(sel ? 0xFF12202B : 0xFF0B0F14);
        cv.drawRect(r, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(sel ? 3.5f : 2f);
        stroke.setShadowLayer(on || sel ? (sel ? 26f : 16f) : 0f, 0, 0, (color & 0x00FFFFFF) | 0x66000000);
        cv.drawRect(r, stroke);
        // ラベル
        text.setColor(on ? 0xFFEAF2FF : 0xFF8A94A2);
        text.setShadowLayer(0, 0, 0, 0);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(r.height() * 0.34f);
        cv.drawText(label, r.left + r.height() * 0.3f, r.centerY() + text.getTextSize() * 0.35f, text);
        // 状態
        text.setColor(color);
        text.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(state == -1 ? "—" : on ? "ON" : "OFF", r.right - r.height() * 0.3f, r.centerY() + text.getTextSize() * 0.35f, text);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            for (int i = 0; i < 4; i++) if (tiles[i] != null && tiles[i].contains(x, y)) { selected = i; invalidate(); l.onTile(i); return true; }
            for (int i = 0; i < 3; i++) if (toggles[i] != null && toggles[i].contains(x, y)) { selected = 4 + i; invalidate(); l.onToggle(i); return true; }
        }
        return true;
    }
}
