package com.yuki.duocruise.main;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/** 操作盤。2画面の開始・左右の入れ替え・キーの登録・窓の出し方の診断を行う。 */
public class MainActivity extends Activity {
    static final int BG = Color.parseColor("#0E1420");
    static final int CARD = Color.parseColor("#172033");
    static final int ACCENT = Color.parseColor("#4F8CFF");
    static final int RED = Color.parseColor("#FF4F6A");
    static final int GREEN = Color.parseColor("#1F8F6E");
    static final int AMBER = Color.parseColor("#B07A1E");
    static final int MUTED = Color.parseColor("#3A4660");
    static final int TEXT = Color.parseColor("#E8EEF8");
    static final int SUB = Color.parseColor("#8FA0BC");

    private TextView chipAccess, chipLayout, keysLine, diagLine;
    private Button leftBtn, methodBtn;
    private ArrayAdapter<String> adapter;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    /** コマンダーで選んでいるボタンが分かるよう、選択中は白い縁を付ける。 */
    private StateListDrawable buttonBg(int color) {
        GradientDrawable focused = round(color, 12);
        focused.setStroke(dp(3), Color.WHITE);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, round(color, 12));
        return s;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(buttonBg(color));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setFocusable(true);
        b.setSingleLine(true);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(Button... bs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i < bs.length - 1) lp.rightMargin = dp(8);
            r.addView(bs[i], lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        r.setLayoutParams(rp);
        return r;
    }

    private TextView chip() {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));

        // 見出し
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Duo Cruise");
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        chipLayout = chip();
        chipAccess = chip();
        head.addView(chipLayout);
        head.addView(chipAccess);
        root.addView(head);

        // 操作ボタン
        Button start = button("2画面で開始", ACCENT, v -> Layout.start(this));
        Button swap = button("左右を入れ替え", MUTED, v -> { Layout.swap(this); refresh(); });
        Button side = button("操作側を切り替え", MUTED, v -> {
            int now = Store.side(this) == Store.SIDE_LEFT ? Store.SIDE_RIGHT : Store.SIDE_LEFT;
            Store.setSide(this, now);
            Store.log("操作する側: " + (now == Store.SIDE_LEFT ? "左" : "右"));
            PaneService.refreshFrame();
            refresh();
        });
        root.addView(row(start, swap, side));

        leftBtn = button("", MUTED, v -> pickLeftApp());
        Button learnSw = button("切替キーを登録", MUTED, v -> learn(1));
        Button learnSp = button("入替キーを登録", MUTED, v -> learn(2));
        root.addView(row(leftBtn, learnSw, learnSp));

        methodBtn = button("", MUTED, v -> {
            Store.setMethod(this, (Store.method(this) + 1) % Layout.METHOD_NAMES.length);
            Store.log("窓の出し方: " + Layout.METHOD_NAMES[Store.method(this)]);
            refresh();
        });
        Button acc = button("ユーザー補助", MUTED, v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
        });
        Button tvOnly = button("YouTube だけ開く", RED, v ->
                startActivity(new Intent(this, TvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        root.addView(row(methodBtn, acc, tvOnly));

        // 状態
        keysLine = new TextView(this);
        keysLine.setTextColor(SUB);
        keysLine.setTextSize(13);
        keysLine.setPadding(0, dp(8), 0, 0);
        root.addView(keysLine);
        diagLine = new TextView(this);
        diagLine.setTextColor(SUB);
        diagLine.setTextSize(12);
        diagLine.setPadding(0, dp(2), 0, dp(6));
        root.addView(diagLine);

        // 記録
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setBackground(round(CARD, 12));
        list.setPadding(dp(10), dp(4), dp(10), dp(4));
        list.setFocusable(false);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                String s = getItem(pos);
                t.setTextSize(12);
                t.setTypeface(Typeface.MONOSPACE);
                t.setMinHeight(0);
                t.setPadding(dp(2), dp(2), dp(2), dp(2));
                t.setTextColor(s.contains("できません") || s.contains("見つかりません") || s.contains("使えません")
                        ? Color.parseColor("#FF6B6B") : TEXT);
                return t;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        start.requestFocus();
        Store.listener = () -> runOnUiThread(this::refresh);
        Store.log(Layout.diagnose(this));
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onDestroy() { Store.listener = null; super.onDestroy(); }

    private void learn(int which) {
        if (!PaneService.isLive()) {
            Store.log("先に「ユーザー補助」で Duo Cruise をオンにしてください");
            return;
        }
        PaneService.learn = which;
        Store.log((which == 1 ? "切替キー" : "入替キー") + ": 登録したいボタンを1回押してください");
    }

    private void pickLeftApp() {
        final List<Layout.AppItem> apps = Layout.launchableApps(this);
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i).label;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("CarPlay として並べるアプリ")
                .setItems(names, (d, i) -> {
                    Store.setLeftApp(this, apps.get(i).key());
                    Store.log("CarPlay 側のアプリ: " + apps.get(i).label);
                    refresh();
                })
                .show();
    }

    private boolean accessOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(new ComponentName(this, PaneService.class).flattenToString());
    }

    private static String keyName(int k) { return k == 0 ? "未登録" : KeyEvent.keyCodeToString(k).replace("KEYCODE_", ""); }

    private void refresh() {
        boolean a = accessOn();
        chipAccess.setText(a ? "ユーザー補助 ON" : "ユーザー補助 OFF");
        chipAccess.setBackground(round(a ? GREEN : AMBER, 16));
        boolean sw = Store.swapped(this);
        chipLayout.setText(sw ? "左 YouTube ／ 右 CarPlay" : "左 CarPlay ／ 右 YouTube");
        chipLayout.setBackground(round(MUTED, 16));
        String left = Store.leftApp(this);
        if (left.isEmpty()) {
            Layout.AppItem g = Layout.guessCarApp(this);
            if (g != null) { Store.setLeftApp(this, g.key()); left = g.key(); }
        }
        leftBtn.setText("CarPlay: " + Layout.labelOf(this, left));
        methodBtn.setText(Layout.METHOD_NAMES[Store.method(this)]);
        keysLine.setText("切替キー(長押し) " + keyName(Store.switchKey(this))
                + "   ／   入替キー(長押し) " + keyName(Store.swapKey(this))
                + "   ／   操作中 " + (Store.side(this) == Store.SIDE_LEFT ? "左" : "右"));
        diagLine.setText(Layout.diagnose(this));
        adapter.clear();
        adapter.addAll(Store.snapshot());
        adapter.notifyDataSetChanged();
    }
}
