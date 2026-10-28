package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * 操作盤。2画面の上に重ねて出す。コマンダー(ダイヤル)だけで全部操作できる。
 *
 * 見た目: 縦に1列のリスト。暗い車内で読みやすいダークトーン、選んでいる行だけ白く浮かせる。
 * 操作:
 *  ・ダイヤルを回す / 上下 … 選ぶ行を上下に動かす
 *  ・左右 … 値のある行(比率・オン/オフなど)を、その場で切り替える
 *  ・押す … 決定(サブpage へ入る・実行・値を1つ送る)
 *  ・戻る … 1つ前へ(主page では操作盤を閉じる)
 *
 * ページは5つ: 主(よく使う操作)/ ボタンの割り当て / 記録 / 設定・ほかのアプリへ / 左右の比率・ナビ連動。
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
        void toggleAutoSplit(); boolean autoSplitOn();
        void toggleAutoSwap(); boolean autoSwapOn();
        void cycleNavHold(); String navHoldText();
        String layoutText();
        String sideText();
        KeyMap keyMap();
        void startLearn(KeyMap.Action a);
        void cancelLearn();
        KeyMap.Action learning();
        void resetKeys();
    }

    static final int MAIN = 0, KEYS = 1, LOG = 2, SYS = 3, SPLIT = 4;

    private interface Text { String get(); }

    private static final class Item {
        final Text label, value;
        final int accent;        // 左端の細い帯の色(その行の性格を示す)。0 なら帯なし
        final Runnable run;      // 押したとき
        final boolean adj;       // 左右でその場で変えられる値か
        Item(Text label, Text value, int accent, Runnable run) { this(label, value, accent, run, false); }
        Item(Text label, Text value, int accent, Runnable run, boolean adj) {
            this.label = label; this.value = value; this.accent = accent; this.run = run; this.adj = adj;
        }
    }

    // 1行の高さの範囲(dp)と、行間・余白。
    private static final float ROW_MIN = 34, ROW_MAX = 66, ROW_GAP = 8, PAD = 16, HEADER = 50;

    private final Host host;
    private final float dp;
    private final TextView title, chipLayout, chipSide, chipAccess, logText;
    private final List<Item> items = new ArrayList<>();
    private final List<LinearLayout> rows = new ArrayList<>();
    private int page = MAIN, focus = 0, logPage = 0, logLines = 8;

    PanelView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.SCRIM);
        setClickable(true); // 下の2画面へタッチを通さない
        title = text(20, Theme.TEXT, true);
        chipLayout = chip();
        chipSide = chip();
        chipAccess = chip();
        logText = text(13, Theme.TEXT, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackground(Theme.round(Theme.CARD, 12 * dp));
        logText.setPadding(px(12), px(8), px(12), px(8));
        addView(title);
        addView(chipLayout);
        addView(chipSide);
        addView(chipAccess);
        addView(logText);
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

    private TextView chip() {
        TextView t = text(13, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), 0, px(12), 0);
        return t;
    }

    // ───────── ページの中身 ─────────

    void show(int p) {
        page = p;
        items.clear();
        focus = 0;
        if (p == MAIN) {
            title.setText("操作メニュー");
            items.add(new Item(() -> "操作対象の画面", host::sideText, Theme.ACCENT, host::switchSide, true));
            items.add(new Item(() -> "画面の配置", host::layoutText, Theme.ACCENT, host::swap, true));
            items.add(new Item(() -> "画面分割とナビ連動", host::splitYtText, 0, () -> show(SPLIT)));
            items.add(new Item(() -> host.carOn() ? "CarPlay を終了" : "CarPlay を開始",
                    () -> host.carOn() ? "接続中" : "停止中",
                    0, host::toggleCar, true));
            items.add(new Item(() -> "コマンダーのボタン割り当て", () -> "操作ごとのボタンを設定", 0, () -> show(KEYS)));
            items.add(new Item(() -> "動作記録", () -> "状態とエラーの履歴", 0, () -> show(LOG)));
            items.add(new Item(() -> "ホーム画面を開く", host::otherHomeName, 0, host::goHome));
            items.add(new Item(() -> "設定", () -> "システム設定とホームアプリ", 0, () -> show(SYS)));
            items.add(new Item(() -> "メニューを閉じる", () -> "", 0, host::closePanel));
        } else if (p == KEYS) {
            title.setText("コマンダーのボタン割り当て");
            for (KeyMap.Action a : KeyMap.Action.values()) {
                items.add(new Item(() -> a.label,
                        () -> host.learning() == a ? "▶ ボタンを押してください（連続1〜4回、または長押し）" : host.keyMap().describe(a),
                        0, () -> { host.startLearn(a); refresh(); }));
            }
            items.add(new Item(() -> "ユーザー補助", () -> host.accessOn() ? "オン" : "オフ（ホーム・タスク操作が行えません）",
                    host.accessOn() ? 0 : Theme.AMBER, host::openAccessibility));
            items.add(new Item(() -> "割り当てを初期状態に戻す", () -> "", 0, () -> { host.resetKeys(); refresh(); }));
            items.add(new Item(() -> "戻る", () -> "", 0, () -> { host.cancelLearn(); show(MAIN); }));
        } else if (p == SYS) {
            title.setText("設定");
            items.add(new Item(() -> "システム設定を開く", () -> "", 0, host::openSettings));
            items.add(new Item(() -> "ホームアプリ", () -> host.isDefaultHome() ? "Duo Cruise" : host.otherHomeName(), 0, host::chooseHome));
            items.add(new Item(() -> "メニューの表示範囲", () -> host.panelHalf() ? "操作中の画面のみ" : "画面全体", Theme.ACCENT, host::togglePanelHalf, true));
            items.add(new Item(() -> "ユーザー補助", () -> host.accessOn() ? "オン" : "オフ（ホーム・タスク操作が行えません）",
                    host.accessOn() ? 0 : Theme.AMBER, host::openAccessibility));
            items.add(new Item(() -> "ホーム画面を開く", host::otherHomeName, 0, host::goHome));
            items.add(new Item(() -> "戻る", () -> "", 0, () -> show(MAIN)));
        } else if (p == SPLIT) {
            title.setText("画面分割とナビ連動");
            items.add(new Item(() -> "通常の分割比", host::splitYtText, Theme.ACCENT, () -> { host.cycleSplitYt(); refresh(); }, true));
            items.add(new Item(() -> "ナビ案内中に自動で調整", () -> host.autoSplitOn() ? "オン" : "オフ", Theme.ACCENT, () -> { host.toggleAutoSplit(); refresh(); }, true));
            items.add(new Item(() -> "ナビ案内中の分割比", host::navYtText, Theme.ACCENT, () -> { host.cycleNavYt(); refresh(); }, true));
            items.add(new Item(() -> "ナビ案内中は地図を右に配置", () -> host.autoSwapOn() ? "オン" : "オフ", Theme.ACCENT, () -> { host.toggleAutoSwap(); refresh(); }, true));
            items.add(new Item(() -> "通常の配置に戻るまでの時間", host::navHoldText, Theme.ACCENT, () -> { host.cycleNavHold(); refresh(); }, true));
            items.add(new Item(() -> "戻る", () -> "", 0, () -> show(MAIN)));
        } else {
            title.setText("動作記録");
            logPage = 0;
            items.add(new Item(() -> "新しい記録へ", () -> "", 0, () -> { logPage = Math.max(0, logPage - 1); refresh(); }));
            items.add(new Item(() -> "古い記録へ", () -> "", 0, () -> { logPage++; refresh(); }));
            items.add(new Item(() -> "戻る", () -> "", 0, () -> show(MAIN)));
        }
        rebuildRows();
        logText.setVisibility(p == LOG ? VISIBLE : GONE);
        refresh();
        requestLayout();
        post(() -> focusRow(0));
    }

    private void rebuildRows() {
        for (View r : rows) removeView(r);
        rows.clear();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            Item it = items.get(i);
            LinearLayout r = new LinearLayout(getContext());
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setFocusable(true);
            r.setFocusableInTouchMode(false);
            r.setBackground(rowBg(false));
            r.setPadding(px(16), px(6), px(16), px(6));

            // 左端の細い帯(その行の性格)。色が無ければ透明で場所だけ空ける
            View bar = new View(getContext());
            bar.setBackground(Theme.round(it.accent == 0 ? Color.TRANSPARENT : it.accent, 2 * dp));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(px(3), px(20));
            bp.rightMargin = px(12);
            r.addView(bar, bp);

            TextView label = text(17, Theme.TEXT, true);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setAutoSizeTextTypeUniformWithConfiguration(12, 17, 1, TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            r.addView(label, lp);

            TextView value = text(14, Theme.SUB, false);
            value.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            value.setPadding(px(10), 0, 0, 0);
            value.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.1f);
            r.addView(value, vp);

            r.setOnClickListener(v -> { focus = idx; it.run.run(); refresh(); });
            r.setOnFocusChangeListener((v, has) -> {
                v.setBackground(rowBg(has));
                v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(120).start();
                if (has) focus = idx;
                paintRow(idx, has);
            });
            rows.add(r);
            addView(r);
        }
    }

    /** 行の背景。選んでいる行は白っぽく浮かせ、白い縁を付ける。 */
    private GradientDrawable rowBg(boolean on) {
        GradientDrawable g = Theme.round(on ? 0x1FFFFFFF : 0x14172033, 14 * dp);
        if (on) g.setStroke(Math.round(1.6f * dp), 0xCCFFFFFF);
        return g;
    }

    /** 選んでいる行は文字を純白に、値のある行には左右で変えられる印(‹ ›)を添える。 */
    private void paintRow(int i, boolean on) {
        if (i < 0 || i >= rows.size() || i >= items.size()) return;
        LinearLayout r = rows.get(i);
        Item it = items.get(i);
        ((TextView) r.getChildAt(1)).setTextColor(on ? Color.WHITE : Theme.TEXT);
        TextView value = (TextView) r.getChildAt(2);
        String s = it.value.get();
        if (it.adj && on && !s.isEmpty()) {
            value.setTextColor(Color.WHITE);
            value.setText("‹ " + s + " ›");
        } else {
            value.setTextColor(it.adj ? Theme.ACCENT : Theme.SUB);
            value.setText(s);
        }
        value.setVisibility(s.isEmpty() ? GONE : VISIBLE);
    }

    /** 文字を今の状態に合わせて書き直す。 */
    void refresh() {
        chipLayout.setText(host.layoutText());
        chipLayout.setBackground(Theme.round(Theme.MUTED, 14 * dp));
        chipSide.setText(host.sideText());
        chipSide.setBackground(Theme.round(Theme.ACCENT, 14 * dp));
        boolean a = host.accessOn();
        chipAccess.setText(a ? "ユーザー補助 有効" : "ユーザー補助 無効");
        chipAccess.setBackground(Theme.round(a ? Theme.GREEN : Theme.AMBER, 14 * dp));
        for (int i = 0; i < rows.size() && i < items.size(); i++) {
            ((TextView) rows.get(i).getChildAt(1)).setText(items.get(i).label.get());
            paintRow(i, i == focus && rows.get(i).isFocused());
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
        if (rows.isEmpty()) return;
        focus = Math.max(0, Math.min(rows.size() - 1, i));
        rows.get(focus).requestFocus();
    }

    // ───────── キー(コマンダー) ─────────

    /** 操作盤が出ている間のキー。扱ったら true。 */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) adjust();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) adjust();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down && !rows.isEmpty()) rows.get(focus).performClick();
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                if (!down) back();
                return true;
            default:
                return false;
        }
    }

    /** ダイヤルを回した量(+1 / -1)で、選ぶ行を上下に動かす。 */
    void rotate(int steps) {
        for (int i = 0; i < Math.abs(steps); i++) move(steps > 0 ? 1 : -1);
    }

    private void move(int delta) {
        if (rows.isEmpty()) return;
        int n = rows.size();
        focusRow(((focus + delta) % n + n) % n);
    }

    /** 左右: 選んでいる行が値を持つなら、その場で1つ送る(押したときと同じ切り替え)。 */
    private void adjust() {
        if (focus < 0 || focus >= items.size()) return;
        Item it = items.get(focus);
        if (it.adj) { it.run.run(); refresh(); }
    }

    void back() {
        if (page != MAIN) { host.cancelLearn(); show(MAIN); }
        else host.closePanel();
    }

    // ───────── 並べ方(縦1列) ─────────

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

    private int chipW(TextView c) {
        c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return c.getMeasuredWidth();
    }

    private void layoutChildren(int w, int h, boolean measure) {
        int pad = px(PAD), head = px(HEADER);
        int chipH = px(28), cy = pad + (head - chipH) / 2 - px(4);
        boolean wide = w / dp >= 1000;
        // 見出し: 左に題名、右に状態の札(狭いときは操作側とユーザー補助だけ)
        int x = w - pad;
        TextView[] chips = wide ? new TextView[]{chipAccess, chipSide, chipLayout} : new TextView[]{chipAccess, chipSide};
        chipLayout.setVisibility(wide ? VISIBLE : GONE);
        for (TextView c : chips) {
            int cw = chipW(c);
            place(c, x - cw, cy, x, cy + chipH, measure);
            x -= cw + px(8);
        }
        place(title, pad, cy, Math.max(pad, x - px(8)), cy + chipH, measure);

        int top = pad + head;
        int bottom = h - pad;
        int listW = Math.min(w - pad * 2, px(720)); // 広い画面では中央に寄せ、幅を抑える
        int left = (w - listW) / 2;
        int n = rows.size();
        int gap = px(ROW_GAP);

        if (page == LOG && n > 0) {
            // 記録: 上に本文、下に3つの行
            int rowH = clampRowH((bottom - top), Math.max(1, n));
            int listH = n * rowH + (n - 1) * gap;
            int logBottom = bottom - listH - gap;
            place(logText, left, top, left + listW, Math.max(top, logBottom), measure);
            if (!measure && logText.getLineHeight() > 0) {
                int fit = Math.max(1, (logBottom - top - px(16)) / logText.getLineHeight());
                if (fit != logLines) { logLines = fit; post(this::refresh); }
            }
            int y = bottom - listH;
            for (int i = 0; i < n; i++) {
                place(rows.get(i), left, y, left + listW, y + rowH, measure);
                y += rowH + gap;
            }
            return;
        }

        if (n == 0) return;
        int avail = bottom - top;
        int rowH = clampRowH(avail, n);
        int listH = n * rowH + (n - 1) * gap;
        int y = top + Math.max(0, (avail - listH) / 2); // 行数が少なければ中央に
        for (int i = 0; i < n; i++) {
            place(rows.get(i), left, y, left + listW, y + rowH, measure);
            y += rowH + gap;
        }
    }

    /** 画面に収まる1行の高さ(px)。範囲 ROW_MIN〜ROW_MAX に収める。 */
    private int clampRowH(int avail, int n) {
        int gap = px(ROW_GAP);
        int h = (avail - gap * (n - 1)) / Math.max(1, n);
        return Math.max(px(ROW_MIN), Math.min(px(ROW_MAX), h));
    }
}
