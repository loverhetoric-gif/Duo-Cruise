package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 操作メニュー。2画面の上に重ねて出す。コマンダー(ダイヤル)だけで全部操作できる。
 *
 * 考え方(Apple 流): 余計なものは出さない。非選択の行は文字だけ、強調は選んでいる行ひとつに集める。
 * 強調は、このアプリ共通の白いネオンの発光(Theme.neonEdge)で縁取る。メニューを開いている間は枠も発光させる。
 * 背景は黒の透過。後ろの映像をうっすら通す。全項目が必ず画面に収まるよう高さを割り付ける(切れない)。
 *
 * 操作: ダイヤル/上下=行移動、左右=値の変更(分割比はスライダー)、押す=決定、戻る=前へ。
 */
final class PanelView extends ViewGroup {

    interface Host {
        void switchSide();
        void swap();
        /** はじめての案内を出す。 */
        void showGuide();
        boolean bootSound(); void toggleBootSound();
        void closePanel();
        void toggleCar();
        boolean carOn();
        void goHome();
        String otherHomeName();
        void openSettings();
        void chooseHome();
        boolean isDefaultHome();
        void openAccessibility();
        boolean accessOn();
        boolean panelHalf();
        void togglePanelHalf();
        // 左右の比率・ナビ連動
        void cycleSplitYt(); String splitYtText();
        void cycleNavYt(); String navYtText();
        int splitYtPct(); void adjustSplitYt(int dir);
        int navYtPct(); void adjustNavYt(int dir);
        void toggleAutoSplit(); boolean autoSplitOn();
        void toggleAutoSwap(); boolean autoSwapOn();
        void cycleNavHold(); String navHoldText();
        // ネオン(メインビューのイベント発光)の色(RGB)と強さを、項目ごとに設定する。
        int neonColor(int which); void setNeonColor(int which, int color);
        int neonLevel(int which); void setNeonLevel(int which, int lv);
        String neonColorText(int which);
        String layoutText();
        String sideText();
        KeyMap keyMap();
        void startLearn(KeyMap.Action a);
        void cancelLearn();
        KeyMap.Action learning();
        void resetKeys();
    }

    static final int MAIN = 0, KEYS = 1, LOG = 2, SYS = 3, SPLIT = 4, NEON = 5, NEON_EDIT = 6, DISPLAY = 7, CONTROLS = 8;
    private static final String GO = "›"; // 下の階層へ進む導線

    private interface Text { String get(); }
    /** 左右(調整モードのダイヤル)で変えるスライダー。value は 0〜max。色のヒントと表示文字も持つ。 */
    private interface Slider { int value(); int max(); void nudge(int dir); String text(); int tint(); }

    private static final class Item {
        final Text label, value;
        final Runnable run;      // 押したとき
        final boolean adj;       // 左右でその場で変えられる値か
        final Slider slider;     // スライダーならその中身(なければ null)
        Item(Text label, Text value, Runnable run) { this(label, value, run, false, null); }
        Item(Text label, Text value, Runnable run, boolean adj) { this(label, value, run, adj, null); }
        Item(Text label, Runnable run, Slider slider) { this(label, () -> "", run, true, slider); }
        private Item(Text label, Text value, Runnable run, boolean adj, Slider slider) {
            this.label = label; this.value = value; this.run = run; this.adj = adj; this.slider = slider;
        }
    }

    // 寸法(dp)。行は画面高に収まる範囲でできるだけ大きく。
    private static final float ROW_MAX = 72, ROW_GAP = 6, HEADER = 56, PAD = 18, LIST_MAX = 1120;
    private static final int VALUE = 0xB3FFFFFF;     // 値の文字(白・70%)。青は使わない
    private static final int LABEL_DIM = 0x8CFFFFFF;  // 非選択のラベル(白・55%)
    /** 項目の列の幅と、スライダーの幅(dp)。1480×480 で、項目と詳細が画面の左 2/3 にまとまる。 */
    private static final int LABEL_COL = 480, SLIDER_W = 620;

    private final Host host;
    private final float dp;
    private final TextView title, logText;
    private final ListPane list;
    private final RectF frameBox = new RectF();
    private final NeonGlow frameGlow = new NeonGlow();
    private final GlowLayer glowWide, glowMid;
    private float glowStrength = 0;
    private android.animation.ValueAnimator shimmer;
    float phase = 0; // 発光の揺らぎ(枠・選択行で共有)
    private final List<Item> items = new ArrayList<>();
    private int page = MAIN, focus = 0, logPage = 0, logLines = 8;
    /** スライダーの調整モード中の行(押して入る)。-1 なら通常(回す=選択移動)。 */
    private int editing = -1;
    /** ネオンの色編集ページで、どのイベントを編集中か(0=ターゲット/1=完了/2=不良)。 */
    private int neonWhich = 0;
    private final RectF swatch = new RectF();

