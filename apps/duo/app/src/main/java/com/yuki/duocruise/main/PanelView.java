package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Color;
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
 * 操作盤。2画面の上に重ねて出す。ダイヤルだけで全部操作できる。
 * ・幅 1480 では横長の格子、740 では2列の格子(PanelGrid が計算)。縦にはみ出す画面は作らない
 * ・回す(左右)で全部のボタンを横の順に一巡、倒す(上下)で縦の順に一巡、押す(決定)で実行、戻るで1つ前へ
 * ・ページは4つ: 主(よく使う操作)/ ボタンの割り当て / 記録 / 設定・ほかのアプリへ
 *   (Duo Cruise をホームアプリにしても、OTTO のホーム・Android の設定へ必ず逃げられる)
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
        String layoutText();
        String sideText();
        KeyMap keyMap();
        void startLearn(KeyMap.Action a);
        void cancelLearn();
        KeyMap.Action learning();
        void resetKeys();
    }

    static final int MAIN = 0, KEYS = 1, LOG = 2, SYS = 3;

    private interface Text { String get(); }

    private static final class Item {
        final Text label, sub;
        final Runnable run;
        final int color;
        Item(Text label, Text sub, int color, Runnable run) { this.label = label; this.sub = sub; this.color = color; this.run = run; }
    }

    private final Host host;
    private final float dp;
    private final TextView title, chipLayout, chipSide, chipAccess, logText;
    private final List<Item> items = new ArrayList<>();
    private final List<LinearLayout> tiles = new ArrayList<>();
    private int page = MAIN, focus = 0, logPage = 0, logLines = 8;
    private PanelGrid grid;

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
            title.setText("Duo Cruise");
            items.add(new Item(() -> "操作側を切り替え", host::sideText, Theme.ACCENT, host::switchSide));
            items.add(new Item(() -> "左右を入れ替え", host::layoutText, Theme.MUTED, host::swap));
            items.add(new Item(() -> "2画面に戻る", () -> "操作盤を閉じる", Theme.MUTED, host::closePanel));
            items.add(new Item(() -> "ボタンの割り当て", () -> "長押し・ホーム・タスク", Theme.MUTED, () -> show(KEYS)));
            items.add(new Item(() -> host.carOn() ? "CarPlay を止める" : "CarPlay を開始",
                    () -> host.carOn() ? "チップ・Wi-Fi・Bluetooth を手放す" : "MultiPlay を使っていない時だけ",
                    Theme.MUTED, host::toggleCar));
            items.add(new Item(() -> "記録を見る", () -> "状態・エラー", Theme.MUTED, () -> show(LOG)));
            items.add(new Item(() -> "OTTO のホームを開く", host::otherHomeName, Theme.MUTED, host::goHome));
            items.add(new Item(() -> "設定・その他", () -> "Android の設定・ホームアプリ", Theme.MUTED, () -> show(SYS)));
        } else if (p == KEYS) {
            title.setText("ボタンの割り当て");
            for (KeyMap.Action a : KeyMap.Action.values()) {
                items.add(new Item(() -> a.label,
                        () -> host.learning() == a ? "▶ 割り当てたいボタンを押す(短く/長く)" : host.keyMap().describe(a),
                        Theme.MUTED, () -> { host.startLearn(a); refresh(); }));
            }
            items.add(new Item(() -> "ユーザー補助の設定", () -> host.accessOn() ? "オン(ホーム・タスクを受けられる)" : "オフ: ホーム・タスクは受けられません",
                    host.accessOn() ? Theme.MUTED : Theme.AMBER, host::openAccessibility));
            items.add(new Item(() -> "初期値に戻す", () -> "仮の割り当てに戻す", Theme.MUTED, () -> { host.resetKeys(); refresh(); }));
            items.add(new Item(() -> "戻る", () -> "操作盤へ", Theme.MUTED, () -> { host.cancelLearn(); show(MAIN); }));
        } else if (p == SYS) {
            title.setText("設定・その他");
            items.add(new Item(() -> "Android の設定を開く", () -> "", Theme.MUTED, host::openSettings));
            items.add(new Item(() -> "ホームアプリを選ぶ", () -> host.isDefaultHome() ? "いまは Duo Cruise" : "いまは " + host.otherHomeName(), Theme.MUTED, host::chooseHome));
            items.add(new Item(() -> "操作盤の場所", () -> host.panelHalf() ? "いまは 操作側の半分" : "いまは 画面全体", Theme.MUTED, host::togglePanelHalf));
            items.add(new Item(() -> "ユーザー補助の設定", () -> host.accessOn() ? "オン" : "オフ: ホーム・タスクは受けられません",
                    host.accessOn() ? Theme.MUTED : Theme.AMBER, host::openAccessibility));
            items.add(new Item(() -> "OTTO のホームを開く", host::otherHomeName, Theme.MUTED, host::goHome));
            items.add(new Item(() -> "戻る", () -> "操作盤へ", Theme.MUTED, () -> show(MAIN)));
        } else {
            title.setText("記録");
            logPage = 0;
            items.add(new Item(() -> "新しい方へ", () -> "", Theme.MUTED, () -> { logPage = Math.max(0, logPage - 1); refresh(); }));
            items.add(new Item(() -> "古い方へ", () -> "", Theme.MUTED, () -> { logPage++; refresh(); }));
            items.add(new Item(() -> "戻る", () -> "操作盤へ", Theme.MUTED, () -> show(MAIN)));
        }
        rebuildTiles();
        logText.setVisibility(p == LOG ? VISIBLE : GONE);
        refresh();
        requestLayout();
        post(() -> focusTile(0));
    }

    private void rebuildTiles() {
        for (View t : tiles) removeView(t);
        tiles.clear();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            Item it = items.get(i);
            LinearLayout t = new LinearLayout(getContext());
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setFocusable(true);
            t.setFocusableInTouchMode(false);
            t.setBackground(Theme.tile(it.color, dp));
            t.setPadding(px(10), px(4), px(10), px(4));
            TextView label = text(18, Color.WHITE, true);
            label.setGravity(Gravity.CENTER);
            label.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, TypedValue.COMPLEX_UNIT_SP);
            TextView sub = text(13, Theme.SUB, false);
            sub.setGravity(Gravity.CENTER);
            sub.setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, TypedValue.COMPLEX_UNIT_SP);
            t.addView(label, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(24)));
            t.addView(sub, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(18)));
            t.setOnClickListener(v -> { focus = idx; it.run.run(); refresh(); });
            t.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start();
                if (has) focus = idx;
            });
            tiles.add(t);
            addView(t);
        }
    }

    /** 文字を今の状態に合わせて書き直す。 */
    void refresh() {
        chipLayout.setText(host.layoutText());
        chipLayout.setBackground(Theme.round(Theme.MUTED, 14 * dp));
        chipSide.setText(host.sideText());
        chipSide.setBackground(Theme.round(Theme.ACCENT, 14 * dp));
        boolean a = host.accessOn();
        chipAccess.setText(a ? "ユーザー補助 ON" : "ユーザー補助 OFF");
        chipAccess.setBackground(Theme.round(a ? Theme.GREEN : Theme.AMBER, 14 * dp));
        for (int i = 0; i < tiles.size() && i < items.size(); i++) {
            LinearLayout t = tiles.get(i);
            Item it = items.get(i);
            ((TextView) t.getChildAt(0)).setText(it.label.get());
            String s = it.sub.get();
            TextView sub = (TextView) t.getChildAt(1);
            sub.setText(s);
            sub.setVisibility(s.isEmpty() ? GONE : VISIBLE);
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
            title.setText("記録 " + (logPage + 1) + "/" + (maxPage + 1));
        }
    }

    private void focusTile(int i) {
        if (tiles.isEmpty()) return;
        focus = Math.max(0, Math.min(tiles.size() - 1, i));
        tiles.get(focus).requestFocus();
    }

    // ───────── キー(ダイヤル) ─────────

    /** 操作盤が出ている間のキー。扱ったら true。 */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int dir = -1;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = PanelGrid.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = PanelGrid.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = PanelGrid.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = PanelGrid.UP; break;
            case KeyEvent.KEYCODE_TAB: dir = e.isShiftPressed() ? PanelGrid.PREV : PanelGrid.NEXT; break;
            case KeyEvent.KEYCODE_NAVIGATE_NEXT: dir = PanelGrid.NEXT; break;
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS: dir = PanelGrid.PREV; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down && !tiles.isEmpty()) tiles.get(focus).performClick();
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                if (!down) back();
                return true;
            default: return false;
        }
        if (down) move(dir);
        return true;
    }

    /** ダイヤルを回した量(+1 / -1)で動く。 */
    void rotate(int steps) {
        for (int i = 0; i < Math.abs(steps); i++) move(steps > 0 ? PanelGrid.NEXT : PanelGrid.PREV);
    }

    private void move(int dir) {
        if (grid == null || tiles.isEmpty()) return;
        focusTile(grid.move(focus, dir));
    }

    void back() {
        if (page != MAIN) { host.cancelLearn(); show(MAIN); }
        else host.closePanel();
    }

    // ───────── 並べ方 ─────────

    private int[] logBox(int w, int h) {
        int pad = px(PanelGrid.PAD), head = px(PanelGrid.HEADER), gap = px(PanelGrid.GAP);
        int tileH = px(56);
        int top = pad + head;
        return new int[]{pad, top, w - pad, h - pad - tileH - gap, tileH};
    }

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
        int pad = px(PanelGrid.PAD), head = px(PanelGrid.HEADER);
        int chipH = px(28), cy = pad + (head - chipH) / 2 - px(6);
        boolean wide = w / dp >= PanelGrid.WIDE_DP;
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

        if (page == LOG) {
            int[] box = logBox(w, h);
            place(logText, box[0], box[1], box[2], box[3], measure);
            if (!measure && logText.getLineHeight() > 0) {
                int fit = Math.max(1, (box[3] - box[1] - px(16)) / logText.getLineHeight());
                if (fit != logLines) { logLines = fit; post(this::refresh); }
            }
            int n = tiles.size(), gap = px(PanelGrid.GAP);
            int tw = (box[2] - box[0] - gap * (n - 1)) / Math.max(1, n);
            for (int i = 0; i < n; i++) {
                int x0 = box[0] + i * (tw + gap);
                place(tiles.get(i), x0, box[3] + gap, x0 + tw, box[3] + gap + box[4], measure);
            }
            grid = PanelGrid.compute(Integer.MAX_VALUE / 4, h, n, dp, 0); // 1行の並び(移り方の計算だけに使う)
            return;
        }
        grid = PanelGrid.compute(w, h, tiles.size(), dp, 0);
        for (int i = 0; i < tiles.size(); i++) {
            int[] r = grid.rect(i);
            place(tiles.get(i), r[0], r[1], r[2], r[3], measure);
        }
    }
}
