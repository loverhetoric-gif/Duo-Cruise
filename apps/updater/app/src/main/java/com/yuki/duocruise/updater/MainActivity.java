package com.yuki.duocruise.updater;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * 更新アプリの画面。ダイヤル(上下左右・決定・戻る)だけで全部操作できるよう、
 * 操作はすべて3×3のタイル(ボタン)にし、選択中のタイルは白い枠で示す。
 * 横長(1480×416)では左に操作・右に記録、半分(740×416)では上に操作・下に記録。
 */
public class MainActivity extends Activity {
    static final int BG = Color.parseColor("#0E1420");
    static final int CARD = Color.parseColor("#172033");
    static final int ACCENT = Color.parseColor("#4F8CFF");
    static final int OK = Color.parseColor("#35D6A4");
    static final int WARN = Color.parseColor("#FFB547");
    static final int TEXT = Color.parseColor("#E8EEF8");
    static final int SUB = Color.parseColor("#8FA0BC");
    static final int GREEN = Color.parseColor("#1F8F6E");
    static final int AMBER = Color.parseColor("#B07A1E");
    static final int GREY = Color.parseColor("#3A4660");

    /** 状態の報告用:この画面の大きさとインセット(最後に表示したとき)。 */
    static volatile String lastLayout;

    private TextView versionLine;
    private ArrayAdapter<String> adapter;
    private Button checkBtn, allBtn, rollbackBtn, installBtn, selfA11yBtn, keysBtn, mainBtn, sendBtn;
    private Switch testSwitch;
    private long rollbackArmedUntil = 0;
    private boolean busy = false;
    private boolean wide;
    private String insetsText = "";

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int r, boolean ring) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        if (ring) g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    /** 選択中(ダイヤルで選んでいる)は白い枠、押している間は少し明るく。 */
    private StateListDrawable tileBg(int color) {
        StateListDrawable s = new StateListDrawable();
        int light = Color.rgb(Math.min(255, Color.red(color) + 40), Math.min(255, Color.green(color) + 40), Math.min(255, Color.blue(color) + 40));
        s.addState(new int[]{android.R.attr.state_pressed}, round(light, 12, true));
        s.addState(new int[]{android.R.attr.state_focused}, round(color, 12, true));
        s.addState(new int[]{}, round(color, 12, false));
        return s;
    }

    private Button tile(View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(wide ? 15 : 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMaxLines(2);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    private void setTile(Button b, String text, int color, boolean clickable) {
        b.setText(text);
        b.setBackground(tileBg(color));
        // 選択中のタイルが押せなくなっても、ダイヤルの選択位置は失わない
        b.setFocusable(clickable || b.isFocused());
        b.setClickable(clickable);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        UpdLog.init(this);
        CheckJobService.schedule(this);
        build();
        UpdLog.listener = () -> runOnUiThread(this::refresh);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        build();
        refresh();
    }

    private void build() {
        wide = getResources().getConfiguration().screenWidthDp >= 1000;
        int tileH = dp(wide ? 64 : 48);
        int gap = dp(wide ? 8 : 6);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Duo Cruise 更新");
        title.setTextColor(TEXT);
        title.setTextSize(wide ? 22 : 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);

        versionLine = new TextView(this);
        versionLine.setTextColor(SUB);
        versionLine.setTextSize(wide ? 13 : 12);
        versionLine.setMaxLines(wide ? 2 : 1);
        versionLine.setEllipsize(TextUtils.TruncateAt.END);
        versionLine.setPadding(0, dp(2), 0, dp(6));
        panel.addView(versionLine);

        checkBtn = tile(v -> runCheck(false));
        allBtn = tile(v -> runCheck(true));
        rollbackBtn = tile(v -> onRollback());
        testSwitch = new Switch(this);
        testSwitch.setText("テスト版を\n受け取る");
        testSwitch.setTextColor(Color.WHITE);
        testSwitch.setTextSize(wide ? 15 : 13);
        testSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        testSwitch.setPadding(dp(12), 0, dp(10), 0);
        testSwitch.setFocusable(true);
        testSwitch.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        testSwitch.setOnCheckedChangeListener((s, on) -> {
            if (on == UpdateEngine.testChannel(this)) return;
            UpdateEngine.setTestChannel(this, on);
            UpdLog.add("テスト版を受け取る: " + (on ? "オン" : "オフ"));
            refresh();
        });
        installBtn = tile(v -> openInstallPermission());
        selfA11yBtn = tile(v -> openA11y(getPackageName()));
        keysBtn = tile(v -> openA11y(Diag.KEYS));
        mainBtn = tile(v -> openA11y(Diag.MAIN));
        sendBtn = tile(v -> { Remote.kick(); UpdLog.add("記録の送信と指示の確認を、今すぐ行います"); });

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        View[] tiles = {checkBtn, allBtn, rollbackBtn, testSwitch, installBtn, selfA11yBtn, keysBtn, mainBtn, sendBtn};
        for (int i = 0; i < tiles.length; i++) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f));
            lp.width = 0;
            lp.height = tileH;
            lp.setMargins(0, 0, (i % 3 == 2) ? 0 : gap, gap);
            grid.addView(tiles[i], lp);
        }
        panel.addView(grid);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setBackground(round(CARD, 12, false));
        list.setPadding(dp(8), dp(4), dp(8), dp(4));
        list.setFocusable(true);
        list.setSelector(round(Color.parseColor("#2A3A5A"), 6, false));
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                String s = getItem(pos);
                t.setTextSize(12);
                t.setTypeface(Typeface.MONOSPACE);
                t.setMinHeight(0);
                t.setMinimumHeight(0);
                t.setPadding(dp(4), dp(2), dp(4), dp(2));
                t.setTextColor(s.contains("失敗") || s.contains("不一致") ? Color.parseColor("#FF6B6B")
                        : s.contains("成功") || s.contains("最新") ? OK : s.contains("→") ? WARN : TEXT);
                return t;
            }
        };
        list.setAdapter(adapter);

        if (wide) {
            root.addView(panel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.6f));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f);
            llp.leftMargin = dp(12);
            root.addView(list, llp);
        } else {
            root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        root.setOnApplyWindowInsetsListener((v, in) -> {
            insetsText = "インセット 上" + in.getSystemWindowInsetTop() + " 下" + in.getSystemWindowInsetBottom()
                    + " 左" + in.getSystemWindowInsetLeft() + " 右" + in.getSystemWindowInsetRight();
            return in;
        });
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) ->
                lastLayout = (r - l) + "x" + (bt - t) + (wide ? "(横長の並び)" : "(半分の並び)") + "  " + insetsText);
        setContentView(root);
        checkBtn.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        runCheck(false);
    }

    @Override protected void onDestroy() { UpdLog.listener = null; super.onDestroy(); }

    private void runCheck(boolean includeMissing) {
        if (busy) return;
        busy = true;
        refresh();
        new Thread(() -> {
            UpdateEngine.checkAndInstall(getApplicationContext(), includeMissing);
            runOnUiThread(() -> { busy = false; refresh(); });
        }).start();
    }

    /** 押し間違いを防ぐため、2回押しで実行(6秒以内にもう一度)。 */
    private void onRollback() {
        if (busy) return;
        long now = System.currentTimeMillis();
        if (now > rollbackArmedUntil) {
            rollbackArmedUntil = now + 6000;
            UpdLog.add("「1つ前の版に戻す」:6秒以内にもう一度押すと戻します");
            refresh();
            rollbackBtn.postDelayed(this::refresh, 6100);
            return;
        }
        rollbackArmedUntil = 0;
        busy = true;
        refresh();
        new Thread(() -> {
            UpdateEngine.rollback(getApplicationContext());
            runOnUiThread(() -> { busy = false; refresh(); });
        }).start();
    }

    private void openInstallPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private void openA11y(String pkg) {
        ComponentName cn = A11y.find(this, pkg);
        if (cn == null) {
            // ユーザー補助が一覧に出ない=入っていない。手で入れた直後は「制限付き設定」の許可が要ることがある
            if (UpdateEngine.installedVersion(this, pkg) >= 0) A11y.openAppInfo(this, pkg);
            return;
        }
        A11y.open(this, cn);
    }

    /** ユーザー補助のタイル:オンなら緑の表示だけ(押せない)、オフなら押すと設定のその項目が開く。 */
    private void a11yTile(Button b, String name, String pkg) {
        boolean installed = UpdateEngine.installedVersion(this, pkg) >= 0;
        ComponentName cn = installed ? A11y.find(this, pkg) : null;
        if (!installed) setTile(b, name + "\n未導入", GREY, false);
        else if (cn == null) setTile(b, name + "\n補助が見つかりません", AMBER, true);
        else if (A11y.enabled(this, cn)) setTile(b, name + " 補助\nON", GREEN, false);
        else setTile(b, name + " 補助 OFF\n押して設定を開く", AMBER, true);
    }

    private String ver(String pkg) {
        long v = UpdateEngine.installedVersion(this, pkg);
        return v < 0 ? "未導入" : "v" + v;
    }

    private void refresh() {
        if (checkBtn == null) return;
        boolean test = UpdateEngine.testChannel(this);
        versionLine.setText("更新 " + ver(getPackageName()) + "  /  本体 " + ver(Diag.MAIN)
                + "  /  キー記録 " + ver(Diag.KEYS) + "  /  AirPlay " + ver(Diag.AIRPLAY)
                + "   " + (test ? "テスト版も受け取る" : "正式版のみ") + "・15分ごとに自動で確認");

        setTile(checkBtn, busy ? "処理中…" : "今すぐ確認\nして更新", ACCENT, !busy);
        setTile(allBtn, "未導入も\n入れる", GREY, !busy);
        boolean armed = System.currentTimeMillis() < rollbackArmedUntil;
        setTile(rollbackBtn, armed ? "もう一度押すと\n戻します" : "1つ前の版に\n戻す", armed ? Color.parseColor("#B04A4A") : GREY, !busy);

        testSwitch.setChecked(test);
        testSwitch.setBackground(tileBg(test ? Color.parseColor("#6A4FC8") : GREY));

        boolean can = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        setTile(installBtn, can ? "インストール許可\n済" : "インストール許可 未\n押して設定を開く", can ? GREEN : AMBER, true);

        ComponentName self = new ComponentName(this, ConfirmClickService.class);
        boolean selfOn = A11y.enabled(this, self);
        setTile(selfA11yBtn, selfOn ? "自動確認(更新)\nON" : "自動確認 OFF\n押して設定を開く", selfOn ? GREEN : AMBER, true);

        a11yTile(keysBtn, "キー記録", Diag.KEYS);
        a11yTile(mainBtn, "本体", Diag.MAIN);

        String send = !Remote.configured() ? "記録の送信\n受け口が未設定(溜めるだけ)"
                : "記録の送信 待ち" + Remote.pending() + "件\n" + Remote.lastSend;
        setTile(sendBtn, send, Remote.configured() ? GREY : AMBER, Remote.configured());

        List<String> snap = UpdLog.snapshot();
        adapter.clear();
        adapter.addAll(snap);
        adapter.notifyDataSetChanged();
    }
}