    PanelView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xE0000000); // 黒の透過(視認性のため濃いめ。後ろの映像はうっすら)
        setClickable(true);             // 下の2画面へタッチを通さない
        setWillNotDraw(false);
        title = text(26, Color.WHITE, true);
        logText = text(14, Theme.TEXT, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(px(14), px(10), px(14), px(10));
        list = new ListPane(c);
        // 縁の光(Duo Pad の band と同じ三層)。ぼかす二層は View ごと GPU でぼかす。細い線は dispatchDraw で最後に引く
        glowWide = new GlowLayer(c, 46, 48, 1, +1);  // 広い裾。Duo Pad の blur 28pt は Android の半径では 48(σ=0.577r+0.5)
        glowMid = new GlowLayer(c, 10, 11, 1, -1);   // 中のにじみ。blur 7pt → 11。逆方向にゆっくり
        addView(glowWide);
        addView(glowMid);
        addView(title);
        addView(logText);
        addView(list);
        show(MAIN);
    }

    int page() { return page; }

    private int px(float v) { return Math.round(v * dp); }

    /** 画面の文言(端末の言語に合わせる)。 */
    private String T(int id, Object... args) { return Tx.s(id, args); }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ───────── メニュー枠の発光 ─────────

    @Override protected void onDraw(Canvas cv) {
        // メニュー表示中は、ディスプレイの縁(画面端)から内側へ向けて間接照明で発光させる(枠線は出さない)。
        frameBox.set(0, 0, getWidth(), getHeight());
        // 開いた瞬間に光がふわっと膨らみ(強く・深く)、すぐ落ち着く
        float burst = intro < 0.35f ? intro / 0.35f * 1.35f : 1.35f - 0.35f * (intro - 0.35f) / 0.65f;
        glowStrength = 0.30f * burst;                       // 普段は控えめ(0.30)。応答の光(+0.6)が立つ余地を残す。呼吸はさせない
        glowWide.set(0.38f * glowStrength, 0xFFFFFFFF, phase);
        glowMid.set(0.60f * glowStrength, 0xFFFFFFFF, phase);
        // 応答の光: コマンダーで選んだ行の縁に寄る(Duo Pad が指の近くを灯すのと同じ。半径 260dp、0.15 秒で灯り 0.6 秒で引く)
        float sk = 0.6f * touchGlow;
        glowWide.spot(touchX, touchY, 260 * dp, 0.38f * sk);
        glowMid.spot(touchX, touchY, 260 * dp, 0.60f * sk);
        // 色編集ページ: 今の色・強さを「発光する丸いネオン」として見出しの右にプレビュー
        if (page == NEON_EDIT) drawOrb(cv, host.neonColor(neonWhich) | 0xFF000000, host.neonLevel(neonWhich) / 100f);
    }

    @Override protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv);
        // 外側の細い線(1.6dp)。ぼかした二層の上に、くっきりと淡く
        frameGlow.line(cv, frameBox, dp, Math.min(1f, 1.1f * glowStrength), 0xFFFFFFFF, 1, phase);
        if (touchGlow > 0) {
            frameGlow.alpha(Math.min(1f, 1.1f * 0.6f * touchGlow));
            frameGlow.spot(touchX, touchY, 260 * dp);
            frameGlow.ribbon(cv, frameBox, NeonGlow.LINE_DP * dp, 0xFFFFFFFF, 0, 0, null, null);
        }
    }

    // ───────── 応答の光(操作に答える) ─────────

    private float touchX, touchY, touchGlow;
    private android.animation.ValueAnimator touchAnim;

    /** 選んだ行の縁(左端・行の中央)に光を寄せる。操作が続く間は灯り、止まると引く。 */
    private void answer(int row, float peak) {
        if (row < 0 || row >= list.getChildCount()) return;
        View r = list.getChildAt(row);
        touchX = 0;
        touchY = list.getTop() + r.getTop() + r.getHeight() * 0.5f;
        if (touchAnim != null) touchAnim.cancel();
        float from = touchGlow;
        touchAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        touchAnim.setDuration(150 + 600);
        touchAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        touchAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue() * 750f;
            if (f < 150) { float e = f / 150f; touchGlow = from + (peak - from) * (1 - (1 - e) * (1 - e)); } // easeOut で灯る
            else { float e = (f - 150) / 600f; touchGlow = peak * (float) (0.5 + 0.5 * Math.cos(Math.PI * e)); } // easeInOut で引く
            invalidate();
        });
        touchAnim.start();
    }

    /** 発光する丸いネオン(オーブ)。色と強さを反映。NEON_EDIT のプレビューに使う。 */
    private void drawOrb(Canvas cv, int color, float level) {
        float k = Math.max(0.3f, level);                 // 0 でも消えないように下限
        float breath = 1f; // 呼吸はさせない(自分では動かない。Duo Pad と同じ)
        float r = 20 * dp;
        float cx = getWidth() - px(PAD) - r - 4 * dp, cy = px(PAD) + 13 * dp;
        int a0 = Math.round(210 * k * breath), a1 = Math.round(95 * k * breath);
        android.graphics.RadialGradient rg = new android.graphics.RadialGradient(cx, cy, r,
                new int[]{ withA(color, a0), withA(color, a1), withA(color, 0) },
                new float[]{ 0f, 0.42f, 1f }, android.graphics.Shader.TileMode.CLAMP);
        orbP.setShader(rg);
        cv.drawCircle(cx, cy, r, orbP);
        orbP.setShader(null);
        orbP.setColor(withA(color, Math.round(235 * k)));      // 色の芯
        cv.drawCircle(cx, cy, r * 0.34f, orbP);
        orbP.setColor(withA(0xFFFFFF, Math.round(210 * k)));   // 白いハイライト
        cv.drawCircle(cx, cy, r * 0.15f, orbP);
    }

    private static int withA(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF); }

    private final Paint orbP = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (getVisibility() == VISIBLE && isAttachedToWindow()) startShimmer(); else stopShimmer();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (getVisibility() == VISIBLE) startShimmer(); }

    @Override protected void onDetachedFromWindow() { stopShimmer(); super.onDetachedFromWindow(); }

    /** 発光の“巡り”と明滅を動かす(メニューを開いている間だけ)。 */
    private float intro = 1f;
    private android.animation.ValueAnimator introAnim;

    private void startShimmer() {
        if (shimmer != null) return;
        // 導入: 開いた瞬間に光が膨らむ
        intro = 0f;
        introAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        introAnim.setDuration(700);
        introAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        introAnim.addUpdateListener(a -> { intro = (float) a.getAnimatedValue(); invalidate(); });
        introAnim.start();
        shimmer = android.animation.ValueAnimator.ofFloat(0f, 1f);
        shimmer.setDuration(72000); // 模様が 72 秒で縁を一周(約 55px/秒。Duo Pad の長辺での速さに揃える)
        shimmer.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        shimmer.setInterpolator(new android.view.animation.LinearInterpolator());
        shimmer.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); list.invalidate(); });
        shimmer.start();
    }

    private void stopShimmer() {
        if (shimmer != null) { shimmer.cancel(); shimmer = null; }
        if (introAnim != null) { introAnim.cancel(); introAnim = null; }
    }

    // ───────── ページの中身 ─────────

    void show(int p) {
        int from = page;
        page = p;
        items.clear();
        focus = 0;
        editing = -1;
        if (p == MAIN) {
            title.setText(T(R.string.menu_title));
            items.add(new Item(() -> T(R.string.m_display), () -> GO, () -> show(DISPLAY)));
            items.add(new Item(() -> T(R.string.m_controls), () -> GO, () -> show(CONTROLS)));
            items.add(new Item(() -> T(R.string.m_system), () -> GO, () -> show(SYS)));
            items.add(new Item(() -> host.carOn() ? T(R.string.m_car_stop) : T(R.string.m_car_start),
                    () -> host.carOn() ? T(R.string.v_connected) : T(R.string.v_stopped), host::toggleCar, true));
            items.add(new Item(() -> T(R.string.m_close), () -> "", host::closePanel));
        } else if (p == DISPLAY) {
            title.setText(T(R.string.m_display));
            items.add(new Item(() -> T(R.string.d_target), host::sideText, host::switchSide, true));
            items.add(new Item(() -> T(R.string.d_layout), host::layoutText, host::swap, true));
            items.add(new Item(() -> T(R.string.d_split), () -> GO, () -> show(SPLIT)));
            items.add(back(T(R.string.to_menu)));
        } else if (p == CONTROLS) {
            title.setText(T(R.string.m_controls));
            items.add(new Item(() -> T(R.string.c_keys), () -> GO, () -> show(KEYS)));
            items.add(new Item(() -> T(R.string.c_neon), () -> GO, () -> show(NEON)));
            items.add(back(T(R.string.to_menu)));
        } else if (p == KEYS) {
            title.setText(T(R.string.c_keys));
            for (KeyMap.Action a : KeyMap.Action.values()) {
                items.add(new Item(() -> a.label(),
                        () -> host.learning() == a ? T(R.string.k_waiting) : host.keyMap().describe(a),
                        () -> { host.startLearn(a); refresh(); }));
            }
            items.add(new Item(() -> T(R.string.k_reset), () -> "", () -> { host.resetKeys(); refresh(); }));
            items.add(back(T(R.string.to_controls)));
        } else if (p == SYS) {
            title.setText(T(R.string.m_system));
            items.add(new Item(() -> T(R.string.s_range), () -> host.panelHalf() ? T(R.string.v_active_only) : T(R.string.v_full), host::togglePanelHalf, true));
            items.add(new Item(() -> T(R.string.s_access), () -> host.accessOn() ? T(R.string.on) : T(R.string.off), host::openAccessibility));
            items.add(new Item(() -> T(R.string.s_home), () -> host.isDefaultHome() ? "Duo Cruise" : host.otherHomeName(), host::chooseHome));
            items.add(new Item(() -> T(R.string.s_settings), () -> "", host::openSettings));
            items.add(new Item(() -> T(R.string.s_bootsound), () -> host.bootSound() ? T(R.string.on) : T(R.string.off), () -> { host.toggleBootSound(); refresh(); }, true));
            items.add(new Item(() -> T(R.string.s_guide), () -> "", host::showGuide));
            items.add(new Item(() -> T(R.string.s_log), () -> GO, () -> show(LOG)));
            items.add(new Item(() -> T(R.string.s_open_home), host::otherHomeName, host::goHome));
            items.add(back(T(R.string.to_menu)));
        } else if (p == SPLIT) {
            title.setText(T(R.string.d_split));
            items.add(new Item(() -> T(R.string.sp_normal), () -> {}, pctSlider(true)));
            items.add(new Item(() -> T(R.string.sp_auto), () -> host.autoSplitOn() ? T(R.string.on) : T(R.string.off), () -> { host.toggleAutoSplit(); refresh(); }, true));
            items.add(new Item(() -> T(R.string.sp_nav), () -> {}, pctSlider(false)));
            items.add(new Item(() -> T(R.string.sp_swap), () -> host.autoSwapOn() ? T(R.string.on) : T(R.string.off), () -> { host.toggleAutoSwap(); refresh(); }, true));
            items.add(new Item(() -> T(R.string.sp_hold), host::navHoldText, () -> { host.cycleNavHold(); refresh(); }, true));
            items.add(back(T(R.string.to_display)));
        } else if (p == NEON) {
            title.setText(T(R.string.c_neon));
            items.add(new Item(() -> T(R.string.n_target), () -> host.neonColorText(0), () -> { neonWhich = 0; show(NEON_EDIT); }));
            items.add(new Item(() -> T(R.string.n_ok), () -> host.neonColorText(1), () -> { neonWhich = 1; show(NEON_EDIT); }));
            items.add(new Item(() -> T(R.string.n_alert), () -> host.neonColorText(2), () -> { neonWhich = 2; show(NEON_EDIT); }));
            items.add(back(T(R.string.to_controls)));
        } else if (p == NEON_EDIT) {
            title.setText(neonWhich == 0 ? T(R.string.n_target_color) : neonWhich == 1 ? T(R.string.n_ok_color) : T(R.string.n_alert_color));
            items.add(new Item(() -> T(R.string.ne_r), () -> {}, channelSlider(0)));
            items.add(new Item(() -> T(R.string.ne_g), () -> {}, channelSlider(1)));
            items.add(new Item(() -> T(R.string.ne_b), () -> {}, channelSlider(2)));
            items.add(new Item(() -> T(R.string.ne_level), () -> {}, levelSlider()));
            items.add(back(T(R.string.to_neon)));
        } else {
            title.setText(T(R.string.s_log));
            logPage = 0;
            items.add(new Item(() -> T(R.string.log_newer), () -> "", () -> { logPage = Math.max(0, logPage - 1); refresh(); }));
            items.add(new Item(() -> T(R.string.log_older), () -> "", () -> { logPage++; refresh(); }));
            items.add(back(T(R.string.to_system)));
        }
        rebuildRows();
        logText.setVisibility(p == LOG ? VISIBLE : GONE);
        refresh();
        requestLayout();
        post(() -> focusRow(0));
        if (getVisibility() == VISIBLE && from != p) transition(depth(p) - depth(from));
    }

    // ───────── 移り変わり ─────────
    //   メニューに入る : 黒い面が 0.26 秒で降り、中身が少し下から浮かぶ。縁の光は膨らんで落ち着く
    //   下の階層へ     : 中身が右から滑り込む(奥へ進む)。行の縁が答える
    //   戻る           : 中身が左から滑り込む(手前へ戻る)
    //   メインに戻る   : 中身が少し沈みながら、面ごと 0.2 秒で消える
    //   いずれも移動と透明度だけ(ハードウェア描画)。CarPlay の映像を壊さない。

    private static int depth(int p) {
        switch (p) {
            case MAIN: return 0;
            case DISPLAY: case CONTROLS: case SYS: return 1;
            case NEON_EDIT: return 3;
            default: return 2;
        }
    }

    private View[] content() { return new View[]{ title, list, logText }; }

    /** 階層の移動。dir>0 で奥へ(右から)、dir<0 で手前へ(左から)。 */
    private void transition(int dir) {
        float dx = (dir >= 0 ? 1 : -1) * 56 * dp;
        long d = 0;
        for (View v : content()) {
            v.animate().cancel();
            v.setTranslationX(dx);
            v.setTranslationY(0);
            v.setAlpha(0f);
            v.animate().translationX(0).alpha(1f).setStartDelay(d).setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            d += 30; // 見出し→行の順に、わずかにずらして流れを作る
        }
    }

    /** メニューに入る。 */
    void enter() {
        animate().cancel();
        setAlpha(0f);
        animate().alpha(1f).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        long d = 60;
        for (View v : content()) {
            v.animate().cancel();
            v.setTranslationX(0);
            v.setTranslationY(14 * dp);
            v.setAlpha(0f);
            v.animate().translationY(0).alpha(1f).setStartDelay(d).setDuration(340)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            d += 40;
        }
    }

    /** メインに戻る。消え終わったら done を呼ぶ。 */
    void leave(Runnable done) {
        for (View v : content()) {
            v.animate().cancel();
            v.animate().translationY(10 * dp).alpha(0f).setStartDelay(0).setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f)).start();
        }
        animate().cancel();
        animate().alpha(0f).setDuration(220).setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    setAlpha(1f);
                    for (View v : content()) { v.setAlpha(1f); v.setTranslationY(0); }
                    done.run();
                }).start();
    }

    private void rebuildRows() {
        list.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            Item it = items.get(i);
            Row r = new Row(getContext());
            r.setPadding(px(34), 0, px(30), 0);

            // 車載: 項目と詳細は離さない。項目の列は固定幅、詳細はそのすぐ右に左寄せで置く(視線が一度で届く)
            TextView label = text(19, Theme.TEXT, true);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END); // 長い言語(独・仏など)で列からはみ出さない
            r.addView(label, new LinearLayout.LayoutParams(px(LABEL_COL), LayoutParams.MATCH_PARENT));

            TextView value = text(14, VALUE, false);
            value.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            r.addView(value, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

            SliderBar bar = new SliderBar(getContext());
            bar.setVisibility(it.slider == null ? GONE : VISIBLE);
            r.addView(bar, new LinearLayout.LayoutParams(px(SLIDER_W), LayoutParams.MATCH_PARENT));

            r.setOnClickListener(v -> { focus = idx; activate(idx); });
            r.setOnFocusChangeListener((v, has) -> {
                if (has) focus = idx;
                list.requestLayout();  // 選択行を大きく見せるため測り直す
                refresh();             // 文字の大きさ・色を更新
            });
            list.addView(r);
        }
    }

    /** 選んでいる行は、文字を大きく・純白にして「選択」を表す(発光は使わない)。 */
    private void paintRow(int i, boolean on) {
        if (i < 0 || i >= list.getChildCount() || i >= items.size()) return;
        Row r = (Row) list.getChildAt(i);
        Item it = items.get(i);
        TextView label = (TextView) r.getChildAt(0);
        label.setTextColor(on ? Color.WHITE : LABEL_DIM);   // 白のみ(選択=純白・非選択=薄い白)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, on ? 30 : 19);  // 選択で拡大
        TextView value = (TextView) r.getChildAt(1);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, on ? 24 : 18);
        SliderBar bar = (SliderBar) r.getChildAt(2);
        if (it.slider != null) {
            value.setVisibility(GONE);
            bar.setVisibility(VISIBLE);
            Slider sl = it.slider;
            bar.target = sl;
            bar.set(sl.value(), sl.max(), sl.text(), sl.tint(), on, editing == i);
        } else {
            bar.setVisibility(GONE);
            String s = it.value.get();
            if (it.adj && on && !s.isEmpty()) value.setText("‹ " + s + " ›");
            else value.setText(s);
            value.setTextColor(on ? Color.WHITE : VALUE);
            value.setVisibility(s.isEmpty() ? GONE : VISIBLE);
        }
    }

    /** 文字を今の状態に合わせて書き直す。 */
    void refresh() {
        for (int i = 0; i < list.getChildCount() && i < items.size(); i++) {
            Row r = (Row) list.getChildAt(i);
            ((TextView) r.getChildAt(0)).setText(items.get(i).label.get());
            paintRow(i, i == focus);  // 選択中の行(高さと文字の大きさを必ず揃える)
        }
        if (page == LOG) {
            List<String> all = DuoLog.snapshot();
            int per = Math.max(1, logLines);
            int maxPage = Math.max(0, (all.size() - 1) / per);
            logPage = Math.min(logPage, maxPage);
            StringBuilder sb = new StringBuilder();
            for (int i = logPage * per; i < Math.min(all.size(), (logPage + 1) * per); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(all.get(i));
            }
            logText.setText(sb.length() == 0 ? T(R.string.log_empty) : sb.toString());
            title.setText(T(R.string.log_title, logPage + 1, maxPage + 1));
        }
    }

    private void focusRow(int i) {
        if (list.getChildCount() == 0) return;
        focus = Math.max(0, Math.min(list.getChildCount() - 1, i));
        list.getChildAt(focus).requestFocus();
        list.requestLayout();  // タッチ操作中はフォーカスが付かないこともあるので、必ず描き直す
        refresh();
        answer(focus, 1f);
    }

    // ───────── キー(コマンダー) ─────────

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                if (down) { if (editing >= 0) nudge(1); else move(1); }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                if (down) { if (editing >= 0) nudge(-1); else move(-1); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) nudge(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) nudge(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) activate(focus);
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                if (!down) { if (editing >= 0) { editing = -1; refresh(); } else back(); }
                return true;
            default:
                return false;
        }
    }

    /** ダイヤルを回した量(+1 / -1)で、調整中なら値を、そうでなければ選ぶ行を上下に動かす。 */
    void rotate(int steps) {
        int s = steps > 0 ? 1 : -1;
        for (int i = 0; i < Math.abs(steps); i++) { if (editing >= 0) nudge(s); else move(s); }
    }

    private void move(int delta) {
        int n = list.getChildCount();
        if (n == 0) return;
        focusRow(((focus + delta) % n + n) % n);
    }

    /**
     * 押したとき。スライダーの行は「調整モード」に入る/出る(入ると回す＝値の増減)。
     * それ以外(サブページ・オン/オフ・実行)はそのまま実行する。
     */
    private void activate(int idx) {
        if (idx < 0 || idx >= items.size()) return;
        answer(idx, 1.5f); // 決定は少し強く
        Item it = items.get(idx);
        if (it.slider != null) { editing = (editing == idx) ? -1 : idx; refresh(); }
        else { editing = -1; it.run.run(); refresh(); }
    }

    /** 調整中(または左右)で、スライダーの値を増減、オン/オフ等の値を切り替える。 */
    private void nudge(int dir) {
        answer(focus, 1f);
        int i = editing >= 0 ? editing : focus;
        if (i < 0 || i >= items.size()) return;
        Item it = items.get(i);
        if (it.slider != null) { it.slider.nudge(dir); refresh(); }
        else if (editing < 0 && it.adj) { it.run.run(); refresh(); }
    }

    /** スライダーを v に近づける(値の刻みはスライダーごとに違うので、nudge を繰り返す)。 */
    private void seek(Slider sl, int v) {
        for (int guard = 0; guard < 64; guard++) {
            int now = sl.value();
            if (now == v) break;
            int dir = v > now ? 1 : -1;
            sl.nudge(dir);
            int next = sl.value();
            if (next == now || (dir > 0 ? next > v : next < v)) { if (Math.abs(next - v) > Math.abs(now - v)) sl.nudge(-dir); break; }
        }
        answer(focus, 1f);
        refresh();
    }

    /** 今のページの1つ上(親)のページ。 */
    private int parentPage(int p) {
        switch (p) {
            case DISPLAY: case CONTROLS: case SYS: return MAIN;
            case SPLIT: return DISPLAY;
            case KEYS: case NEON: return CONTROLS;
            case LOG: return SYS;
            case NEON_EDIT: return NEON;
            default: return MAIN;
        }
    }

    void back() {
        if (page != MAIN) { host.cancelLearn(); show(parentPage(page)); }
        else host.closePanel();
    }

    /** 「戻る」の行。押すと1つ上のページへ。label に戻り先を書いて導線にする。 */
    private Item back(String label) {
        return new Item(() -> T(R.string.back), () -> label, this::back);
    }

    // ───────── スライダーの中身 ─────────

    /** 分割比のスライダー(通常/ナビ中)。 */
    private Slider pctSlider(boolean normal) {
        return new Slider() {
            public int value() { return normal ? host.splitYtPct() : host.navYtPct(); }
            public int max() { return 100; }
            public void nudge(int d) { if (normal) host.adjustSplitYt(d); else host.adjustNavYt(d); }
            public String text() { return value() + "%"; }
            public int tint() { return Theme.NEON_WHITE; }
        };
    }

    /** ネオンの色の1チャンネル(R/G/B)。ch: 0=R,1=G,2=B。0〜255。 */
    private Slider channelSlider(int ch) {
        final int shift = 16 - ch * 8;
        final int hint = ch == 0 ? 0xFFFF5B5B : ch == 1 ? 0xFF5BE37C : 0xFF5B8CFF;
        return new Slider() {
            public int value() { return (host.neonColor(neonWhich) >> shift) & 0xFF; }
            public int max() { return 255; }
            public void nudge(int d) {
                int c = host.neonColor(neonWhich);
                int v = Math.max(0, Math.min(255, value() + d * 8));
                host.setNeonColor(neonWhich, (c & ~(0xFF << shift)) | (v << shift));
            }
            public String text() { return String.valueOf(value()); }
            public int tint() { return hint; }
        };
    }

    /** 発光の強さ(0〜100%)。つまみの色は今の色そのもの。 */
    private Slider levelSlider() {
        return new Slider() {
            public int value() { return host.neonLevel(neonWhich); }
            public int max() { return 100; }
            public void nudge(int d) { host.setNeonLevel(neonWhich, Math.max(0, Math.min(100, value() + d * 5))); }
            public String text() { return value() + "%"; }
            public int tint() { return host.neonColor(neonWhich); }
        };
    }

    // ───────── 並べ方(縦1列・画面高に収める) ─────────

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        layoutChildren(w, h, true);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutChildren(r - l, b - t, false);
    }

    private void place(View v, int x0, int y0, int x1, int y1, boolean measure) {
        if (measure) v.measure(MeasureSpec.makeMeasureSpec(Math.max(0, x1 - x0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, y1 - y0), MeasureSpec.EXACTLY));
        else v.layout(x0, y0, x1, y1);
    }

    private void layoutChildren(int w, int h, boolean measure) {
        int pad = px(PAD), head = px(HEADER);
        // 超横長を端まで使う(左右の余白は最小限だけ)
        int side = px(34);
        int listW = w - side * 2;
        int left = side;
        place(glowWide, 0, 0, w, h, measure);
        place(glowMid, 0, 0, w, h, measure);
        place(title, left, pad, left + listW, pad + px(38), measure);

        int top = pad + head;
        int bottom = h - pad;
        if (page == LOG) {
            int listH = (bottom - top) * 3 / 5;        // 下に行、上に本文
            int logBottom = bottom - listH;
            logText.setBackground(Theme.round(0x4D000000, 12 * dp));
            place(logText, left, top, left + listW, Math.max(top, logBottom - px(8)), measure);
            if (!measure && logText.getLineHeight() > 0) {
                int fit = Math.max(1, (logBottom - top - px(20)) / logText.getLineHeight());
                if (fit != logLines) { logLines = fit; post(this::refresh); }
            }
            place(list, left, logBottom, left + listW, bottom, measure);
            return;
        }
        place(list, left, top, left + listW, bottom, measure);
    }

    /** 行を縦に積む。選んでいる行だけ大きく、他は小さくして「選択」を表す(全部が画面に収まる)。 */
    private final class ListPane extends ViewGroup {
        private static final float FOCUS = 1.9f;  // 選択行は他の何倍の高さか

        ListPane(Context c) { super(c); setClipChildren(false); setClipToPadding(false); }

        private int baseH(int n, int h) {
            if (n <= 0) return 0;
            int gap = px(ROW_GAP);
            int avail = h - gap * (n - 1);
            return Math.max(px(24), Math.round(avail / ((n - 1) + FOCUS)));
        }

        private int heightOf(int i, int base) {
            return i == focus ? Math.round(base * FOCUS) : base;
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            setMeasuredDimension(w, h);
            int n = getChildCount(), base = baseH(n, h);
            int rw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
            for (int i = 0; i < n; i++)
                getChildAt(i).measure(rw, MeasureSpec.makeMeasureSpec(heightOf(i, base), MeasureSpec.EXACTLY));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t, n = getChildCount(), gap = px(ROW_GAP), base = baseH(n, h);
            int total = (n - 1) * gap;
            for (int i = 0; i < n; i++) total += heightOf(i, base);
            int y = Math.max(0, (h - total) / 2);
            for (int i = 0; i < n; i++) {
                int rh = heightOf(i, base);
                getChildAt(i).layout(0, y, w, y + rh);
                y += rh + gap;
            }
        }
    }

    /** 1行。文字だけ(発光・下地は使わない)。選択は文字の大きさと白さで表す。 */
    private final class Row extends LinearLayout {
        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(false);
            setDefaultFocusHighlightEnabled(false); // 既定の灰色ハイライトは出さない(選択は文字の大きさで示す)
        }
    }

    /**
     * スライダー。押して「調整モード」に入ると、ダイヤルで増減できる。
     * 値は“ネオンの位置”で示す(発光するつまみ)。色は項目に合わせて tint する(R=赤み等・色は現在色)。
     */
    private final class SliderBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chev = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float frac = 0.5f;
        private int tint = 0xFFFFFFFF;
        private String label = "";
        private boolean on, editing;
        private int lastValue = Integer.MIN_VALUE;
        private float flare = 0;                       // 値が変わった瞬間に灯り、0.6 秒で引く
        private android.animation.ValueAnimator flareAnim;
        /** 指で触れた・なぞった位置の値にする(DuoPad のカーソルのドラッグも同じ)。 */
        Slider target;

        SliderBar(Context c) {
            super(c);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            num.setTextAlign(Paint.Align.RIGHT);
            unit.setTypeface(Typeface.DEFAULT_BOLD);
            chev.setTextSize(22 * dp);
            chev.setTextAlign(Paint.Align.CENTER);
            chev.setFakeBoldText(true);
        }

        // 数字の欄の幅。数字は溝のすぐ右に置く(選択 34sp で「100%」が収まる幅)
        private float numW() { return 96 * dp; }
        private float x0() { return 4 * dp + (editing ? 28 * dp : 0); }
        private float x1() { return getWidth() - numW() - (editing ? 28 * dp : 12 * dp); }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (target == null) return false;
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE) {
                float f = Math.max(0f, Math.min(1f, (e.getX() - x0()) / Math.max(1f, x1() - x0())));
                seek(target, Math.round(f * target.max()));
                return true;
            }
            return a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL;
        }

        void set(int value, int max, String label, int tint, boolean on, boolean editing) {
            this.frac = max <= 0 ? 0 : Math.max(0f, Math.min(1f, value / (float) max));
            this.tint = tint | 0xFF000000;
            this.label = label; this.on = on; this.editing = editing;
            if (lastValue != Integer.MIN_VALUE && value != lastValue) flare();
            lastValue = value;
            track.setColor(on ? 0x40FFFFFF : 0x26FFFFFF);
            fill.setColor(withA(this.tint, on ? 255 : 190));
            knob.setColor(0xFFFFFFFF);
            num.setColor(on ? 0xFFFFFFFF : VALUE);
            num.setTextSize((on ? 34 : 24) * dp);
            unit.setColor(on ? 0xCCFFFFFF : 0x80FFFFFF);
            unit.setTextSize((on ? 18 : 14) * dp);
            chev.setColor(0xFFFFFFFF);
            invalidate();
        }

        /** 値が動いた。光がふっと強まり、引いていく(Duo Pad の応答と同じ 0.15 秒→0.6 秒)。 */
        private void flare() {
            if (flareAnim != null) flareAnim.cancel();
            float from = flare;
            flareAnim = android.animation.ValueAnimator.ofFloat(0f, 750f);
            flareAnim.setDuration(750);
            flareAnim.setInterpolator(new android.view.animation.LinearInterpolator());
            flareAnim.addUpdateListener(an -> {
                float t = (float) an.getAnimatedValue();
                flare = t < 150 ? from + (1 - from) * (t / 150f) : (float) (0.5 + 0.5 * Math.cos(Math.PI * (t - 150) / 600f));
                invalidate();
            });
            flareAnim.start();
        }

        private int withA(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cy = h / 2f;
            float x0 = x0(), x1 = x1();
            float th = (on ? 12 : 9) * dp;               // 車載: 太く。角は落とさない(四角)
            float kx = x0 + (x1 - x0) * frac;
            // 溝
            cv.drawRect(x0, cy - th / 2, x1, cy + th / 2, track);
            // 値のぶんの光: 埋まった所の上下に、値の色の光がにじむ。値が動くと強まる(連動)
            // 箱に見えないよう、上下だけでなく両端も柔らかく消す(縦のにじみ × 横のにじみ)
            float k = (editing ? 0.70f : on ? 0.42f : 0.20f) + 0.45f * flare;
            float hh = th * 2.0f, fade = 22 * dp;
            if (kx - x0 > 2) {
                android.graphics.Shader vert = new android.graphics.LinearGradient(0, cy - hh, 0, cy + hh,
                        new int[]{ withA(tint, 0), withA(tint, Math.round(80 * k)), withA(tint, Math.round(80 * k)), withA(tint, 0) },
                        new float[]{ 0f, 0.40f, 0.60f, 1f }, android.graphics.Shader.TileMode.CLAMP);
                float span = kx + fade - (x0 - fade);
                float f = Math.min(0.45f, fade / span);
                android.graphics.Shader horiz = new android.graphics.LinearGradient(x0 - fade, 0, kx + fade, 0,
                        new int[]{ 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF }, new float[]{ 0f, f, 1 - f, 1f },
                        android.graphics.Shader.TileMode.CLAMP);
                halo.setShader(new android.graphics.ComposeShader(vert, horiz, android.graphics.PorterDuff.Mode.DST_IN));
                cv.drawRect(x0 - fade, cy - hh, kx + fade, cy + hh, halo);
                halo.setShader(null);
            }
            cv.drawRect(x0, cy - th / 2, kx, cy + th / 2, fill);
            // つまみ: 値の位置で光る。調整中・値が動いた瞬間は強く
            float kr = (editing ? 15 : on ? 12 : 9) * dp;
            float gr = kr * (2.2f + 0.8f * flare);
            int a0 = Math.round((editing ? 150 : on ? 95 : 55) + 100 * flare);
            glowP.setShader(new android.graphics.RadialGradient(kx, cy, gr,
                    new int[]{ withA(tint, a0), withA(tint, a0 * 2 / 5), withA(tint, 0) },
                    new float[]{ 0f, 0.38f, 1f }, android.graphics.Shader.TileMode.CLAMP));
            cv.drawCircle(kx, cy, gr, glowP);
            glowP.setShader(null);
            cv.drawCircle(kx, cy, kr * 0.62f, knob);     // 白い芯
            if (editing) {
                cv.drawText("‹", x0 - 16 * dp, cy + chev.getTextSize() * 0.36f, chev);
                cv.drawText("›", x1 + 16 * dp, cy + chev.getTextSize() * 0.36f, chev);
            }
            // 数字: 一目で読める大きさ。「%」は小さく添える
            String n = label, u = "";
            if (n.endsWith("%")) { n = n.substring(0, n.length() - 1); u = "%"; }
            float nx = x1 + (editing ? 28 : 12) * dp + 14 * dp; // 溝の終わり(と ›)のすぐ右から、左寄せ
            float base = cy + num.getTextSize() * 0.36f;
            num.setTextAlign(Paint.Align.LEFT);
            cv.drawText(n, nx, base, num);
            cv.drawText(u, nx + num.measureText(n) + 3 * dp, base, unit);
        }
    }
}
