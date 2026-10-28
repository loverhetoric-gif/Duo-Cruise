package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.BlurMaskFilter;
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
 * 車載向け: 行は大きく、ひと目で読める。背景は黒の透過で後ろの映像をうっすら通す。
 * 選んでいる行だけ、Duo Pad と同じ白い発光(ネオン)で縁取って強調する。
 * 操作:
 *  ・ダイヤルを回す / 上下 … 選ぶ行を上下に動かす(選択行は画面中央に送られる)
 *  ・左右 … 値のある行(比率・オン/オフなど)を、その場で切り替える
 *  ・押す … 決定(サブページへ入る・実行・値を1つ送る)
 *  ・戻る … 1つ前へ(主メニューでは閉じる)
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
        final Runnable run;      // 押したとき
        final boolean adj;       // 左右でその場で変えられる値か
        Item(Text label, Text value, Runnable run) { this(label, value, run, false); }
        Item(Text label, Text value, Runnable run, boolean adj) {
            this.label = label; this.value = value; this.run = run; this.adj = adj;
        }
    }

    // 車載向けの大きめの寸法(dp)。
    private static final float ROW_H = 74, ROW_GAP = 14, HEADER = 58, PAD = 18, LIST_MAX = 1180;

    private final Host host;
    private final float dp;
    private final TextView title, chipSide, chipAccess, logText;
    private final ListPane list;
    private final List<Item> items = new ArrayList<>();
    private int page = MAIN, focus = 0, logPage = 0, logLines = 8;

    PanelView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xC2000000); // 黒の透過(後ろの映像をうっすら通す)
        setClickable(true);             // 下の2画面へタッチを通さない
        title = text(24, Theme.TEXT, true);
        chipSide = chip();
        chipAccess = chip();
        logText = text(14, Theme.TEXT, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(px(14), px(10), px(14), px(10));
        list = new ListPane(c);
        addView(title);
        addView(chipSide);
        addView(chipAccess);
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

    private TextView chip() {
        TextView t = text(14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        return t;
    }

    // ───────── ページの中身 ─────────

    void show(int p) {
        page = p;
        items.clear();
        focus = 0;
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
            items.add(new Item(() -> "ホーム画面を開く", host::otherHomeName, host::goHome));
            items.add(new Item(() -> "戻る", () -> "", () -> show(MAIN)));
        } else if (p == SPLIT) {
            title.setText("画面分割とナビ連動");
            items.add(new Item(() -> "通常の分割比", host::splitYtText, () -> { host.cycleSplitYt(); refresh(); }, true));
            items.add(new Item(() -> "ナビ案内中に自動で調整", () -> host.autoSplitOn() ? "オン" : "オフ", () -> { host.toggleAutoSplit(); refresh(); }, true));
            items.add(new Item(() -> "ナビ案内中の分割比", host::navYtText, () -> { host.cycleNavYt(); refresh(); }, true));
            items.add(new Item(() -> "ナビ案内中は地図を右に配置", () -> host.autoSwapOn() ? "オン" : "オフ", () -> { host.toggleAutoSwap(); refresh(); }, true));
            items.add(new Item(() -> "通常の配置に戻るまでの時間", host::navHoldText, () -> { host.cycleNavHold(); refresh(); }, true));
            items.add(new Item(() -> "戻る", () -> "", () -> show(MAIN)));
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

            TextView label = text(22, Theme.TEXT, true);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setAutoSizeTextTypeUniformWithConfiguration(15, 22, 1, TypedValue.COMPLEX_UNIT_SP);
            r.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

            TextView value = text(16, Theme.SUB, false);
            value.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            value.setPadding(px(14), 0, 0, 0);
            value.setAutoSizeTextTypeUniformWithConfiguration(12, 16, 1, TypedValue.COMPLEX_UNIT_SP);
            r.addView(value, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.1f));

            r.setOnClickListener(v -> { focus = idx; it.run.run(); refresh(); });
            r.setOnFocusChangeListener((v, has) -> {
                ((Row) v).setOn(has);
                v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(130).start();
                if (has) { focus = idx; list.scrollToFocus(idx); }
                paintRow(idx, has);
            });
            list.addView(r);
        }
    }

    /** 選んでいる行は文字を純白に、値のある行には左右で変えられる印(‹ ›)を添える。 */
    private void paintRow(int i, boolean on) {
        if (i < 0 || i >= list.getChildCount() || i >= items.size()) return;
        Row r = (Row) list.getChildAt(i);
        Item it = items.get(i);
        ((TextView) r.getChildAt(0)).setTextColor(on ? Color.WHITE : Theme.TEXT);
        TextView value = (TextView) r.getChildAt(1);
        String s = it.value.get();
        if (it.adj && on && !s.isEmpty()) {
            value.setTextColor(Color.WHITE);
            value.setText("‹ " + s + " ›");
        } else {
            value.setTextColor(Theme.SUB);
            value.setText(s);
        }
        value.setVisibility(s.isEmpty() ? GONE : VISIBLE);
    }

    /** 文字を今の状態に合わせて書き直す。 */
    void refresh() {
        chipSide.setText(host.sideText());
        chipSide.setBackground(Theme.round(0x33FFFFFF, 16 * dp));
        boolean a = host.accessOn();
        chipAccess.setText(a ? "ユーザー補助 有効" : "ユーザー補助 無効");
        chipAccess.setBackground(Theme.round(a ? Theme.GREEN : Theme.AMBER, 16 * dp));
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
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) adjust();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down && list.getChildCount() > 0) list.getChildAt(focus).performClick();
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
        int n = list.getChildCount();
        if (n == 0) return;
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

    // ───────── 並べ方 ─────────

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
        c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(px(32), MeasureSpec.EXACTLY));
        return c.getMeasuredWidth();
    }

    private void layoutChildren(int w, int h, boolean measure) {
        int pad = px(PAD), head = px(HEADER);
        int chipH = px(32), cy = pad + (head - chipH) / 2;
        int x = w - pad;
        for (TextView c : new TextView[]{chipAccess, chipSide}) {
            int cw = chipW(c);
            place(c, x - cw, cy, x, cy + chipH, measure);
            x -= cw + px(10);
        }
        place(title, pad, cy, Math.max(pad, x - px(10)), cy + chipH, measure);

        int top = pad + head;
        int bottom = h - pad;
        int listW = Math.min(w - pad * 2, px(LIST_MAX));
        int left = (w - listW) / 2;

        if (page == LOG) {
            int listH = h / 2;                       // 下半分に3つの行、上半分に本文
            int logBottom = bottom - listH;
            logText.setBackground(Theme.round(0x4D000000, 12 * dp));
            place(logText, left, top, left + listW, Math.max(top, logBottom), measure);
            if (!measure && logText.getLineHeight() > 0) {
                int fit = Math.max(1, (logBottom - top - px(20)) / logText.getLineHeight());
                if (fit != logLines) { logLines = fit; post(this::refresh); }
            }
            place(list, left, bottom - listH, left + listW, bottom, measure);
            return;
        }
        place(list, left, top, left + listW, bottom, measure);
    }

    /**
     * 行を縦に積み、選択行が見えるように上下へ送る(スクロール)。はみ出た行は縁で切る。
     */
    private final class ListPane extends ViewGroup {
        private int scroll;

        ListPane(Context c) { super(c); setClipChildren(true); setClipToPadding(true); }

        int pitch() { return px(ROW_H) + px(ROW_GAP); }
        int contentH() { int n = getChildCount(); return n == 0 ? 0 : n * px(ROW_H) + (n - 1) * px(ROW_GAP); }

        /** 選んだ行を、できるだけ中央に送る。 */
        void scrollToFocus(int i) {
            int vh = getHeight();
            if (vh <= 0) return;
            int center = i * pitch() + px(ROW_H) / 2;
            int max = Math.max(0, contentH() - vh);
            int s = Math.max(0, Math.min(max, center - vh / 2));
            if (s != scroll) { scroll = s; requestLayout(); }
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            setMeasuredDimension(w, h);
            int rh = MeasureSpec.makeMeasureSpec(px(ROW_H), MeasureSpec.EXACTLY);
            int rw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(rw, rh);
            // 行数が少なくて全部収まるなら、上端に合わせる
            if (contentH() <= h) scroll = 0;
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, pitch = pitch();
            for (int i = 0; i < getChildCount(); i++) {
                int y = i * pitch - scroll;
                getChildAt(i).layout(0, y, w, y + px(ROW_H));
            }
        }
    }

    /**
     * 1行。黒の透過を敷き、選んでいる間だけ Duo Pad と同じ白い発光で縁取る(ネオン)。
     * 発光は software 描画でしか効かないので、選択時だけ software にする。
     */
    private final class Row extends LinearLayout {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowWide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowMid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float inset = 16 * dp, radius = 20 * dp;
        private boolean on;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(false);
            setWillNotDraw(false);

            hair.setStyle(Paint.Style.STROKE);
            hair.setStrokeWidth(1 * dp);
            hair.setColor(0x24FFFFFF); // 未選択の、ごく薄い縁

            glowWide.setStyle(Paint.Style.STROKE);
            glowWide.setStrokeWidth(8 * dp);
            glowWide.setColor(0x66FFFFFF);
            glowWide.setMaskFilter(new BlurMaskFilter(10 * dp, BlurMaskFilter.Blur.NORMAL));

            glowMid.setStyle(Paint.Style.STROKE);
            glowMid.setStrokeWidth(3 * dp);
            glowMid.setColor(0xB3FFFFFF);
            glowMid.setMaskFilter(new BlurMaskFilter(5 * dp, BlurMaskFilter.Blur.NORMAL));

            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.8f * dp);
            edge.setColor(0xFFFFFFFF);
        }

        void setOn(boolean b) {
            if (on == b) return;
            on = b;
            setLayerType(b ? LAYER_TYPE_SOFTWARE : LAYER_TYPE_NONE, null);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            fill.setColor(on ? 0x80000000 : 0x59000000); // 黒の透過(選択時は少し濃く)
            cv.drawRoundRect(box, radius, radius, fill);
            if (on) {
                cv.drawRoundRect(box, radius, radius, glowWide);
                cv.drawRoundRect(box, radius, radius, glowMid);
                cv.drawRoundRect(box, radius, radius, edge);
            } else {
                cv.drawRoundRect(box, radius, radius, hair);
            }
        }
    }
}
