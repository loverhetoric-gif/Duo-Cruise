package com.yuki.duocruise.main;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * はじめての案内。初めて開いたとき(起動演出のすぐ後)と、メニュー › システム から出す。
 *
 * 起動演出と地続きにする: 地の色は演出と同じ(昼は白・夜は黒)、左上に色付きのシンボル、
 * 色は演出のロゴと同じ暖色→寒色のグラデーションだけを差し色に使う。
 * 1 ページに 1 つのこと。左に大きな見出しと短い本文、右にそのページの絵。
 * ページが変わると、今のページが左へ退き、次のページが右から入る。
 * 最後に押すと、文字が引いて、地が透けるように 2 画面が現れ、縁に光がひと巡りして落ち着く。
 */
final class GuideView extends View {
    /** ページ: 見出しと本文の文言(res/values-*\/strings.xml)。本文の「操作\t意味」は二段組み。 */
    private static final int[][] PAGES = {
        { R.string.g1_t, R.string.g1_b },
        { R.string.g2_t, R.string.g2_b },
        { R.string.g3_t, R.string.g3_r1, R.string.g3_r2, R.string.g3_r3, R.string.g3_r4, R.string.g3_r5, R.string.g3_r6 },
        { R.string.g4_t, R.string.g4_b },
    };
    /** ロゴと同じ差し色(暖色 → 寒色)。 */
    private static final int WARM_A = 0xFFFFA347, WARM_B = 0xFFFF5C3C, COOL_A = 0xFF3CA8FF, COOL_B = 0xFF2860F0;
    private static final long PAGE_MS = 420, OUT_MS = 900;

