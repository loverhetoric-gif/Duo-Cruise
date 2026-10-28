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

    static final int MAIN = 0, KEYS = 1, LOG = 2, SYS = 3, SPLIT = 4, NEON = 5, NEON_EDIT = 6;

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
    private static final int VALUE = 0xFFBFCBDE; // 黒の透過でも読めるよう、値の文字は明るめ

    private final Host host;
    private final float dp;
    private final TextView title, logText;
    private final ListPane list;
    private final RectF frameBox = new RectF();
    private final NeonGlow frameGlow = new NeonGlow();
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
        setBackgroundColor(0xC2000000); // 黒の透過(後ろの映像をうっすら通す)
        setClickable(true);             // 下の2画面へタッチを通さない
        setWillNotDraw(false);
        title = text(26, Theme.TEXT, true);
        logText = text(14, Theme.TEXT, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(px(14), px(10), px(14), px(10));
        list = new ListPane(c);
        addView(title);
        addView(logText);
        addView(list);
        show(MAIN);
    }

    int page() { return page; }

    private int px(float v) { return Math.round(v * dp); }

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
        frameGlow.draw(cv, frameBox, dp, 100, 0.95f, Theme.NEON_WHITE, phase);
        // 色編集ページ: 今の色・強さを「発光する丸いネオン」として見出しの右にプレビュー
        if (page == NEON_EDIT) drawOrb(cv, host.neonColor(neonWhich) | 0xFF000000, host.neonLevel(neonWhich) / 100f);
    }

    /** 発光する丸いネオン(オーブ)。色と強さを反映。NEON_EDIT のプレビューに使う。 */
    private void drawOrb(Canvas cv, int color, float level) {
        float k = Math.max(0.3f, level);                 // 0 でも消えないように下限
        float breath = 0.9f + 0.1f * (float) Math.sin(phase * 2 * Math.PI);
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
    private void startShimmer() {
        if (shimmer != null) return;
        shimmer = android.animation.ValueAnimator.ofFloat(0f, 1f);
        shimmer.setDuration(2800);
        shimmer.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        shimmer.setInterpolator(new android.view.animation.LinearInterpolator());
        shimmer.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); list.invalidate(); });
        shimmer.start();
    }

    private void stopShimmer() { if (shimmer != null) { shimmer.cancel(); shimmer = null; } }

    // ───────── ページの中身 ─────────

    void show(int p) {
        page = p;
        items.clear();
        focus = 0;
        editing = -1;
        if (p == MAIN) {
            title.setText("操作メニュー");
            items.add(new Item(() -> "操作対象の画面", host::sideText, host::switchSide, true));
            items.add(new Item(() -> "画面の配置", host::layoutText, host::swap, true));
            items.add(new Item(() -> "画面分割とナビ連動", host::splitYtText, () -> show(SPLIT)));
            items.add(new Item(() -> host.carOn() ? "CarPlay を終了" : "CarPlay を開始",
                    () -> host.carOn() ? "接続中" : "停止中", host::toggleCar, true));
            items.add(new Item(() -> "コマンダーのボタン割り当て", () -> "操作ごとのボタンを設定", () -> show(KEYS)));
            items.add(new Item(() -> "動作記録", () -> "状態とエラーの履歴", () -> show(LOG)));
            items.add(new Item(() -> "ホーム画面を開く", host::otherHomeName, host::goHome));
            items.add(new Item(() -> "設定", () -> "システム設定とホームアプリ", () -> show(SYS)));
            items.add(new Item(() -> "メニューを閉じる", () -> "", host::closePanel));
        } else if (p == KEYS) {
            title.setText("コマンダーのボタン割り当て");
            for (KeyMap.Action a : KeyMap.Action.values()) {
                items.add(new Item(() -> a.label,
                        () -> host.learning() == a ? "▶ ボタンを押してください（連続1〜4回、または長押し）" : host.keyMap().describe(a),
                        () -> { host.startLearn(a); refresh(); }));
            }
            items.add(new Item(() -> "ユーザー補助", () -> host.accessOn() ? "オン" : "オフ（ホーム・タスク操作が行えません）",
                    host::openAccessibility));
            items.add(new Item(() -> "割り当てを初期状態に戻す", () -> "", () -> { host.resetKeys(); refresh(); }));
            items.add(new Item(() -> "戻る", () -> "", () -> { host.cancelLearn(); show(MAIN); }));
        } else if (p == SYS) {
            title.setText("設定");
            items.add(new Item(() -> "システム設定を開く", () -> "", host::openSettings));
            items.add(new Item(() -> "ホームアプリ", () -> host.isDefaultHome() ? "Duo Cruise" : host.otherHomeName(), host::chooseHome));
            items.add(new Item(() -> "メニューの表示範囲", () -> host.panelHalf() ? "操作中の画面のみ" : "画面全体", host::togglePanelHalf, true));
            items.add(new Item(() -> "ユーザー補助", () -> host.accessOn() ? "オン" : "オフ（ホーム・タスク操作が行えません）",
                    host::openAccessibility));
            items.add(new Item(() -> "ネオンの色・強さ", () -> "", () -> show(NEON)));
            items.add(new Item(() -> "ホーム画面を開く", host::otherHomeName, host::goHome));
            items.add(new Item(() -> "戻る", () -> "", () -> show(MAIN)));
        } else if (p == SPLIT) {
            title.setText("画面分割とナビ連動");
            items.add(new Item(() -> "通常の分割比", () -> {}, pctSlider(true)));
            items.add(new Item(() -> "ナビ案内中に自動で調整", () -> host.autoSplitOn() ? "オン" : "オフ", () -> { host.toggleAutoSplit(); refresh(); }, true));
            items.add(new Item(() -> "ナビ案内中の分割比", () -> {}, pctSlider(false)));
            items.add(new Item(() -> "ナビ案内中は地図を右に配置", () -> host.autoSwapOn() ? "オン" : "オフ", () -> { host.toggleAutoSwap(); refresh(); }, true));
            items.add(new Item(() -> "通常の配置に戻るまでの時間", host::navHoldText, () -> { host.cycleNavHold(); refresh(); }, true));
            items.add(new Item(() -> "戻る", () -> "", () -> show(MAIN)));
        } else if (p == NEON) {
            title.setText("ネオンの色・強さ");
            items.add(new Item(() -> "ターゲット切替", () -> host.neonColorText(0), () -> { neonWhich = 0; show(NEON_EDIT); }));
            items.add(new Item(() -> "接続完了", () -> host.neonColorText(1), () -> { neonWhich = 1; show(NEON_EDIT); }));
            items.add(new Item(() -> "接続不良", () -> host.neonColorText(2), () -> { neonWhich = 2; show(NEON_EDIT); }));
            items.add(new Item(() -> "戻る", () -> "", () -> show(SYS)));
        } else if (p == NEON_EDIT) {
            title.setText(neonWhich == 0 ? "ターゲット切替の色" : neonWhich == 1 ? "接続完了の色" : "接続不良の色");
            items.add(new Item(() -> "赤 (R)", () -> {}, channelSlider(0)));
            items.add(new Item(() -> "緑 (G)", () -> {}, channelSlider(1)));
            items.add(new Item(() -> "青 (B)", () -> {}, channelSlider(2)));
            items.add(new Item(() -> "発光の強さ", () -> {}, levelSlider()));
            items.add(new Item(() -> "戻る", () -> "", () -> show(NEON)));
        } else {
            title.setText("動作記録");
            logPage = 0;
            items.add(new Item(() -> "新しい記録へ", () -> "", () -> { logPage = Math.max(0, logPage - 1); refresh(); }));
            items.add(new Item(() -> "古い記録へ", () -> "", () -> { logPage++; refresh(); }));
            items.add(new Item(() -> "戻る", () -> "", () -> show(MAIN)));
        }
        rebuildRows();
        logText.setVisibility(p == LOG ? VISIBLE : GONE);
        refresh();
        requestLayout();
        post(() -> focusRow(0));
    }

    private void rebuildRows() {
        list.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            Item it = items.get(i);
            Row r = new Row(getContext());
            r.setPadding(px(34), 0, px(30), 0);

            TextView label = text(19, Theme.TEXT, true);
            label.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

            TextView value = text(14, VALUE, false);
            value.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            value.setPadding(px(14), 0, 0, 0);
            r.addView(value, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.1f));

            SliderBar bar = new SliderBar(getContext());
            bar.setVisibility(it.slider == null ? GONE : VISIBLE);
            r.addView(bar, new LinearLayout.LayoutParams(px(320), LayoutParams.MATCH_PARENT));

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
        label.setTextColor(on ? Color.WHITE : Theme.SUB);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, on ? 30 : 19);  // 選択で拡大
        TextView value = (TextView) r.getChildAt(1);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, on ? 17 : 14);
        SliderBar bar = (SliderBar) r.getChildAt(2);
        if (it.slider != null) {
            value.setVisibility(GONE);
            bar.setVisibility(VISIBLE);
            Slider sl = it.slider;
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
            paintRow(i, r.isFocused());
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
            logText.setText(sb.length() == 0 ? "記録はまだありません" : sb.toString());
            title.setText("動作記録 " + (logPage + 1) + "/" + (maxPage + 1));
        }
    }

    private void focusRow(int i) {
        if (list.getChildCount() == 0) return;
        focus = Math.max(0, Math.min(list.getChildCount() - 1, i));
        list.getChildAt(focus).requestFocus();
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
        Item it = items.get(idx);
        if (it.slider != null) { editing = (editing == idx) ? -1 : idx; refresh(); }
        else { editing = -1; it.run.run(); refresh(); }
    }

    /** 調整中(または左右)で、スライダーの値を増減、オン/オフ等の値を切り替える。 */
    private void nudge(int dir) {
        int i = editing >= 0 ? editing : focus;
        if (i < 0 || i >= items.size()) return;
        Item it = items.get(i);
        if (it.slider != null) { it.slider.nudge(dir); refresh(); }
        else if (editing < 0 && it.adj) { it.run.run(); refresh(); }
    }

    void back() {
        if (page != MAIN) { host.cancelLearn(); show(page == NEON_EDIT ? NEON : page == NEON ? SYS : MAIN); }
        else host.closePanel();
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
        }
    }

    /**
     * スライダー。押して「調整モード」に入ると、ダイヤルで増減できる。
     * 値は“ネオンの位置”で示す(発光するつまみ)。色は項目に合わせて tint する(R=赤み等・色は現在色)。
     */
    private final class SliderBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chev = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float frac = 0.5f;
        private int tint = 0xFFFFFFFF;
        private String label = "";
        private boolean on, editing;

        SliderBar(Context c) {
            super(c);
            txt.setTextSize(14 * dp);
            txt.setTextAlign(Paint.Align.RIGHT);
            txt.setFakeBoldText(true);
            chev.setTextSize(15 * dp);
            chev.setTextAlign(Paint.Align.CENTER);
            chev.setFakeBoldText(true);
        }

        void set(int value, int max, String label, int tint, boolean on, boolean editing) {
            this.frac = max <= 0 ? 0 : Math.max(0f, Math.min(1f, value / (float) max));
            this.tint = tint | 0xFF000000;
            this.label = label; this.on = on; this.editing = editing;
            track.setColor(on ? 0x59FFFFFF : 0x33FFFFFF);
            fill.setColor(withA(this.tint, on ? 255 : 200));
            knob.setColor(0xFFFFFFFF);
            txt.setColor(on ? 0xFFFFFFFF : VALUE);
            chev.setColor(0xFFFFFFFF);
            invalidate();
        }

        private int withA(int c, int a) { return (a << 24) | (c & 0xFFFFFF); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cy = h / 2f;
            float labelW = 52 * dp;
            float chevW = editing ? 22 * dp : 0;
            float x0 = 6 * dp + chevW, x1 = w - labelW - chevW;
            float th = 5 * dp, r = th / 2;
            cv.drawRoundRect(new RectF(x0, cy - r, x1, cy + r), r, r, track);
            float kx = x0 + (x1 - x0) * frac;
            cv.drawRoundRect(new RectF(x0, cy - r, kx, cy + r), r, r, fill);
            // 発光するつまみ(ネオンの位置＝値)。外側ほど薄い円を重ねて、ぼかし無しで光らせる
            float kr = (editing ? 11 : 8) * dp;
            int steps = 5;
            for (int i = steps; i >= 1; i--) {
                glowP.setColor(withA(tint, Math.round((on ? 46 : 30) * (i / (float) steps))));
                cv.drawCircle(kx, cy, kr + i * 2.2f * dp, glowP);
            }
            knob.setColor(0xFFFFFFFF);
            cv.drawCircle(kx, cy, kr * 0.55f, knob);     // 白い芯
            if (editing) {
                cv.drawText("‹", x0 - 12 * dp, cy + chev.getTextSize() * 0.36f, chev);
                cv.drawText("›", x1 + 12 * dp, cy + chev.getTextSize() * 0.36f, chev);
            }
            cv.drawText(label, w, cy + txt.getTextSize() * 0.35f, txt);
        }
    }
}
