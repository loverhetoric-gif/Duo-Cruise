package com.yuki.duocruise.main;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 起動演出(仕様と素材はリポジトリの assets/brand/。README に時間・配置・昼夜の決まりがある)。
 *
 * ・昼は白地、夜は黒地。どちらにするかは OS のダーク表示に従う
 * ・上にシンボル、下にロゴ。横も縦も中央
 * ・一色版と色付き版を同じ位置・同じ大きさで重ね、一色版を上に置く
 *   色付き版: 0.5〜2.0 秒で透明→不透明 / 一色版: 1.4〜2.9 秒で不透明→透明(どちらも ease-in-out)
 *   同時に切り替えると途中で濁るので、必ずこの時間差にする
 * ・起動音「ブルーム」を同時に鳴らす(音の中で 0.5 秒から鳴り始める)。設定で切れる
 * ・「アニメーションを減らす」がオンなら動かさず、色付き版をそのまま出す(音も鳴らさない)
 * 演出の後は、面ごと 0.4 秒で消えて、そのまま 2 画面になる。
 */
final class SplashView extends View {
    static final long ANIM_MS = 2900, FADE_MS = 400, STILL_MS = 1200;

    private final boolean night, still;
    private final Bitmap monoSym, colSym, monoLogo, colLogo;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF symBox = new RectF(), logoBox = new RectF();
    private float t = 0; // 経過(ms)
    private MediaPlayer sound;

    SplashView(Context c) {
        super(c);
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        float scale = 1f;
        try { scale = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f); }
        catch (Exception ignored) { }
        still = scale == 0f;
        setBackgroundColor(night ? Color.BLACK : Color.WHITE);
        setClickable(true); // 出ている間は下へ触らせない
        monoSym = bmp(c, night ? R.drawable.boot_symbol_mono_white : R.drawable.boot_symbol_mono_black);
        colSym = bmp(c, night ? R.drawable.boot_symbol_for_black : R.drawable.boot_symbol_for_white);
        monoLogo = bmp(c, night ? R.drawable.boot_logo_for_black : R.drawable.boot_logo_for_white);
        colLogo = bmp(c, night ? R.drawable.boot_logo_gradient_glow_for_black : R.drawable.boot_logo_gradient_for_white);
    }

    private static Bitmap bmp(Context c, int id) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        return BitmapFactory.decodeResource(c.getResources(), id, o);
    }

    /**
     * 出す。消え終わったら done を呼ぶ。withSound=false なら音は鳴らさない。
     * 時間は ValueAnimator に任せず自分で測る(「アニメーションを減らす」で ValueAnimator が 0 秒になっても、
     * 色付き版を見せる時間を保つため)。
     */
    void play(boolean withSound, Runnable done) {
        if (withSound && !still) startSound();
        final long total = (still ? STILL_MS : ANIM_MS) + FADE_MS;
        final long start = android.os.SystemClock.uptimeMillis();
        postOnAnimation(new Runnable() {
            @Override public void run() {
                long e = android.os.SystemClock.uptimeMillis() - start;
                t = still ? ANIM_MS : Math.min(ANIM_MS, e);
                long fadeFrom = total - FADE_MS;
                setAlpha(e <= fadeFrom ? 1f : 1f - ease((e - fadeFrom) / (float) FADE_MS));
                invalidate();
                if (e < total) postOnAnimation(this);
                else { releaseSound(); done.run(); }
            }
        });
    }

    private void startSound() {
        try {
            sound = MediaPlayer.create(getContext(), night ? R.raw.boot_bloom_night : R.raw.boot_bloom_day,
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0);
            if (sound != null) sound.start();
        } catch (Exception e) { DuoLog.error("起動音を鳴らせません: " + DuoLog.why(e)); }
    }

    private void releaseSound() {
        MediaPlayer s = sound;
        sound = null;
        if (s == null) return;
        // 余韻(約 4 秒)を切らないよう、鳴り終わってから手放す
        if (s.isPlaying()) s.setOnCompletionListener(MediaPlayer::release); else s.release();
    }

    /** ease-in-out(三次の S 字。CSS の ease-in-out に近い)。 */
    private static float ease(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || monoSym == null || monoLogo == null) return;
        // 大きさ: シンボルは画面の高さの 36%、ロゴは高さの 92% の幅、間は 6.5%(試作と同じ)
        float symS = h * 0.36f, logoW = h * 0.92f;
        float logoH = logoW * monoLogo.getHeight() / monoLogo.getWidth();
        float gap = h * 0.065f;
        float top = (h - (symS + gap + logoH)) / 2f;
        symBox.set((w - symS) / 2f, top, (w + symS) / 2f, top + symS);
        logoBox.set((w - logoW) / 2f, top + symS + gap, (w + logoW) / 2f, top + symS + gap + logoH);
        float col = ease((t - 500) / 1500f);       // 0.5〜2.0 秒
        float mono = 1 - ease((t - 1400) / 1500f); // 1.4〜2.9 秒
        draw(cv, colSym, symBox, col);
        draw(cv, colLogo, logoBox, col);
        draw(cv, monoSym, symBox, mono);           // 一色版を上に
        draw(cv, monoLogo, logoBox, mono);
    }

    private void draw(Canvas cv, Bitmap b, RectF box, float a) {
        if (b == null || a <= 0.001f) return;
        p.setAlpha(Math.round(255 * Math.min(1f, a)));
        cv.drawBitmap(b, null, box, p);
    }
}