    private final float dp;
    private final boolean night;
    private final int bg, ink, sub, faint;
    private final Bitmap symbol;
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG), body = new Paint(Paint.ANTI_ALIAS_FLAG),
            hint = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final NeonGlow glow = new NeonGlow();
    private final RectF r = new RectF();

    private int page = 0, prevPage = -1, dir = 1;
    private long pageAt = 0;          // ページが変わった時刻
    private float content = 1f;       // 文字と絵の不透明度(現れる・引くとき)
    private float ground = 1f;        // 地の不透明度(最後に透けて 2 画面が現れる)
    private float bloom = 0f;         // 最後の縁の光
    private boolean closing;
    private Runnable onClose;

    GuideView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF000000 : 0xFFFFFFFF;
        ink = night ? 0xFFF4F2EE : 0xFF0A0A0A;
        sub = night ? 0xA6F4F2EE : 0x990A0A0A;
        faint = night ? 0x26FFFFFF : 0x1A000000;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        symbol = BitmapFactory.decodeResource(c.getResources(), night ? R.drawable.boot_symbol_for_black : R.drawable.boot_symbol_for_white, o);
        setClickable(true);
        setFocusable(true);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        title.setTypeface(medium);
        title.setTextSize(36 * dp);
        title.setColor(ink);
        body.setTextSize(19 * dp);
        body.setColor(sub);
        hint.setTextSize(15 * dp);
        hint.setColor(sub);
        hint.setTextAlign(Paint.Align.RIGHT);
        hint.setLetterSpacing(0.04f);
    }

    /**
     * 地だけを先に敷いておく(起動演出の後ろ)。演出が消えると、同じ色の地の上に案内が浮かぶ。
     */
    void prepare() {
        page = 0; prevPage = -1; closing = false;
        content = 0f; ground = 1f; bloom = 0f;
        setVisibility(VISIBLE);
        setAlpha(1f);
        invalidate();
    }

    /** 案内の中身を浮かび上がらせる(起動演出の後)。 */
    void reveal(Runnable onClose) {
        this.onClose = onClose;
        pageAt = SystemClock.uptimeMillis();
        tween(560, f -> content = ease(f), null);
    }

    /** メニューから出すとき: 地ごと現れる。 */
    void open(Runnable onClose) {
        prepare();
        setAlpha(0f);
        animate().alpha(1f).setDuration(300).start();
        reveal(onClose);
    }

    private void close() {
        if (closing) return;
        closing = true;
        // 文字が引き(0〜0.3)、地が透けて 2 画面が現れ(0.2〜1.0)、縁に光がひと巡りする
        tween(OUT_MS, f -> {
            content = 1 - ease(f / 0.3f);
            ground = 1 - ease((f - 0.2f) / 0.8f);
            bloom = (float) Math.sin(Math.PI * Math.min(1f, f));
        }, () -> {
            setVisibility(GONE);
            content = 1f; ground = 1f; bloom = 0f; closing = false;
            if (onClose != null) onClose.run();
        });
    }

    private void next() { if (page >= PAGES.length - 1) close(); else go(page + 1, 1); }
    private void prev() { if (page > 0) go(page - 1, -1); }

    private void go(int p, int d) {
        prevPage = page; page = p; dir = d;
        pageAt = SystemClock.uptimeMillis();
        tween(PAGE_MS, f -> {}, () -> prevPage = -1);
    }

    private interface Step { void at(float f); }

    /** ms かけて step を回す(画面の更新ごと)。 */
    private void tween(long ms, Step step, Runnable done) {
        final long start = SystemClock.uptimeMillis();
        postOnAnimation(new Runnable() {
            @Override public void run() {
                float f = Math.min(1f, (SystemClock.uptimeMillis() - start) / (float) ms);
                step.at(f);
                invalidate();
                if (f < 1f) postOnAnimation(this); else if (done != null) done.run();
            }
        });
    }

    /** コマンダーのキー。扱ったら true。 */
    boolean onKey(KeyEvent e) {
        if (closing || e.getAction() != KeyEvent.ACTION_UP) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_TAB:
                next(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP:
                prev(); return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                if (page > 0) prev(); else close();
                return true;
            default: return true;
        }
    }

    /** ダイヤル(+1 / -1)。 */
    void rotate(int s) { if (closing) return; if (s > 0) next(); else if (s < 0) prev(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!closing && e.getActionMasked() == MotionEvent.ACTION_UP) next();
        return true;
    }

    // ───────── 描画 ─────────

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        // 地(最後は透けて、下の 2 画面が現れる)
        cv.drawColor((Math.round(255 * ground) << 24) | (bg & 0xFFFFFF));
        if (bloom > 0) {
            r.set(0, 0, w, h);
            glow.draw(cv, r, dp, 72, 0.7f * bloom, 0xFFFFFFFF, 0.25f * bloom);
        }
        if (content <= 0.001f) return;
        float pad = 68 * dp;
        // 左上: 色付きのシンボル(起動演出から引き継ぐ印)
        if (symbol != null) {
            float s = 34 * dp;
            r.set(pad - 4 * dp, 26 * dp, pad - 4 * dp + s, 26 * dp + s);
            img.setAlpha(Math.round(255 * content));
            cv.drawBitmap(symbol, null, r, img);
        }
        // ページ(今のページが入り、前のページが退く)
        float f = Math.min(1f, (SystemClock.uptimeMillis() - pageAt) / (float) PAGE_MS);
        float in = ease(f);
        if (prevPage >= 0) drawPage(cv, prevPage, w, h, -dir * 40 * dp * in, (1 - ease(f * 1.6f)) * content);
        drawPage(cv, page, w, h, dir * 56 * dp * (1 - in), (prevPage >= 0 ? ease((f - 0.25f) / 0.75f) : 1f) * content);
        if (f < 1f) postInvalidateOnAnimation();
        // 下: 進み具合(細い線。今のページはロゴの差し色)と、次の操作
        float by = h - 36 * dp, seg = 28 * dp, gap = 8 * dp, th = 2 * dp;
        for (int i = 0; i < PAGES.length; i++) {
            float x = pad + i * (seg + gap);
            if (i == page) {
                line.setShader(new LinearGradient(x, 0, x + seg, 0, i < 2 ? WARM_A : COOL_A, i < 2 ? WARM_B : COOL_B, Shader.TileMode.CLAMP));
                line.setAlpha(Math.round(255 * content));
            } else {
                line.setShader(null);
                line.setColor(faint);
                line.setAlpha(Math.round(alphaOf(faint) * content));
            }
            cv.drawRect(x, by - th / 2, x + seg, by + th / 2, line);
        }
        line.setShader(null);
        hint.setAlpha(Math.round(alphaOf(sub) * content));
        cv.drawText(Tx.s(page == PAGES.length - 1 ? R.string.g_start : R.string.g_next), w - pad, by + hint.getTextSize() * 0.35f, hint);
    }

    private static int alphaOf(int c) { return (c >>> 24) & 0xFF; }

    /** 1 ページ。dx だけ横にずらし、a の濃さで描く。 */
    private void drawPage(Canvas cv, int p, int w, int h, float dx, float a) {
        if (a <= 0.001f) return;
        int[] pg = PAGES[p];
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < pg.length; i++) { if (i > 1) text.append('\n'); text.append(Tx.s(pg[i])); }
        String head = Tx.s(pg[0]);
        String[] lines = text.toString().split("\n");
        float left = 68 * dp + dx;
        float lineH = body.getTextSize() * 1.75f;
        float blockH = title.getTextSize() + 30 * dp + (lines.length - 1) * lineH + body.getTextSize();
        float y = (h - blockH) / 2f + title.getTextSize() * 0.86f;
        title.setAlpha(Math.round(255 * a));
        cv.drawText(head, left, y, title);
        y += 30 * dp + body.getTextSize();
        int baseSub = alphaOf(sub);
        float col = 0; // 二段組みの右の列の位置(左の列のいちばん長い語 ＋ 余白)
        for (String l : lines) { int t = l.indexOf('\t'); if (t > 0) col = Math.max(col, body.measureText(l.substring(0, t))); }
        col += 36 * dp;
        for (String l : lines) {
            int tab = l.indexOf('\t'); // 「操作\t意味」は二段組み。操作は濃く、意味は薄く
            if (tab < 0) { body.setColor(sub); body.setAlpha(Math.round(baseSub * a)); cv.drawText(l, left, y, body); }
            else {
                body.setColor(ink); body.setAlpha(Math.round(255 * a));
                cv.drawText(l.substring(0, tab), left, y, body);
                body.setColor(sub); body.setAlpha(Math.round(baseSub * a));
                cv.drawText(l.substring(tab + 1), left + col, y, body);
            }
            y += lineH;
        }
        body.setColor(sub);
        if (p == 0) drawPanes(cv, w, h, dx, a);
    }

    /** 1 ページ目の絵: 左右 2 つの画面。左は暖色、右は寒色(ロゴと同じ差し色)。四角、角は丸めない。 */
    private void drawPanes(Canvas cv, int w, int h, float dx, float a) {
        float pw = 150 * dp, ph = 108 * dp, gap = 10 * dp;
        float x1 = w - 68 * dp - (pw * 2 + gap) + dx, y1 = (h - ph) / 2f;
        pane(cv, x1, y1, pw, ph, WARM_A, WARM_B, a);
        pane(cv, x1 + pw + gap, y1, pw, ph, COOL_A, COOL_B, a);
    }

    private void pane(Canvas cv, float x, float y, float pw, float ph, int c1, int c2, float a) {
        fill.setShader(new LinearGradient(x, y, x + pw, y + ph, (0x59 << 24) | (c1 & 0xFFFFFF), (0x26 << 24) | (c2 & 0xFFFFFF), Shader.TileMode.CLAMP));
        fill.setAlpha(Math.round(255 * a));
        cv.drawRect(x, y, x + pw, y + ph, fill);
        fill.setShader(null);
        line.setShader(new LinearGradient(x, y, x + pw, y + ph, c1, c2, Shader.TileMode.CLAMP));
        line.setAlpha(Math.round(200 * a));
        float t = 1.5f * dp;
        cv.drawRect(x, y, x + pw, y + t, line);
        cv.drawRect(x, y + ph - t, x + pw, y + ph, line);
        cv.drawRect(x, y, x + t, y + ph, line);
        cv.drawRect(x + pw - t, y, x + pw, y + ph, line);
        line.setShader(null);
    }

    private static float ease(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }
}
