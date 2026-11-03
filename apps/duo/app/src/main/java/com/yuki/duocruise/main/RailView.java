package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * YouTube を画面中央に 16:9 で全画面表示したとき、左右に残る黒い余白へ情報を並べる(本人の指示)。
 * クルーズ・停車・駐車・案内中に出す。没入を壊さないよう控えめに。角は丸めない。
 *   左の余白: 時刻／行き先／目的地まで／到着／航続／進行度
 *   右の余白: 標識(次の曲がり角の矢印・距離・道の名前)
 * 値は推測しない。案内から取れた分だけ出し、まだ特定できていない到着(ETA)は「—」。
 * 寸法は画面の高さ基準の比率(どの車の解像度・密度でも崩れない)。
 */
final class RailView extends View {

    /** DuoActivity が作って渡す、表示する値。文字は呼び手が作る(推測を避けるため)。 */
    static final class Data {
        boolean guiding;          // 案内中か
        String dest;              // 行き先
        String remain;            // 目的地まで(例 "12.3 km")。null で出さない
        String eta = "—";         // 到着時間。まだ特定できていなければ "—"
        String cruise;            // 航続距離(案内開始からの GPS 累積)。null で出さない
        float progress = -1f;     // 進行度 0..1。<0 で出さない
        Integer turnAngle;        // 標識の矢印の角度(0=直進, -90=左, 90=右…)。null で標識なし
        String turnDist;          // 曲がり角まで(例 "300 m")
        String onto;              // 曲がった先の道
    }

    private static final int WHITE = 0xFFF2F5F8, DIM = 0xFF8A929C, ACCENT = 0xFF72B4FF, BAR = 0x33FFFFFF;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat clock = new SimpleDateFormat("H:mm", Locale.JAPAN);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Data data = new Data();
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (getVisibility() != VISIBLE) { ticking = false; return; }
            invalidate();
            postDelayed(this, 1000);
        }
    };

    RailView(Context c) {
        super(c);
        arrow.setStyle(Paint.Style.FILL);
    }

    void set(Data d) {
        this.data = d == null ? new Data() : d;
        invalidate();
    }

    @Override public void setVisibility(int v) {
        super.setVisibility(v);
        if (v == VISIBLE && !ticking) { ticking = true; post(tick); } // 時計を毎秒進める(案内が止まっていても)
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float u = h / 100f; // 高さ基準の単位(1u = 画面高の 1%)
        // 中央の 16:9 の幅と、左右の余白
        int vw = Math.min(w, Math.round(h * 16f / 9f));
        int left = (w - vw) / 2;       // 左余白の幅
        int right = w - (left + vw);   // 右余白の幅
        // 背後で生かしている CarPlay を隠す: 中央(YouTube)以外の左右の黒余白を不透明の黒で塗る。
        p.setColor(0xFF000000);
        if (left > 0) cv.drawRect(0, 0, left, h, p);
        if (right > 0) cv.drawRect(w - right, 0, w, h, p);
        if (left >= 18 * u) drawLeft(cv, 0, left, h, u);
        if (right >= 18 * u && data.turnAngle != null) drawRight(cv, w - right, right, h, u);
    }

    /** 左の余白: 時刻・行き先・目的地まで・到着・航続・進行度。 */
    private void drawLeft(Canvas cv, int x0, int ww, int h, float u) {
        float pad = Math.min(ww * 0.12f, 7 * u);
        float x = x0 + pad, maxW = ww - pad * 2;
        p.setTextAlign(Paint.Align.LEFT);

        // 時刻(大きく)
        p.setColor(WHITE); p.setTypeface(light); p.setTextSize(13 * u);
        float y = 20 * u;
        cv.drawText(clock.format(new Date()), x, y, p);
        y += 10 * u;

        if (data.guiding) {
            y = item(cv, x, y, maxW, u, "行き先", data.dest);
            y = item(cv, x, y, maxW, u, "目的地まで", data.remain);
            y = item(cv, x, y, maxW, u, "到着", data.eta);
            y = item(cv, x, y, maxW, u, "航続", data.cruise);
            if (data.progress >= 0f) {
                float f = Math.max(0f, Math.min(1f, data.progress));
                p.setColor(DIM); p.setTypeface(reg); p.setTextSize(2.8f * u);
                cv.drawText("進行度", x, y, p);
                p.setColor(WHITE); p.setTypeface(med); p.setTextSize(2.8f * u); p.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(Math.round(f * 100) + "%", x + maxW, y, p);
                p.setTextAlign(Paint.Align.LEFT);
                y += 2.4f * u;
                float bh = 0.9f * u;
                p.setColor(BAR); cv.drawRect(x, y, x + maxW, y + bh, p);
                p.setColor(ACCENT); cv.drawRect(x, y, x + maxW * f, y + bh, p);
            }
        }
    }

    /** 1項目(小さなラベル + 値)。値が null のときは出さず、縦位置も進めない。 */
    private float item(Canvas cv, float x, float y, float maxW, float u, String label, String value) {
        if (value == null || value.isEmpty()) return y;
        p.setColor(DIM); p.setTypeface(reg); p.setTextAlign(Paint.Align.LEFT); p.setTextSize(2.8f * u);
        cv.drawText(label, x, y, p);
        y += 4.6f * u;
        p.setColor(WHITE); p.setTypeface(med); p.setTextSize(4.6f * u);
        cv.drawText(ellipsize(value, maxW, p), x, y, p);
        return y + 6.2f * u;
    }

    /** 右の余白: 標識(矢印 + 曲がり角まで + 先の道)。 */
    private void drawRight(Canvas cv, int x0, int ww, int h, float u) {
        float cx = x0 + ww / 2f, cy = h * 0.40f, r = Math.min(ww * 0.30f, 11 * u);
        cv.save();
        cv.rotate(data.turnAngle, cx, cy); // 上向きの矢印を角度だけ回す
        Path a = new Path();
        a.moveTo(cx, cy - r);
        a.lineTo(cx - r * 0.62f, cy + r * 0.1f);
        a.lineTo(cx - r * 0.24f, cy + r * 0.1f);
        a.lineTo(cx - r * 0.24f, cy + r * 0.8f);
        a.lineTo(cx + r * 0.24f, cy + r * 0.8f);
        a.lineTo(cx + r * 0.24f, cy + r * 0.1f);
        a.lineTo(cx + r * 0.62f, cy + r * 0.1f);
        a.close();
        arrow.setColor(ACCENT);
        cv.drawPath(a, arrow);
        cv.restore();

        float y = cy + r + 8 * u;
        p.setTextAlign(Paint.Align.CENTER);
        if (data.turnDist != null && !data.turnDist.isEmpty()) {
            p.setColor(WHITE); p.setTypeface(med); p.setTextSize(5 * u);
            cv.drawText(data.turnDist, cx, y, p);
            y += 5.4f * u;
        }
        if (data.onto != null && !data.onto.isEmpty()) {
            p.setColor(DIM); p.setTypeface(reg); p.setTextSize(2.8f * u);
            cv.drawText(ellipsize(data.onto, ww * 0.9f, p), cx, y, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static String ellipsize(String s, float max, Paint paint) {
        if (paint.measureText(s) <= max) return s;
        while (s.length() > 1 && paint.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
