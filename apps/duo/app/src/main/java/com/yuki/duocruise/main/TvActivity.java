package com.yuki.duocruise.main;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * TV 版 YouTube を出す窓。TV として開くので、アカウント選択・TV コードでのリンク・
 * iPhone からの送信(キャスト)を、TV と同じように受けられる。
 */
public class TvActivity extends Activity {
    static final String URL = "https://www.youtube.com/tv";
    /** YouTube に TV として見せるための名乗り。 */
    static final String TV_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 CrKey/1.0 SmartTV";

    private static volatile TvActivity live;
    private WebView web;

    static boolean isLive() { return live != null; }

    static void closeIfOpen() {
        TvActivity a = live;
        if (a != null) a.runOnUiThread(a::finishAndRemoveTask);
    }

    /** 操作側が YouTube のとき、コマンダーのキーをここへ届ける。届けたら true。 */
    static boolean deliver(KeyEvent e) {
        TvActivity a = live;
        if (a == null || a.web == null) return false;
        final KeyEvent k = translate(e);
        if (k == null) return false;
        a.runOnUiThread(() -> { a.web.requestFocus(); a.web.dispatchKeyEvent(k); });
        return true;
    }

    /** 戻るキーは、TV 版 YouTube の「戻る」(Esc)に置き換える。それ以外の操作キーはそのまま。 */
    private static KeyEvent translate(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                code = KeyEvent.KEYCODE_ESCAPE;
                break;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_TAB:
                break;
            default:
                return null;
        }
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(e.getDownTime(), now, e.getAction(), code, e.getRepeatCount(), e.getMetaState());
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(TV_UA);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public void onPageFinished(WebView v, String url) { Store.log("TV版YouTube 読み込み完了"); }
        });
        setContentView(web);
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        if (b != null) web.restoreState(b); else web.loadUrl(URL);
        live = this;
        Store.log("TV版YouTube の窓を開きました");
        PaneService.refreshFrame();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // この窓に直接届いたキー(タッチ・リモコンなど)も、同じ置き換えで YouTube に渡す
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            KeyEvent k = translate(e);
            if (k != null) return web.dispatchKeyEvent(k);
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }

    @Override protected void onDestroy() {
        if (live == this) live = null;
        PaneService.refreshFrame();
        CookieManager.getInstance().flush();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
