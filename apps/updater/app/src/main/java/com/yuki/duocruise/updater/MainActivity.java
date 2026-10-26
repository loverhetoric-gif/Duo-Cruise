package com.yuki.duocruise.updater;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
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

    private TextView chipInstall, chipAccess, versionLine;
    private ArrayAdapter<String> adapter;
    private Button checkBtn;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color, 14));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        b.setOnClickListener(l);
        return b;
    }

    private TextView chip() {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        UpdLog.init(this);
        CheckJobService.schedule(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Duo Cruise 更新");
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        chipInstall = chip();
        chipAccess = chip();
        head.addView(chipInstall);
        head.addView(chipAccess);
        root.addView(head);

        versionLine = new TextView(this);
        versionLine.setTextColor(SUB);
        versionLine.setTextSize(13);
        versionLine.setPadding(0, dp(4), 0, dp(8));
        root.addView(versionLine);

        LinearLayout row = new LinearLayout(this);
        checkBtn = button("今すぐ確認して更新", ACCENT, v -> runCheck(false));
        Button all = button("未導入も入れる", Color.parseColor("#3A4660"), v -> runCheck(true));
        Button perm = button("インストール許可", Color.parseColor("#3A4660"), v -> openInstallPermission());
        Button acc = button("ユーザー補助", Color.parseColor("#3A4660"), v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
        });
        for (Button x : new Button[]{checkBtn, all, perm, acc}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = dp(8);
            row.addView(x, lp);
        }
        root.addView(row);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setBackground(round(CARD, 14));
        list.setPadding(dp(10), dp(6), dp(10), dp(6));
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                String s = getItem(pos);
                t.setTextSize(13);
                t.setTypeface(Typeface.MONOSPACE);
                t.setPadding(dp(4), dp(3), dp(4), dp(3));
                t.setTextColor(s.contains("失敗") || s.contains("不一致") ? Color.parseColor("#FF6B6B")
                        : s.contains("成功") || s.contains("最新") ? OK : s.contains("→") ? WARN : TEXT);
                return t;
            }
        };
        list.setAdapter(adapter);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        llp.topMargin = dp(10);
        root.addView(list, llp);

        setContentView(root);
        UpdLog.listener = () -> runOnUiThread(this::refresh);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        runCheck(false);
    }

    @Override protected void onDestroy() { UpdLog.listener = null; super.onDestroy(); }

    private void runCheck(boolean includeMissing) {
        checkBtn.setEnabled(false);
        new Thread(() -> {
            UpdateEngine.checkAndInstall(getApplicationContext(), includeMissing);
            runOnUiThread(() -> { checkBtn.setEnabled(true); refresh(); });
        }).start();
    }

    private void openInstallPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private boolean accessOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(new ComponentName(this, ConfirmClickService.class).flattenToString());
    }

    private void refresh() {
        boolean can = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        chipInstall.setText(can ? "インストール許可 済" : "インストール許可 未");
        chipInstall.setBackground(round(can ? Color.parseColor("#1F8F6E") : Color.parseColor("#B07A1E"), 20));
        boolean a = accessOn();
        chipAccess.setText(a ? "自動確認 ON" : "自動確認 OFF");
        chipAccess.setBackground(round(a ? Color.parseColor("#1F8F6E") : Color.parseColor("#B07A1E"), 20));
        versionLine.setText("このアプリ v" + UpdateEngine.installedVersion(this, getPackageName())
                + "   /   airplay v" + UpdateEngine.installedVersion(this, "com.yuki.duocruise.airplay")
                + "   /   キー記録 v" + UpdateEngine.installedVersion(this, "com.yuki.duocruise.keys")
                + "   (15分ごとに自動で確認)");
        List<String> snap = UpdLog.snapshot();
        adapter.clear();
        adapter.addAll(snap);
        adapter.notifyDataSetChanged();
    }
}
