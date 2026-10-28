package com.yuki.duocruise.main;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * はじめての案内。初めて開いたとき(と、メニュー › システム から)出す。
 *
 * 車載なので、1 ページに 1 つのことだけ。大きな見出しと、短い本文。
 * コマンダーで進む(押す・回す・右)/戻る(戻る・左)。触れても進む。最後のページで押すと閉じる。
 * 見た目は設定ビューと同じ: 黒の透過、白い文字、縁の光。ページが変わると右から(戻ると左から)滑り込む。
 */
final class GuideView extends View {
    private static final String[][] PAGES = {
        { "Duo Cruise へようこそ",
          "左に CarPlay、右に YouTube。\nひとつの画面で、二つを同時に使えます。" },
        { "iPhone をつなぐ",
          "iPhone の Bluetooth と Wi-Fi をオンにして、\n設定 › 一般 › CarPlay から「Duo Cruise」を選んでください。\n次からは、エンジンをかけるだけで自動でつながります。" },
        { "コマンダーで操作する",
          "回す・倒す\t選ぶ\n押す\t決める\n押すを 2 回\t操作する画面を切り替える\n押すを 3 回\tメニューを開く\n戻るを 2 回\t左右を入れ替える\n戻るを 3 回\t1 画面と 2 画面を切り替える" },
        { "YouTube を送る",
          "iPhone の YouTube で送信ボタンを押し、\n「Duo Cruise」を選ぶと、こちらの画面で再生されます。" },
    };

    private final float dp;
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final NeonGlow glow = new NeonGlow();
    private final RectF box = new RectF();
    private int page = 0;
    private float slide = 0;     // ページが変わった直後のずれ(px)。0 に戻りながら滑り込む
    private float shown = 1;     // 文字の不透明度
    private ValueAnimator anim;
    private Runnable onClose;

    GuideView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xF0000000);
        setClickable(true);
        setFocusable(true);
        title.setColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(34 * dp);
        body.setColor(0xD9FFFFFF);
        body.setTextSize(20 * dp);
        hint.setColor(0x8CFFFFFF);
        hint.setTextSize(15 * dp);
        hint.setTextAlign(Paint.Align.RIGHT);
    }

    /** 最初のページから出す。閉じたら onClose を呼ぶ。 */
    void open(Runnable onClose) {
        this.onClose = onClose;
        page = 0;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(260).setInterpolator(new DecelerateInterpolator(1.5f)).start();
        go(0, 1);
    }

    private void close() {
        animate().alpha(0f).setDuration(220).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
            if (onClose != null) onClose.run();
        }).start();
    }

    private void next() { if (page >= PAGES.length - 1) close(); else go(page + 1, 1); }
    private void prev() { if (page > 0) go(page - 1, -1); }

    private void go(int p, int dir) {
        page = p;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(1f, 0f);
        anim.setDuration(320);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            slide = dir * 56 * dp * f;
            shown = 1 - f;
            invalidate();
        });
        anim.start();
    }

    /** コマンダーのキー。扱ったら true。 */
    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_UP) return true;
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
    void rotate(int s) { if (s > 0) next(); else if (s < 0) prev(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) next();
        return true;
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        glow.draw(cv, box, dp, 64, 0.30f, 0xFFFFFFFF, 0);
        float left = 68 * dp + slide;
        int a = Math.round(255 * shown);
        title.setAlpha(a);
        body.setAlpha(Math.round(0xD9 * shown));
        String[] pg = PAGES[page];
        String[] lines = pg[1].split("\n");
        float lineH = body.getTextSize() * 1.6f;
        float blockH = title.getTextSize() + 28 * dp + lines.length * lineH;
        float y = (h - blockH) / 2f + title.getTextSize();
        cv.drawText(pg[0], left, y, title);
        y += 28 * dp + body.getTextSize();
        for (String l : lines) {
            int tab = l.indexOf('\t');   // 「操作\t意味」は二段組みにして揃える
            if (tab < 0) cv.drawText(l, left, y, body);
            else { cv.drawText(l.substring(0, tab), left, y, body); cv.drawText(l.substring(tab + 1), left + 190 * dp, y, body); }
            y += lineH;
        }
        // 下: ページの点と、次の操作
        float r = 4 * dp, gap = 16 * dp, by = h - 34 * dp;
        float x0 = 68 * dp;
        for (int i = 0; i < PAGES.length; i++) {
            dot.setColor(i == page ? 0xFFFFFFFF : 0x4DFFFFFF);
            cv.drawCircle(x0 + i * gap, by, i == page ? r * 1.25f : r, dot);
        }
        cv.drawText(page == PAGES.length - 1 ? "押して始める" : "押して次へ", w - 68 * dp, by + hint.getTextSize() * 0.35f, hint);
    }
}
