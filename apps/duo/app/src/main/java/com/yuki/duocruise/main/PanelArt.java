package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 設定画面の右側の、動く図。そのページで何をする場所かを、Duo Cruise の画面の縮図(1480×480)で見せる。
 * 文字は使わない。線と面だけ、角は丸めない。ネオンの色は設定画面の光と同じ。4 秒で一巡してくり返す。
 * ページごとの動き:
 *   メニュー・表示 … 左右の枠の入れ替えとターゲットの光 / 分割比 … 境目が動く / 曲がり角の案内 … 矢印と距離、
 *   地図(小窓) … YouTube が隅へ縮む / 曲がる方向の光 … 曲がる側の縁が灯る / 音量 … 音の波が小さくなる /
 *   到着 … YouTube が広がる / 一本道 … 道が伸びて YouTube が広がる / 運転中 … 止まると YouTube が広がる /
 *   操作・ボタン … コマンダーの押し込み回数 / ネオン … 縁の光 / システム・車・Wi-Fi・記録 … 画面と電波の印
 */
final class PanelArt extends View {
    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), a = new RectF(), b = new RectF();
    private final Path path = new Path();
    private int page;
    private float t; // 0〜1 の一巡
    private boolean night = true;
    private int fg = 0xFFFFFFFF, neon = 0xFFFFFFFF, neonColor = 0;
    private android.animation.ValueAnimator loop;

    PanelArt(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.MITER);
    }

    /** ページと配色。color はネオンの色の編集中だけ使う(その色で光らせる)。 */
    void set(int page, boolean night, int fg, int neon, int color) {
        boolean changed = page != this.page;
        this.page = page; this.night = night; this.fg = fg; this.neon = neon; this.neonColor = color;
        if (changed) { t = 0; setAlpha(0f); animate().alpha(1f).setDuration(420).start(); }
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loop = android.animation.ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(4000);
        loop.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        loop.setInterpolator(new android.view.animation.LinearInterpolator());
        loop.addUpdateListener(an -> { t = (float) an.getAnimatedValue(); if (getVisibility() == VISIBLE) invalidate(); });
        loop.start();
    }

    @Override protected void onDetachedFromWindow() { if (loop != null) loop.cancel(); super.onDetachedFromWindow(); }

    private static int a(int c, float k) { return (Math.round(Math.max(0, Math.min(1, k)) * ((c >>> 24) & 0xFF)) << 24) | (c & 0xFFFFFF); }
    private static float lerp(float x, float y, float k) { return x + (y - x) * k; }
    /** 0〜1 の区間 [s,e] での進み(緩急とバウンド付き)。 */
    private static float seg(float t, float s, float e) { return Motion.ease(Math.max(0, Math.min(1, (t - s) / (e - s)))); }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        // 縮図の画面(1480:480)を、上寄りに置く
        float sw = w, sh = sw * 480f / 1480f;
        if (sh > h * 0.62f) { sh = h * 0.62f; sw = sh * 1480f / 480f; }
        float x0 = (w - sw) / 2f, y0 = h * 0.12f;
        r.set(x0, y0, x0 + sw, y0 + sh);
        stroke(r, a(fg, 0.28f), 1.2f);
        switch (page) {
            case PanelView.SPLIT: split(r); break;
            case PanelView.ROUTE: case PanelView.R_HINT: turnHint(r); break;
            case PanelView.R_MAP: pip(r); break;
            case PanelView.R_LIGHT: turnLight(r); break;
            case PanelView.R_AUDIO: audio(r); break;
            case PanelView.R_ARRIVE: arrive(r); break;
            case PanelView.R_STRAIGHT: straight(r); break;
            case PanelView.DRIVE: drive(r); break;
            case PanelView.CONTROLS: case PanelView.KEYS: dial(r, h); break;
            case PanelView.NEON: case PanelView.NEON_EDIT: neonFrame(r); break;
            case PanelView.SYS: case PanelView.VEHICLE: case PanelView.WIFI: case PanelView.LOG: system(r); break;
            default: panes(r); break; // MAIN・DISPLAY
        }
    }

    private void stroke(RectF box, int color, float wdp) {
        line.setColor(color); line.setStrokeWidth(wdp * dp); cvRef.drawRect(box, line);
    }

    /** 描いている最中の描き先(onDraw の間だけ使う)。 */
    private Canvas cvRef;

    @Override public void draw(Canvas canvas) { cvRef = canvas; super.draw(canvas); }

    /** ネオンの縁(ぼかしを使わず、太く薄い線を重ねる)。 */
    private void glow(RectF box, int color, float k) { Theme.neonEdge(cvRef, box, dp, 0, k, color); }

    private void paneBox(RectF box, boolean tv, float k) {
        fill.setColor(a(fg, 0.05f + 0.05f * k)); cvRef.drawRect(box, fill);
        stroke(box, a(fg, 0.45f), 1f);
        icon(box, tv, a(fg, 0.55f + 0.4f * k));
    }

    /** 枠の中の印: CarPlay は車の地図の矢印、YouTube は再生の三角。 */
    private void icon(RectF box, boolean tv, int color) {
        float cx = box.centerX(), cy = box.centerY(), s = Math.min(box.width(), box.height()) * 0.16f;
        fill.setColor(color);
        path.reset();
        if (tv) { path.moveTo(cx - s * 0.6f, cy - s * 0.75f); path.lineTo(cx + s * 0.8f, cy); path.lineTo(cx - s * 0.6f, cy + s * 0.75f); }
        else { path.moveTo(cx, cy - s); path.lineTo(cx + s * 0.75f, cy + s * 0.8f); path.lineTo(cx, cy + s * 0.4f); path.lineTo(cx - s * 0.75f, cy + s * 0.8f); }
        path.close();
        cvRef.drawPath(path, fill);
    }

    private void inset(RectF box, float d) { box.inset(d * dp, d * dp); }

    /** 左右の枠: 前半でターゲットが左右に移り、後半で入れ替わる。 */
    private void panes(RectF s) {
        float sw = seg(t, 0.55f, 0.8f), tg = t < 0.3f ? 0 : 1;
        float mid = s.centerX();
        a.set(lerp(s.left, mid, sw), s.top, lerp(mid, s.right, sw), s.bottom); inset(a, 4);
        b.set(lerp(mid, s.left, sw), s.top, lerp(s.right, mid, sw), s.bottom); inset(b, 4);
        paneBox(a, false, tg == 0 ? 1 : 0);
        paneBox(b, true, tg == 1 ? 1 : 0);
        glow(tg == 0 ? a : b, neon, 0.9f);
    }

    /** 分割比: 境目が 30% と 70% のあいだを行き来する。 */
    private void split(RectF s) {
        float k = t < 0.5f ? seg(t, 0.05f, 0.45f) : 1 - seg(t, 0.55f, 0.95f);
        float bx = s.left + s.width() * lerp(0.3f, 0.7f, k);
        a.set(s.left, s.top, bx, s.bottom); inset(a, 4);
        b.set(bx, s.top, s.right, s.bottom); inset(b, 4);
        paneBox(a, false, 0.3f); paneBox(b, true, 0.3f);
        line.setColor(neon); line.setStrokeWidth(2 * dp);
        cvRef.drawLine(bx, s.top - 8 * dp, bx, s.bottom + 8 * dp, line);
        Theme.neonEdge(cvRef, new RectF(bx - 1, s.top - 8 * dp, bx + 1, s.bottom + 8 * dp), dp, 0, 0.8f, neon);
    }

    /** 曲がり角の案内: 地図の上に、右折の矢印の札。距離の棒が縮んでいく。 */
    private void turnHint(RectF s) {
        a.set(s); inset(a, 4); paneBox(a, false, 0.2f);
        b.set(s.right - s.width() * 0.3f, s.top + 10 * dp, s.right - 10 * dp, s.top + s.height() * 0.42f);
        fill.setColor(night ? 0xCC000000 : 0xE6FFFFFF); cvRef.drawRect(b, fill);
        glow(b, neon, 0.7f);
        float cx = b.left + b.height() * 0.5f, cy = b.centerY(), u = b.height() * 0.22f;
        line.setColor(fg); line.setStrokeWidth(3 * dp);
        path.reset(); path.moveTo(cx - u * 0.4f, cy + u * 1.2f); path.lineTo(cx - u * 0.4f, cy - u * 0.2f); path.lineTo(cx + u, cy - u * 0.2f);
        cvRef.drawPath(path, line);
        path.reset(); path.moveTo(cx + u * 0.5f, cy - u * 0.8f); path.lineTo(cx + u * 1.1f, cy - u * 0.2f); path.lineTo(cx + u * 0.5f, cy + u * 0.4f);
        cvRef.drawPath(path, line);
        float bar = (1 - t) * (b.right - (cx + u * 2) - 10 * dp);
        fill.setColor(neon); cvRef.drawRect(cx + u * 2, cy - 2 * dp, cx + u * 2 + bar, cy + 2 * dp, fill);
    }

    /** 小窓: CarPlay が画面いっぱい、YouTube が右下へ縮んで戻る。 */
    private void pip(RectF s) {
        float k = t < 0.6f ? seg(t, 0.08f, 0.4f) : 1 - seg(t, 0.68f, 0.95f);
        a.set(s); inset(a, 4); paneBox(a, false, 0.6f);
        float pw = s.width() / 3f, ph = pw * 9 / 16f;
        b.set(lerp(s.centerX(), s.right - pw - 8 * dp, k), lerp(s.top, s.bottom - ph - 8 * dp, k),
                lerp(s.right, s.right - 8 * dp, k), lerp(s.bottom, s.bottom - 8 * dp, k));
        inset(b, 3);
        fill.setColor(night ? 0xFF101010 : 0xFFF4F4F4); cvRef.drawRect(b, fill);
        paneBox(b, true, 1f);
        glow(b, neon, 0.5f + 0.4f * k);
    }

    /** 曲がる方向の光: 右の縁が灯って引く。 */
    private void turnLight(RectF s) {
        a.set(s); inset(a, 4); paneBox(a, false, 0.2f);
        float k = t < 0.35f ? seg(t, 0, 0.35f) : 1 - Math.max(0, Math.min(1, (t - 0.45f) / 0.5f));
        b.set(s.right - 3 * dp, s.top, s.right, s.bottom);
        Theme.neonEdge(cvRef, b, dp, 0, k, neonColor != 0 ? neonColor : 0xFFFFC24F);
    }

    /** 音量: YouTube の音の波が小さくなる。 */
    private void audio(RectF s) {
        a.set(s); inset(a, 4); paneBox(a, true, 0.4f);
        float k = t < 0.5f ? 1 - seg(t, 0.1f, 0.45f) * 0.7f : 0.3f + seg(t, 0.6f, 0.95f) * 0.7f;
        line.setColor(neon); line.setStrokeWidth(2.4f * dp);
        float cx = s.right - s.width() * 0.18f, cy = s.centerY();
        for (int i = 1; i <= 3; i++) {
            float rr = i * 10 * dp;
            line.setAlpha(Math.round(255 * Math.max(0, Math.min(1, k * 1.4f - (i - 1) * 0.35f))));
            cvRef.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -50, 100, false, line);
        }
        line.setAlpha(255);
    }

    /** 到着: YouTube が画面いっぱいに広がる。 */
    private void arrive(RectF s) {
        float k = t < 0.7f ? seg(t, 0.15f, 0.5f) : 1 - seg(t, 0.8f, 0.98f);
        float bx = lerp(s.centerX(), s.left, k);
        a.set(s.left, s.top, bx, s.bottom); inset(a, 4);
        b.set(bx, s.top, s.right, s.bottom); inset(b, 4);
        if (a.width() > 8 * dp) paneBox(a, false, 0.2f);
        paneBox(b, true, 0.6f + 0.4f * k);
        glow(b, neon, 0.4f + 0.5f * k);
    }

    /** 一本道: 道の線が伸び、YouTube が広がる。 */
    private void straight(RectF s) {
        float k = t < 0.6f ? seg(t, 0.1f, 0.45f) : 1 - seg(t, 0.7f, 0.95f);
        float bx = s.left + s.width() * lerp(0.5f, 0.3f, k);
        a.set(s.left, s.top, bx, s.bottom); inset(a, 4);
        b.set(bx, s.top, s.right, s.bottom); inset(b, 4);
        paneBox(a, false, 0.2f); paneBox(b, true, 0.3f + 0.6f * k);
        line.setColor(neon); line.setStrokeWidth(2 * dp);
        float cx = a.centerX();
        cvRef.drawLine(cx, a.bottom - 6 * dp, cx, lerp(a.bottom - 6 * dp, a.top + 6 * dp, Math.min(1, t * 1.6f)), line);
    }

    /** 運転中: 車が止まると YouTube が広がり、走り出すと戻る。下に速さの棒。 */
    private void drive(RectF s) {
        boolean stopped = t > 0.35f && t < 0.75f;
        float k = stopped ? seg(t, 0.35f, 0.55f) : (t >= 0.75f ? 1 - seg(t, 0.75f, 0.92f) : 0);
        float bx = s.left + s.width() * lerp(0.5f, 0.3f, k);
        a.set(s.left, s.top, bx, s.bottom); inset(a, 4);
        b.set(bx, s.top, s.right, s.bottom); inset(b, 4);
        paneBox(a, false, 0.2f); paneBox(b, true, 0.3f + 0.6f * k);
        float speed = stopped ? 0 : (t < 0.35f ? 1 - seg(t, 0.15f, 0.35f) : seg(t, 0.75f, 0.95f));
        float by = s.bottom + 18 * dp;
        fill.setColor(a(fg, 0.18f)); cvRef.drawRect(s.left, by, s.right, by + 4 * dp, fill);
        fill.setColor(neon); cvRef.drawRect(s.left, by, s.left + s.width() * speed, by + 4 * dp, fill);
    }

    /** コマンダー: ダイヤルを押す回数(1・2・3 回)の点が順に灯る。 */
    private void dial(RectF s, int h) {
        float cx = s.centerX(), cy = s.centerY(), rr = s.height() * 0.38f;
        line.setColor(a(fg, 0.5f)); line.setStrokeWidth(2 * dp);
        cvRef.drawCircle(cx, cy, rr, line);
        int n = 1 + (int) (t * 3) % 3;
        float press = (t * 3) % 1f;
        float inner = rr * (0.62f - (press < 0.15f ? 0.06f : 0));
        fill.setColor(a(neon, press < 0.15f ? 0.9f : 0.35f)); cvRef.drawCircle(cx, cy, inner, fill);
        for (int i = 0; i < 3; i++) {
            fill.setColor(i < n ? neon : a(fg, 0.2f));
            cvRef.drawCircle(cx - 14 * dp + i * 14 * dp, s.bottom + 18 * dp, 4 * dp, fill);
        }
    }

    /** ネオン: 画面の縁が、その色でゆっくり灯って引く。 */
    private void neonFrame(RectF s) {
        a.set(s); inset(a, 4); paneBox(a, false, 0.2f);
        float k = 0.35f + 0.65f * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t));
        Theme.neonEdge(cvRef, s, dp, 0, k, neonColor != 0 ? neonColor : neon);
    }

    /** システム: 画面と、電波の印。 */
    private void system(RectF s) {
        a.set(s); inset(a, 4); paneBox(a, false, 0.1f);
        float cx = s.centerX(), cy = s.bottom - s.height() * 0.25f;
        line.setStrokeWidth(2.4f * dp);
        for (int i = 1; i <= 3; i++) {
            float k = Math.max(0, Math.min(1, t * 3 - (i - 1) * 0.5f));
            line.setColor(a(neon, 0.25f + 0.75f * k));
            float rr = i * 12 * dp;
            cvRef.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, 225, 90, false, line);
        }
        fill.setColor(neon); cvRef.drawCircle(cx, cy, 3 * dp, fill);
    }
}
