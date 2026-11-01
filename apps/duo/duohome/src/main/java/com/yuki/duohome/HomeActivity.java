package com.yuki.duohome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 箱のホーム(ランチャー)。実アプリのアイコンを均一の器で並べる。
 * 並べる物は「車で使うアプリ＋自作アプリ＋設定」。入っていない物は飛ばす。
 * コマンダー(回転/決定)・Duo Slate・タップで操作。
 */
public final class HomeActivity extends Activity implements HomeView.Listener {
    private static final String DUO = "com.yuki.duocruise.main", CLAUDE = "com.anthropic.claude", SETTINGS = "#settings";

    // 並べる候補(上から順。入っていない物は出さない)。SETTINGS は特別枠。
    private static final String[] CANDIDATES = {
            DUO, "com.google.android.apps.maps", "com.google.android.youtube",
            "com.apple.android.music", "com.netflix.mediaclient", "com.android.chrome",
            CLAUDE, SETTINGS,
    };

    private HomeView view;
    private final List<String> actions = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refreshStates(); view.invalidate(); main.postDelayed(this, 1000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new HomeView(this, this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(view);
        buildItems();
    }

    private void buildItems() {
        PackageManager pm = getPackageManager();
        List<String> labels = new ArrayList<>();
        List<Drawable> icons = new ArrayList<>();
        actions.clear();
        for (String c : CANDIDATES) {
            try {
                if (SETTINGS.equals(c)) {
                    labels.add("設定");
                    icons.add(pm.getApplicationIcon("com.android.settings"));
                    actions.add(SETTINGS);
                } else {
                    if (pm.getLaunchIntentForPackage(c) == null) continue; // 入っていない
                    labels.add(pm.getApplicationLabel(pm.getApplicationInfo(c, 0)).toString());
                    icons.add(pm.getApplicationIcon(c));
                    actions.add(c);
                }
            } catch (Throwable ignored) { }
        }
        view.setItems(labels.toArray(new String[0]), icons.toArray(new Drawable[0]));
    }

    @Override protected void onResume() { super.onResume(); main.removeCallbacks(tick); main.post(tick); }
    @Override protected void onPause() { main.removeCallbacks(tick); super.onPause(); }
    @Override public void onBackPressed() { /* ホームでは抜けない */ }

    // ───────── 起動 ─────────
    @Override public void onSelect(int i) {
        if (i < 0 || i >= actions.size()) return;
        String a = actions.get(i);
        if (SETTINGS.equals(a)) { open(new Intent(Settings.ACTION_SETTINGS)); return; }
        Intent it = getPackageManager().getLaunchIntentForPackage(a);
        if (it == null) { // 未インストール(Claude 等) → Play へ
            try { open(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + a))); }
            catch (Exception e) { open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + a))); }
            return;
        }
        if (DUO.equals(a)) it.putExtra("duohome", true);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        open(it);
    }

    private void open(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Exception e) { toast("開けませんでした"); }
    }

    // ───────── コマンダー / パッド ─────────
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (v == 0) v = -e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) view.move(v > 0 ? 1 : -1);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK: break;
            default: return super.dispatchKeyEvent(e); // 音量・電源はシステムへ
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN: view.move(1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP: view.move(-1); break;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                    if (e.getRepeatCount() == 0) view.activate(); break;
                default: break; // BACK はホームなので無視
            }
        }
        return true;
    }

    // ───────── 接続状態(上部の点) ─────────
    private void refreshStates() {
        view.setToggle(0, readApState());
        view.setToggle(1, readWifiState());
        view.setToggle(2, readBtState());
    }
    private int readWifiState() {
        try { return ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).isWifiEnabled() ? 1 : 0; }
        catch (Throwable t) { return -1; }
    }
    private int readApState() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            Method m = wm.getClass().getDeclaredMethod("isWifiApEnabled"); m.setAccessible(true);
            Object r = m.invoke(wm);
            return (r instanceof Boolean && (Boolean) r) ? 1 : 0;
        } catch (Throwable t) { return -1; }
    }
    private int readBtState() {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            return a == null ? -1 : (a.isEnabled() ? 1 : 0);
        } catch (Throwable t) { return -1; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
