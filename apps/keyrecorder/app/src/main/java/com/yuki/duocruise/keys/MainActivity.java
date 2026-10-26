package com.yuki.duocruise.keys;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
    static final int BG = Color.parseColor("#0E1420");
    static final int CARD = Color.parseColor("#172033");
    static final int ACCENT = Color.parseColor("#4F8CFF");
    static final int OK = Color.parseColor("#35D6A4");
    static final int WARN = Color.parseColor("#FFB547");
    static final int TEXT = Color.parseColor("#E8EEF8");
    static final int SUB = Color.parseColor("#8FA0BC");

    private TextView status, count;
    private ArrayAdapter<String> adapter;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color, 14));
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        KeyLog.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        // 見出しと状態
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Duo Cruise キー記録");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        status = new TextView(this);
        status.setTextSize(16);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Color.WHITE);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        head.addView(status);
        root.addView(head);

        TextView help = new TextView(this);
        help.setText("車のコマンダーやボタンを押すと、届いたキーがここに出ます。どのアプリが前面でも記録します。");
        help.setTextColor(SUB);
        help.setTextSize(14);
        help.setPadding(0, dp(6), 0, dp(10));
        root.addView(help);

        // 操作ボタン
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button open = button("ユーザー補助を開く", ACCENT, v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
        });
        Button clear = button("記録を消す", Color.parseColor("#3A4660"), v -> KeyLog.clear());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(10);
        row.addView(open, lp);
        row.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        count = new TextView(this);
        count.setTextColor(SUB);
        count.setTextSize(13);
        count.setPadding(0, dp(10), 0, dp(6));
        root.addView(count);

        // 記録一覧
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setBackground(round(CARD, 14));
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        list.setClipToPadding(false);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                String s = getItem(pos);
                t.setTextSize(13);
                t.setTypeface(Typeface.MONOSPACE);
                t.setPadding(dp(4), dp(4), dp(4), dp(4));
                t.setTextColor(s.contains("[前面]") ? WARN : s.contains("[サービス]") ? OK : s.contains("押す") ? TEXT : SUB);
                return t;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        KeyLog.setListener(this::refresh);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onDestroy() { KeyLog.setListener(null); super.onDestroy(); }

    private boolean serviceEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String me = new ComponentName(this, KeyRecorderService.class).flattenToString();
        return s.contains(me) || s.contains(getPackageName() + "/.KeyRecorderService");
    }

    private void refresh() {
        boolean on = serviceEnabled();
        status.setText(on ? "記録中(全体)" : "未設定(この画面のみ)");
        status.setBackground(round(on ? Color.parseColor("#1F8F6E") : Color.parseColor("#B07A1E"), 20));
        List<String> snap = KeyLog.snapshot();
        adapter.clear();
        adapter.addAll(snap);
        adapter.notifyDataSetChanged();
        count.setText(snap.size() + " 件  /  保存先: " + KeyLog.filePath());
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        KeyLog.add(KeyLog.describe(e, "画面"));
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE) KeyLog.add(KeyLog.describe(e, "画面"));
        return super.onGenericMotionEvent(e);
    }
}
