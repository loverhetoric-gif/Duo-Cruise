package com.yuki.duocruise.main.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDrm;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 右(または左)の枠に置く TV 版 YouTube(youtube.com/tv を WebView で TV として開く)。
 *
 * <p>約束の API:{@code TvPane(Activity)}、{@link #view()}、{@link #onKey(KeyEvent)}、{@link #onResume()}、
 * {@link #onPause()}、{@link #destroy()}、{@link #status()}。追加で {@link #command(String)}(記録シートの指示用)。
 *
 * <p>iPhone の YouTube の送信ボタンに「Duo Cruise」を出すため、{@link DialServer} を起動・停止する。
 */
public final class TvPane {
    public static final String TV_URL = "https://www.youtube.com/tv";
    public static final String FRIENDLY_NAME = "Duo Cruise";

    /** YouTube に TV として見せる名乗り(3種)。どれが通るかは実機で決める。 */
    static final String[] UA_NAMES = {"Tizen", "Cobalt", "AndroidTV"};

    private static final long COOKIE_FLUSH_MS = 30_000;
    private static final long POLL_MS = 5_000;

    private final Activity act;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private volatile DialServer dial;
    private WifiManager.MulticastLock multicastLock;

    private int uaIndex;
    private int uaAutoTries;
    private String lastUrl = "";
    private String loadState = "未読込";
    private String probeJava = "";
    private String probeWeb = "";
    private String playState = "";
    private boolean playing;
    private long lastQualityLog;
    private boolean destroyed;
    /** CarPlay の音(ナビなど)に合わせて下げている音量。1＝そのまま。 */
    private float duck = 1f;

    private final AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus, pausedForFocus;

    public TvPane(Activity activity) {
        this.act = activity;
        this.app = activity.getApplicationContext();
        this.prefs = app.getSharedPreferences("duo_tv", Context.MODE_PRIVATE);
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.uaIndex = Math.max(0, Math.min(UA_NAMES.length - 1, prefs.getInt("ua", 0)));
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        buildWebView();
        web.loadUrl(TV_URL);
        TvLog.state(app, "TV 枠を作成: 名乗り=" + UA_NAMES[uaIndex] + " WebView=" + webViewVersion());
        main.postDelayed(cookieFlusher, COOKIE_FLUSH_MS);
        main.postDelayed(poller, POLL_MS);
        new Thread(this::probeJavaSide, "duo-tv-probe").start();
        startDial();
    }

    // ---------------- 約束の API ----------------

    public View view() { return root; }

    /** コマンダーのキーを YouTube へ。届けたら true。戻る=Esc、決定=Enter、上下左右=矢印。 */
    public boolean onKey(KeyEvent e) {
        if (web == null || destroyed) return false;
        KeyEvent k = translate(e);
        if (k == null) return false;
        if (!web.hasFocus()) web.requestFocus();
        web.dispatchKeyEvent(k);
        return true;
    }

    /**
     * タッチパッドの iPhone のキーボードで確定した文字を打つ。英数字は1文字ずつキーとして、
     * キーにできない文字(日本語など)はまとめて1つの「文字の出来事」として、ページへ渡す。
     */
    public void typeText(String text) {
        if (web == null || destroyed || text.isEmpty()) return;
        if (!web.hasFocus()) web.requestFocus();
        android.view.KeyCharacterMap map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] keys = map.getEvents(text.toCharArray());
        if (keys != null) { for (KeyEvent k : keys) web.dispatchKeyEvent(k); return; }
        web.dispatchKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), text, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
    }

    /** タッチパッドからの1つのキー(消す・改行など)を、そのままページへ渡す。 */
    public void typeKey(int code) {
        if (web == null || destroyed) return;
        if (!web.hasFocus()) web.requestFocus();
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        web.dispatchKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
    }

    public void onResume() {
        if (web == null) return;
        web.onResume();
        web.resumeTimers();
    }

    /** 画面が後ろに回っても再生は止めない(音を続ける)。ログインが消えないよう Cookie を保存する。 */
    public void onPause() {
        flushCookies();
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        stopDial();
        abandonFocus();
        flushCookies();
        if (web != null) {
            root.removeAllViews();
            web.destroy();
            web = null;
        }
        TvLog.log(app, "TV 枠を閉じました");
    }

    /** 今の状態(1行)。 */
    public String status() {
        return "TV: 名乗り=" + UA_NAMES[uaIndex] + " 読込=" + loadState + " URL=" + shortUrl(lastUrl)
                + (playState.isEmpty() ? "" : " 再生=" + playState)
                + " | " + (dial == null ? "DIAL 未起動" : dial.status())
                + (probeJava.isEmpty() ? "" : " | 端末=" + probeJava)
                + (probeWeb.isEmpty() ? "" : " | ページ=" + probeWeb);
    }

    /**
     * 記録シートの指示(「com.yuki.duocruise.main:tv-…」)を受ける。結果は kind=result でも送る。
     * tv-status / tv-ua(一覧) / tv-ua:0〜2(名乗りを変えて読み直す) / tv-reload / tv-home / tv-probe / tv-dial-restart
     */
    public String command(String cmd) {
        String c = cmd == null ? "" : cmd.trim();
        String r;
        if (c.equals("tv-status")) {
            r = status();
        } else if (c.equals("tv-ua")) {
            StringBuilder b = new StringBuilder("名乗り: 今=" + uaIndex + "(" + UA_NAMES[uaIndex] + ")");
            for (int i = 0; i < UA_NAMES.length; i++) b.append(" / ").append(i).append('=').append(UA_NAMES[i]);
            r = b.toString();
        } else if (c.startsWith("tv-ua:")) {
            try {
                int i = Integer.parseInt(c.substring(6).trim());
                if (i < 0 || i >= UA_NAMES.length) throw new NumberFormatException();
                setUa(i, true);
                uaAutoTries = UA_NAMES.length; // 手で選んだら自動の切り替えはしない
                r = "名乗りを " + UA_NAMES[i] + " にして読み直します";
            } catch (NumberFormatException e) {
                r = "番号は 0〜" + (UA_NAMES.length - 1);
            }
        } else if (c.equals("tv-reload")) {
            if (web != null) web.reload();
            r = "読み直します";
        } else if (c.equals("tv-home")) {
            if (web != null) web.loadUrl(TV_URL);
            r = "TV 版のホームを開きます";
        } else if (c.equals("tv-probe")) {
            probeWebSide();
            new Thread(this::probeJavaSide, "duo-tv-probe").start();
            r = "確認を始めました(結果は state で届きます)";
        } else if (c.equals("tv-dial-restart")) {
            stopDial();
            startDial();
            r = "DIAL を起動し直しました";
        } else {
            r = "知らない指示: " + c;
        }
        TvLog.result(app, c + " → " + r);
        return r;
    }

    // ---------------- WebView ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private void buildWebView() {
        if ((app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web = new WebView(act);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(uaString(uaIndex));
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new Chrome());
        web.setWebViewClient(new Client());
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 描画の処理が落ちたとき、WebView を作り直して開き直す(アプリごと落ちないように)。 */
    private void rebuildWebView(String why) {
        TvLog.error(app, "WebView を作り直します: " + why);
        if (web != null) {
            root.removeView(web);
            try { web.destroy(); } catch (Exception ignored) { }
            web = null;
        }
        if (destroyed) return;
        buildWebView();
        web.loadUrl(TV_URL);
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String scheme = r.getUrl().getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) return false;
            TvLog.log(app, "外のアプリへ飛ぶ読み込みを止めました: " + scheme);
            return true;
        }

        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
            loadState = "読込中";
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (v != web) return;
            lastUrl = url == null ? "" : url;
            boolean onTv = isTvUrl(lastUrl);
            loadState = onTv ? "TV版" : "TV版でない";
            TvLog.state(app, "読み込み完了: 名乗り=" + UA_NAMES[uaIndex] + " URL=" + shortUrl(lastUrl) + (onTv ? "" : " (TV版から外れた)"));
            flushCookies();
            if (onTv) {
                if (prefs.getInt("ua", -1) != uaIndex) prefs.edit().putInt("ua", uaIndex).apply();
                main.postDelayed(TvPane.this::probeWebSide, 4000);
            } else if (isBrowserYouTube(lastUrl) && uaAutoTries < UA_NAMES.length - 1) {
                // ブラウザと判定されて普通の YouTube に飛ばされた → 次の名乗りで試す
                uaAutoTries++;
                int next = (uaIndex + 1) % UA_NAMES.length;
                TvLog.error(app, "TV版から外された(名乗り=" + UA_NAMES[uaIndex] + ")。次は " + UA_NAMES[next] + " で試します");
                setUa(next, true);
            }
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame()) {
                loadState = "失敗";
                TvLog.error(app, "読み込み失敗: " + e.getErrorCode() + " " + e.getDescription() + " URL=" + shortUrl(r.getUrl().toString()));
            }
        }

        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            if (v != web) return true;
            final String why = d.didCrash() ? "クラッシュ" : "メモリ不足で止められた";
            main.post(() -> rebuildWebView(why));
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        /** DRM(Widevine)だけを許可。カメラ・マイク等は断る。 */
        @Override public void onPermissionRequest(PermissionRequest r) {
            List<String> ok = new ArrayList<>();
            for (String res : r.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) ok.add(res);
            }
            if (ok.isEmpty()) {
                r.deny();
                TvLog.log(app, "許可の求めを断りました: " + String.join(",", r.getResources()));
            } else {
                r.grant(ok.toArray(new String[0]));
            }
        }

        /** 全画面の求めは、この枠の中いっぱいに出す。 */
        @Override public void onShowCustomView(View v, CustomViewCallback cb) {
            if (customView != null) { cb.onCustomViewHidden(); return; }
            customView = v;
            customCallback = cb;
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (web != null) web.setVisibility(View.INVISIBLE);
        }

        @Override public void onHideCustomView() {
            if (customView != null) root.removeView(customView);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
            if (web != null) web.setVisibility(View.VISIBLE);
        }

        /** 再生前の灰色の再生ボタン画像を出さない。 */
        @Override public Bitmap getDefaultVideoPoster() {
            Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.BLACK);
            return b;
        }
    }

    // ---------------- 名乗り(UA) ----------------

    private void setUa(int i, boolean reload) {
        uaIndex = i;
        prefs.edit().putInt("ua", i).apply();
        if (web == null) return;
        web.getSettings().setUserAgentString(uaString(i));
        TvLog.state(app, "名乗りを " + UA_NAMES[i] + " に変更");
        if (reload) web.loadUrl(TV_URL);
    }

    static String uaString(int i) {
        switch (i) {
            case 1: // Cobalt(本物の TV が使う実行環境)風
                return "Mozilla/5.0 (Linux armeabi-v7a; Android 13) Cobalt/25.lts.1.1034943-gold (unlike Gecko) v8/8.8.278.17-jit gles Starboard/15, "
                        + "Ottocast_SM6225_2025/13 (Ottocast, P3Pro, Wireless)";
            case 2: { // Android TV の Chrome 風(前回の CrKey は Chromecast と誤認されるので外した)
                String v = webViewVersion();
                if (v.isEmpty() || v.equals("?")) v = "101.0.4951.61";
                return "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + v + " Safari/537.36";
            }
            default: // Samsung の TV(Tizen)風
                return "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.0 TV Safari/537.36";
        }
    }

    static String webViewVersion() {
        try {
            PackageInfo p = WebView.getCurrentWebViewPackage();
            return p == null ? "?" : p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean isTvUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath() == null ? "" : u.getPath();
            return isYouTubeHost(url) && (path.equals("/tv") || path.startsWith("/tv/"));
        } catch (Exception e) {
            return false;
        }
    }

    /** ブラウザ向けの YouTube(TV 版から飛ばされた先)。同意画面などは含めない。 */
    static boolean isBrowserYouTube(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return "www.youtube.com".equals(h) || "m.youtube.com".equals(h) || "youtube.com".equals(h);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isYouTubeHost(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h != null && (h.equals("youtube.com") || h.endsWith(".youtube.com"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- キー ----------------

    /** 戻る→Esc、決定→Enter。上下左右・メディアキーはそのまま。それ以外は渡さない(null)。 */
    static KeyEvent translate(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                code = KeyEvent.KEYCODE_ESCAPE;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                code = KeyEvent.KEYCODE_ENTER;
                break;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_TAB:
                break;
            default:
                return null;
        }
        return new KeyEvent(e.getDownTime(), SystemClock.uptimeMillis(), e.getAction(), code, e.getRepeatCount(), e.getMetaState());
    }

    // ---------------- Cookie・状態の見回り ----------------

    private void flushCookies() {
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) { }
    }

    private final Runnable cookieFlusher = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            flushCookies();
            main.postDelayed(this, COOKIE_FLUSH_MS);
        }
    };

    private static final String POLL_JS =
            "(function(){var v=document.querySelector('video');if(!v)return '';"
            + "var q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null;"
            + "return JSON.stringify({p:!v.paused&&!v.ended,w:v.videoWidth,h:v.videoHeight,"
            + "d:q?q.droppedVideoFrames:-1,f:q?q.totalVideoFrames:-1,m:v.muted});})()";

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (web != null) web.evaluateJavascript(POLL_JS, TvPane.this::onPoll);
            // 動画が切り替わると音量が戻ることがあるので、下げている間は5秒ごとにかけ直す
            if (web != null && duck < 1f) web.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(v&&!window.__duoFade)v.volume=" + duck + ";})()", null);
            main.postDelayed(this, POLL_MS);
        }
    };

    private void onPoll(String raw) {
        try {
            Object v = new JSONTokener(raw).nextValue();
            if (!(v instanceof String) || ((String) v).isEmpty()) return;
            JSONObject o = new JSONObject((String) v);
            boolean p = o.optBoolean("p");
            String res = o.optInt("w") + "x" + o.optInt("h");
            int dropped = o.optInt("d", -1), total = o.optInt("f", -1);
            String now = (p ? "再生中 " : "停止 ") + res + (o.optBoolean("m") ? " 消音" : "");
            boolean changed = !now.equals(playState);
            playState = now;
            if (changed) TvLog.state(app, "再生の状態: " + now);
            long t = SystemClock.elapsedRealtime();
            if (p && total > 0 && t - lastQualityLog > 60_000) {
                lastQualityLog = t;
                TvLog.state(app, String.format(Locale.ROOT, "画質: %s 落ちたコマ %d/%d (%.1f%%)", res, dropped, total, 100.0 * dropped / total));
            }
            if (p != playing) {
                playing = p;
                if (p) requestFocus(); else if (!pausedForFocus) abandonFocus();
            }
        } catch (Exception ignored) {
            // ページ切り替え中など
        }
    }

    // ---------------- 音のフォーカス ----------------

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        switch (change) {
            // 止めない: 再生を始めると WebView 自身が音の主導権を取るため、ここで止めると
            // 「再生して2秒で止まる」を繰り返していた(2026-10-27 車で確認)。
            // ナビ・電話のときの音量は Duo Cruise が自分で下げる(DuoActivity.applyDuck)
            case AudioManager.AUDIOFOCUS_LOSS:
                hasFocus = false;
                pausedForFocus = false;
                TvLog.state(TvPane.this.app, "音の主導権が他へ(止めずに再生を続ける)");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                TvLog.state(TvPane.this.app, "音の主導権が一時的に他へ(止めずに再生を続ける)");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (pausedForFocus) { pausedForFocus = false; playVideo(); TvLog.state(TvPane.this.app, "音が戻ったので再開"); }
                break;
            default:
                break; // 小さくする(ダック)は Android に任せる
        }
    });

    private void requestFocus() {
        if (hasFocus || audio == null) return;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build();
        }
        int r = audio.requestAudioFocus(focusRequest);
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        TvLog.state(app, "音のフォーカス: " + (hasFocus ? "得た" : "得られない(" + r + ")"));
    }

    private void abandonFocus() {
        if (!hasFocus || audio == null || focusRequest == null) return;
        audio.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
    }

    /**
     * YouTube の音量を level へ、約0.3秒かけてなめらかに変える(Apple の機器でナビが割り込むときと同じ動き)。
     * 再生は止めない。端末の音量ではなく、ページの動画の音量だけを変える。
     */
    public void setDuck(float level) {
        level = Math.max(0f, Math.min(1f, level));
        if (level == duck) return;
        duck = level;
        TvLog.state(app, level >= 1f ? "音量を戻した" : "CarPlay の音のため音量を " + Math.round(level * 100) + "% に下げた");
        if (web == null) return;
        web.evaluateJavascript("(function(t){var v=document.querySelector('video');if(!v)return;"
                + "clearInterval(window.__duoFade);var s=v.volume,n=0;"
                + "window.__duoFade=setInterval(function(){n++;v.volume=s+(t-s)*Math.min(1,n/10);"
                + "if(n>=10){clearInterval(window.__duoFade);window.__duoFade=0;}},30);})(" + level + ")", null);
    }

    private void pauseVideo() {
        if (web != null) web.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.pause();})()", null);
    }

    private void playVideo() {
        if (web != null) web.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.play();})()", null);
    }

    // ---------------- 再生能力の確認(VP9/AV1・Widevine) ----------------

    private static final String PROBE_JS =
            "(function(){var r={};window.__duoProbe=r;"
            + "try{var M=window.MediaSource;"
            + "r.vp9=!!(M&&M.isTypeSupported('video/webm; codecs=\"vp9\"'));"
            + "r.av1=!!(M&&M.isTypeSupported('video/mp4; codecs=\"av01.0.05M.08\"'));"
            + "r.h264=!!(M&&M.isTypeSupported('video/mp4; codecs=\"avc1.4d401f\"'));"
            + "r.opus=!!(M&&M.isTypeSupported('audio/webm; codecs=\"opus\"'));}catch(e){r.err=String(e);}"
            + "function wv(rob,key){try{navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc'],"
            + "videoCapabilities:[{contentType:'video/mp4; codecs=\"avc1.4d401f\"',robustness:rob}]}])"
            + ".then(function(){r[key]=true;},function(e){r[key]=false;});}catch(e){r[key]='err';}}"
            + "wv('HW_SECURE_ALL','wvHw');wv('SW_SECURE_CRYPTO','wvSw');"
            + "return 'ok';})()";

    private void probeWebSide() {
        if (web == null || destroyed) return;
        web.evaluateJavascript(PROBE_JS, null);
        main.postDelayed(() -> {
            if (web == null) return;
            web.evaluateJavascript("JSON.stringify(window.__duoProbe||{})", raw -> {
                try {
                    Object v = new JSONTokener(raw).nextValue();
                    JSONObject o = new JSONObject(v instanceof String ? (String) v : "{}");
                    probeWeb = "VP9=" + yn(o.opt("vp9")) + " AV1=" + yn(o.opt("av1")) + " H264=" + yn(o.opt("h264"))
                            + " Opus=" + yn(o.opt("opus")) + " Widevine(HW)=" + yn(o.opt("wvHw")) + " Widevine(SW)=" + yn(o.opt("wvSw"))
                            + (o.has("err") ? " 誤り=" + o.optString("err") : "");
                    TvLog.state(app, "ページから見た再生能力: " + probeWeb);
                } catch (Exception e) {
                    TvLog.error(app, "再生能力の確認に失敗: " + e);
                }
            });
        }, 3000);
    }

    private static String yn(Object o) {
        if (o == null) return "?";
        if (Boolean.TRUE.equals(o)) return "可";
        if (Boolean.FALSE.equals(o)) return "不可";
        return String.valueOf(o);
    }

    /** 端末側:ハード再生の有無と Widevine のレベル。 */
    private void probeJavaSide() {
        StringBuilder b = new StringBuilder();
        b.append("VP9=").append(decoders("video/x-vnd.on2.vp9"));
        b.append(" AV1=").append(decoders("video/av01"));
        b.append(" H264=").append(decoders("video/avc"));
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL));
            b.append(" Widevine=").append(drm.getPropertyString("securityLevel"))
                    .append("/").append(drm.getPropertyString("version"));
        } catch (Exception e) {
            b.append(" Widevine=なし(").append(e.getClass().getSimpleName()).append(")");
        } finally {
            if (drm != null) try { drm.close(); } catch (Exception ignored) { }
        }
        b.append(" WebView=").append(webViewVersion());
        probeJava = b.toString();
        TvLog.state(app, "端末の再生能力: " + probeJava);
    }

    private static String decoders(String mime) {
        boolean hw = false, sw = false;
        try {
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (i.isEncoder()) continue;
                for (String t : i.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(mime)) continue;
                    boolean isHw = Build.VERSION.SDK_INT >= 29 ? i.isHardwareAccelerated() : !i.getName().startsWith("c2.android") && !i.getName().startsWith("OMX.google");
                    if (isHw) hw = true; else sw = true;
                }
            }
        } catch (Exception e) {
            return "?";
        }
        return hw ? "ハード" : sw ? "ソフトのみ" : "なし";
    }

    // ---------------- DIAL(iPhone の送信ボタンに出る) ----------------

    private void startDial() {
        new Thread(() -> {
            try {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wm != null && multicastLock == null) {
                    multicastLock = wm.createMulticastLock("duo-dial");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }
            } catch (SecurityException e) {
                TvLog.error(app, "マルチキャストの許可がない(CHANGE_WIFI_MULTICAST_STATE)。自動で見つからない恐れ");
            } catch (Exception e) {
                TvLog.error(app, "マルチキャストの準備に失敗: " + e);
            }
            String id = prefs.getString("dialUuid", null);
            if (id == null) { id = UUID.randomUUID().toString(); prefs.edit().putString("dialUuid", id).apply(); }
            DialServer d = new DialServer(FRIENDLY_NAME, id, new DialServer.Listener() {
                @Override public void onLaunch(String query, String from) { main.post(() -> launchFromPhone(query, from)); }
                @Override public void onStop(String from) { main.post(() -> { if (web != null) web.loadUrl(TV_URL); }); }
                @Override public void log(String line) { TvLog.log(app, line); }
            });
            try {
                d.start();
                dial = d;
                if (destroyed) stopDial();
            } catch (Exception e) {
                TvLog.error(app, "DIAL を起動できない: " + e);
            }
        }, "duo-dial-start").start();
    }

    private void stopDial() {
        DialServer d = dial;
        dial = null;
        if (d != null) new Thread(d::stop, "duo-dial-stop").start();
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception ignored) { }
        multicastLock = null;
    }

    /** iPhone から送られてきた依頼(pairingCode など)で TV 版を開き直す。 */
    private void launchFromPhone(String query, String from) {
        if (web == null || destroyed) return;
        DialServer d = dial;
        String url = TV_URL + (query.isEmpty() ? "" : "?" + query);
        if (d != null && d.httpPort() > 0) {
            url += (query.isEmpty() ? "?" : "&") + "additionalDataUrl="
                    + Uri.encode("http://localhost:" + d.httpPort() + "/apps/YouTube/dial_data");
        }
        TvLog.state(app, "iPhone から送信を受けました: " + from + " → TV版を開き直します");
        web.loadUrl(url);
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        // pairingCode 等の値は記録に出さない(項目名だけ)
        int q = u.indexOf('?');
        if (q < 0) return u;
        StringBuilder b = new StringBuilder(u.substring(0, q)).append("?");
        for (String part : u.substring(q + 1).split("&")) {
            int i = part.indexOf('=');
            b.append(i < 0 ? part : part.substring(0, i)).append("&");
        }
        return b.substring(0, b.length() - 1);
    }
}
