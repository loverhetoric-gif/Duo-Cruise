package com.yuki.duohome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
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

/**
 * 箱のホーム(ランチャー)。Duo Cruise / CarPlay / Claude / 設定 だけを出し、接続トグルを表に出す。
 * トグルは機器が許す範囲で切り替え、無理なら該当の設定パネルを開く(最低でも1タップで届く)。
 */
public final class HomeActivity extends Activity implements HomeView.Listener {
    private static final String DUO = "com.yuki.duocruise.main";
    private static final String CLAUDE = "com.anthropic.claude";

    private HomeView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshStates();
            view.invalidate();           // 時計も更新
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new HomeView(this, this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override public void onBackPressed() { /* ホームでは戻るで抜けない */ }

    // ───────── コマンダー(物理コントローラ) ─────────
    /** ダイヤルを回す＝選択を動かす(スクロール)。機器により回転はこのイベントで届く。 */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (v == 0) v = -e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) view.move(v > 0 ? 1 : -1);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /** ボタン: 上下左右=選択移動、中央/決定=実行、戻る=無視。音量・電源などはシステムへ。 */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean handled;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
                handled = true; break;
            default:
                return super.dispatchKeyEvent(e); // 音量・電源などはいつもどおりシステムへ
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_LEFT: view.move(-1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_RIGHT: view.move(1); break;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                    if (e.getRepeatCount() == 0) activate(); break;
                default: break; // BACK は無視
            }
        }
        return handled;
    }

    private void activate() {
        int s = view.selected();
        if (s < 4) onTile(s); else onToggle(s - 4);
    }

    // ───────── タイル ─────────
    @Override public void onTile(int i) {
        switch (i) {
            case 0: launch(DUO, null); break;                               // Duo Cruise
            case 1: launch(DUO, "duohome_carplay"); break;                  // CarPlay(DuoCruise を CarPlay で)
            case 2: launchOrStore(CLAUDE); break;                           // Claude
            case 3: open(new Intent(Settings.ACTION_SETTINGS)); break;      // 設定
        }
    }

    private void launch(String pkg, String extra) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { toast(pkg + " が見つかりません"); return; }
        if (extra != null) i.putExtra(extra, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        open(i);
    }

    private void launchOrStore(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); open(i); return; }
        // 未インストール → Play を開く
        try { open(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))); }
        catch (Exception e) { open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg))); }
    }

    // ───────── トグル ─────────
    @Override public void onToggle(int i) {
        switch (i) {
            case 0: openHotspotSettings(); break;
            case 1: openWifiPanel(); break;
            case 2: openBluetoothSettings(); break;
        }
    }

    private void openWifiPanel() {
        try { open(new Intent(Settings.Panel.ACTION_WIFI)); }
        catch (Exception e) { open(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
    }

    private void openHotspotSettings() {
        Intent i = new Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.TetherSettings");
        if (getPackageManager().resolveActivity(i, 0) != null) { open(i); return; }
        try { open(new Intent("android.settings.WIRELESS_SETTINGS")); }
        catch (Exception e) { open(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void openBluetoothSettings() {
        try { open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        catch (Exception e) { open(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void open(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Exception e) { toast("開けませんでした"); }
    }

    // ───────── 状態読み取り ─────────
    private void refreshStates() {
        view.setToggle(0, readApState());
        view.setToggle(1, readWifiState());
        view.setToggle(2, readBtState());
    }

    private int readWifiState() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            return wm.isWifiEnabled() ? 1 : 0;
        } catch (Throwable t) { return -1; }
    }

    /** アクセスポイント(テザリング)の状態。公開APIが無いのでリフレクションで。取れなければ不明(-1)。 */
    private int readApState() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            Method m = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            m.setAccessible(true);
            Object r = m.invoke(wm);
            return (r instanceof Boolean && (Boolean) r) ? 1 : 0;
        } catch (Throwable t) { return -1; }
    }

    private int readBtState() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return -1;
            return a.isEnabled() ? 1 : 0;
        } catch (Throwable t) { return -1; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
