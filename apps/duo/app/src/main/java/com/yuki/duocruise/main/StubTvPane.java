package com.yuki.duocruise.main;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 仮の TV 版 YouTube(D の tv/TvPane が入っていないときの代わり)。前の TvActivity の中身を、枠に入る形にしたもの。
 * D の TvPane と同じ公開の約束(作った時点で読み込み開始、view / onKey / onResume / onPause / destroy / status / command)。
 */
final class StubTvPane implements Pane {
    static final String URL = "https://www.youtube.com/tv";
    static final String TV_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 CrKey/1.0 SmartTV";

    private WebView web;
    private String state = "読み込み前";

    @SuppressLint("SetJavaScriptEnabled")
    StubTvPane(Activity a) {
        web = new WebView(a);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(TV_UA);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public void onPageFinished(WebView v, String url) { state = "読み込み完了"; DuoActivity.stateChanged(); }
        });
        web.loadUrl(URL);
    }

    @Override public View view() { return web; }
    @Override public void start() {}
    @Override public void resume() { if (web != null) web.onResume(); }
    /** 画面が後ろに回っても再生は止めない(D の TvPane と同じ)。ログインを保つため Cookie だけ保存。 */
    @Override public void pause() { CookieManager.getInstance().flush(); }

    @Override public void destroy() {
        CookieManager.getInstance().flush();
        if (web != null) { web.destroy(); web = null; }
    }

    /** 戻るは TV 版 YouTube の「戻る」(Esc)に置き換える。方向・決定・再生系はそのまま。 */
    @Override public boolean onKey(KeyEvent e) {
        if (web == null) return false;
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_BACK: code = KeyEvent.KEYCODE_ESCAPE; break;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_TAB:
                break;
            default: return false;
        }
        KeyEvent k = new KeyEvent(e.getDownTime(), SystemClock.uptimeMillis(), e.getAction(), code, e.getRepeatCount(), e.getMetaState());
        web.requestFocus();
        return web.dispatchKeyEvent(k);
    }

    @Override public String status() { return "仮の部品(" + state + ")"; }
    /** 仮の部品では tv-status と tv-reload だけ。 */
    @Override public String command(String cmd) {
        String c = cmd == null ? "" : cmd.trim();
        if (c.equals("tv-status")) return status();
        if (c.equals("tv-reload") && web != null) { web.reload(); return "読み直します(仮の部品)"; }
        return "仮の TV 部品のため「" + c + "」は未対応";
    }

    @Override public String name() { return "YouTube"; }
    @Override public boolean real() { return false; }
}
