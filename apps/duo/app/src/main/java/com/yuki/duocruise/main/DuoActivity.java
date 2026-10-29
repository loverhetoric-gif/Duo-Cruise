package com.yuki.duocruise.main;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.yuki.duocruise.carplay.Route;

/**
 * Duo Cruise 本体の画面。1つの画面の中に、左右2つの枠(各 740×416)を持つ。
 * ・左＝CarPlay(B の CarPlayPane)、右＝TV 版 YouTube(D の TvPane)。入れ替え中は逆
 * ・入れ替え＝枠の置き直しだけ(YouTube を読み込み直さない)
 * ・操作側の切り替え・枠線・キーの振り分けは、この画面の中で行う(KeyRouter)
 * ・ホーム・タスクは、この画面が前面のときだけユーザー補助(PaneService)が横取りしてここへ渡す
 */
public class DuoActivity extends Activity implements KeyRouter.Sink, PanelView.Host, SplitRoot.Listener, PadInput.Host {

    /** 前面にいるときだけ入る(ユーザー補助がホーム等を渡す先)。 */
    static volatile DuoActivity front;
    /** 作られている間だけ入る(指示ブロードキャストの届け先)。 */
    static volatile DuoActivity live;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Pane car, tv;
    /** CarPlay を動かしているか。開いたら自動で true(本人の指示 2026-10-27「自動で接続は絶対」)。操作盤で止めたら false。 */
    private boolean carOn;
    /** 次につなぎ直すまでの待ち時間(ms)。失敗が続くほど延ばし、つながったら戻す。 */
    private long carRetryMs = CAR_RETRY_MIN_MS;
    private static final long CAR_RETRY_MIN_MS = 10_000, CAR_RETRY_MAX_MS = 300_000, CAR_WATCH_MS = 5_000;
    /** CarPlay の見張り。失敗・切断で止まっていたら、待ってから作り直してつなぎ直す。 */
    private final Runnable carWatch = new Runnable() {
        private long stoppedSince;
        @Override public void run() {
            main.postDelayed(this, CAR_WATCH_MS);
            if (!carOn) { stoppedSince = 0; return; }
            String s = safeStatus(car);
            if (s.startsWith("無線 CarPlay 接続中")) carRetryMs = CAR_RETRY_MIN_MS;
            if (!carStopped(s)) { stoppedSince = 0; return; }
            long now = SystemClock.elapsedRealtime();
            if (stoppedSince == 0) { stoppedSince = now; return; }
            if (now - stoppedSince < carRetryMs) return;
            stoppedSince = 0;
            DuoLog.log("CarPlay をつなぎ直します(" + (carRetryMs / 1000) + " 秒待った。止まった理由: " + s + ")");
            carRetryMs = Math.min(carRetryMs * 2, CAR_RETRY_MAX_MS);
            restartCar();
        }
    };
    private SplitRoot root;
    private PanelView panel;
    private KeyRouter router;
    private KeyMap keyMap;
    private KeyMap.Action learnAction;
    /** iPhone のタッチパッド(Duo Slate)。 */
    private PadInput pad;
    private PadServer padServer;
    private PaneService service;
    private String sizes = "-", insets = "-";
    private GuideView guide;
    private final Runnable learnTimeout = () -> {
        if (learnAction != null) { DuoLog.log("割り当ての登録: 時間切れで取りやめ"); cancelLearn(); }
    };
    private final Runnable sendState = () -> DuoLog.state(stateText());

    // ───────── 一生 ─────────

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        DuoLog.init(this);
        Tx.init(this);
        Crash.install(this);
        // 二重に開かれたら、前の画面を閉じる(CarPlay の Wi-Fi・Bluetooth とタッチパッドの口を取り合わないように)
        DuoActivity old = live;
        if (old != null && old != this && !old.isFinishing()) {
            DuoLog.log("前の Duo Cruise の画面が残っていたので閉じます");
            old.finish();
        }
        live = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        keyMap = Store.keyMap(this);
        router = new KeyRouter(keyMap, Store.longMs(this), this, new KeyRouter.Timer() {
            @Override public Object post(Runnable r, long ms) { main.postDelayed(r, ms); return r; }
            @Override public void cancel(Object t) { if (t != null) main.removeCallbacks((Runnable) t); }
        });

        car = Panes.car(this);
        tv = Panes.tv(this);
        panel = new PanelView(this, this);
        panel.setVisibility(View.GONE);
        root = new SplitRoot(this, this);
        pad = new PadInput(this, this);
        root.cursor = pad.cursorView();
        root.setPanes(car.view(), tv.view(), panel);
        guide = new GuideView(this);
        guide.setVisibility(View.GONE);
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.setBackgroundColor(android.graphics.Color.BLACK); // 起動の地(白/黒)は演出の後ろに隠し、2 画面の地は黒
        stage.addView(root);
        stage.addView(guide);
        boolean first = !Store.guided(this);
        if (b == null) {          // エンジンをかけて開いたとき(作り直しでは出さない)
            SplashView splash = new SplashView(this);
            if (first) guide.prepare();   // 案内の地(演出と同じ色)を先に敷き、演出が消えたら案内が浮かぶ
            stage.addView(splash);
            splash.play(Store.bootSound(this), () -> {
                stage.removeView(splash);
                if (first) guide.reveal(this::guideDone);
            });
        } else if (first) main.post(this::showGuide);
        setContentView(stage);
        setupInsets();
        applyLayout();

        askPermissions();
        // CarPlay は開いたら自動で始め、失敗・切断したら見張りがつなぎ直す
        startCar("開いたので自動で");
        main.postDelayed(carWatch, CAR_WATCH_MS);
        // タッチパッドは、アプリごとにモードを切り替えるルーターに通す
        // (Duo Cruise 前面＝この窓を操作、それ以外＝ユーザー補助で全体を操作)
        PadRouter.get(this).setDuoSink(pad);
        padServer = PadRouter.get(this).server();
        padServer.setState(Store.single(this) ? "s F" : Store.side(this) == SplitGeometry.SIDE_LEFT ? "s L" : "s R");
        try { tv.start(); } catch (Throwable t) { DuoLog.error("YouTube を始められません: " + DuoLog.why(t)); }
        boolean asHome = getIntent() != null && getIntent().hasCategory(Intent.CATEGORY_HOME);
        DuoLog.log((BuildConfig.UNLIMITED ? "Duo Cruise Unlimited Edition" : "Duo Cruise") + " を開きました(版 "
                + DuoLog.version() + (asHome ? "、ホームとして" : "") + ")");
        stateChanged();
    }

    // ───────── 権限(CarPlay: Bluetooth・近くの Wi-Fi 機器・マイク) ─────────

    private static final int REQ_PERMS = 1;
    private final Runnable permWatch = () -> DuoLog.error(
            "権限の許可画面が30秒操作されていません。ダイヤルで押せない・縦に収まらない可能性(タッチで許可するか、設定のアプリ情報から許可)");

    private void askPermissions() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String p : Panes.carPermissions())
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) return;
        DuoLog.log("権限の許可画面を出します: " + missing);
        requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        main.postDelayed(permWatch, 30_000);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        main.removeCallbacks(permWatch);
        StringBuilder b = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < perms.length && i < results.length; i++) {
            boolean ok = results[i] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            any |= ok;
            b.append(perms[i].substring(perms[i].lastIndexOf('.') + 1)).append(ok ? "=許可 " : "=拒否 ");
        }
        if (perms.length == 0) DuoLog.error("権限の許可画面が取り消されました(画面が閉じられた等)");
        else DuoLog.log("権限の結果: " + b.toString().trim());
        if (any) DuoLog.log("CarPlay は操作盤の「CarPlay を開始」で始めます");
    }

    /** 高さは WindowInsets で求める。上のステータスバーは隠し、残りの余白(下の64など)は避ける。 */
    private void setupInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            root.setOnApplyWindowInsetsListener((v, in) -> {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                insets = "余白 左" + i.left + " 上" + i.top + " 右" + i.right + " 下" + i.bottom;
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    /** ホームとして開き直されたとき(ホームキー・起動時)。中身はそのまま、並べ方だけ整える。 */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        boolean home = i != null && i.hasCategory(Intent.CATEGORY_HOME);
        DuoLog.log(home ? "ホームとして開かれました" : "もう一度開かれました");
        // 見た目の確かめ用(adb から): 案内のテストだけを受ける。ほかの指示は署名で守った受け口からだけ
        String t = i == null ? null : i.getStringExtra("routeTest");
        if (t != null && (t.startsWith("route-test") || t.equals("route-end"))) DuoLog.log(routeTest(t));
        if (root != null) root.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        front = this;
        if (getIntent() != null && getIntent().getBooleanExtra("openMenu", false)) {
            getIntent().removeExtra("openMenu");
            main.post(() -> openMenu());
        }
        DuoLog.listener = () -> { if (panel.getVisibility() == View.VISIBLE) panel.refresh(); };
        car.resume();
        tv.resume();
        if (panel.getVisibility() == View.VISIBLE) panel.refresh();
        focusActive();
        stateChanged();
    }

    @Override protected void onPause() {
        if (front == this) front = null;
        car.pause();
        tv.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (front == this) front = null;
        if (live == this) live = null;
        DuoLog.listener = null;
        main.removeCallbacksAndMessages(null);

        try { car.destroy(); } catch (Throwable t) { DuoLog.error("CarPlay の終了で失敗: " + DuoLog.why(t)); }
        try { tv.destroy(); } catch (Throwable t) { DuoLog.error("YouTube の終了で失敗: " + DuoLog.why(t)); }
        PadRouter.get(this).setDuoSink(null);
        super.onDestroy();
    }

    // ───────── キー ─────────

    /** 音量・電源などは、いつもどおりシステムへ。 */
    private static boolean passToSystem(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_BRIGHTNESS_UP: case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                return true;
            default: return false;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        recordKey(e, "画面");
        if (passToSystem(code)) return super.dispatchKeyEvent(e);
        return router.onKey(code, e.getAction(), e.getRepeatCount(), e.getEventTime(), false, e);
    }

    /** ユーザー補助から(ホーム・タスク等)。扱ったら true(システムには渡らない)。 */
    boolean onSystemKey(KeyEvent e, PaneService s) {
        service = s;
        recordKey(e, "補助");
        return router.onKey(e.getKeyCode(), e.getAction(), e.getRepeatCount(), e.getEventTime(), true, e);
    }

    /** ダイヤルが「回転(スクロール)」として届く機器のため。 */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (v == 0) v = -e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            DuoLog.keys("回転 " + v);
            if (guiding()) { guide.rotate(v > 0 ? 1 : v < 0 ? -1 : 0); return true; }
            if (panel.getVisibility() == View.VISIBLE) {
                panel.rotate(v > 0 ? 1 : v < 0 ? -1 : 0);
                return true;
            }
            try { if (active().onGenericMotion(e)) return true; }
            catch (Throwable t) { DuoLog.error(active().name() + " へ回転を渡せません: " + DuoLog.why(t)); }
            return active().view().dispatchGenericMotionEvent(e);
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void recordKey(KeyEvent e, String from) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) return; // 繰り返しは記録しない
        DuoLog.keys(from + " " + (e.getAction() == KeyEvent.ACTION_DOWN ? "押" : e.getAction() == KeyEvent.ACTION_UP ? "離" : "他")
                + " " + KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "") + " t=" + e.getEventTime());
    }

    // KeyRouter.Sink

    @Override public void deliver(int code, int action, long downTime, Object raw) {
        KeyEvent ev = raw instanceof KeyEvent ? (KeyEvent) raw
                : new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0);
        if (guiding()) { guide.onKey(ev); return; }
        if (panel.getVisibility() == View.VISIBLE) { panel.onKey(ev); return; }
        if (isMediaKey(code)) { mediaTarget().onKey(ev); return; }
        try { active().onKey(ev); }
        catch (Throwable t) { DuoLog.error(active().name() + " へキーを渡せません: " + DuoLog.why(t)); }
    }

    /** ステアリングの曲送り・曲戻し・再生/一時停止。 */
    private static boolean isMediaKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_HEADSETHOOK:
                return true;
            default: return false;
        }
    }

    /**
     * 曲送り等を渡す側。CarPlay で音楽が鳴っていれば CarPlay、そうでなければ YouTube。
     * (操作中の画面とは別に決める。地図を見ながら YouTube を送れるように)
     */
    private Pane mediaTarget() { return carSounds.contains("media") ? car : tv; }

    @Override public void action(KeyMap.Action a) {
        if (guiding()) return; // 案内の間は、裏の画面を動かさない
        DuoLog.log("ボタン操作: " + a.label());
        switch (a) {
            case SWITCH_SIDE: switchSide(); break;
            case SWAP: swap(); break;
            case PANEL: togglePanel(); break;
            case SINGLE: toggleSingle(); break;
        }
    }

    @Override public void systemDefault(int code) {
        PaneService s = service;
        if (s != null) s.systemDefault(code);
    }

    @Override public void learned(int code, int gesture) {
        main.removeCallbacks(learnTimeout);
        KeyMap.Action a = learnAction;
        learnAction = null;
        if (a == null) return;
        if (gesture == KeyMap.SHORT && KeyMap.reservedSingle(code)) {
            DuoLog.log("割り当ての登録: " + KeyMap.keyName(code) + "の1回押しはコマンダーの普通の操作に使うため割り当てられません。2回以上か長押しにしてください");
            if (panel.getVisibility() == View.VISIBLE) panel.refresh();
            return;
        }
        keyMap.set(a, code, gesture, true);
        Store.setKeyMap(this, keyMap);
        router.setMap(keyMap);
        DuoLog.log("割り当て: " + a.label() + " = " + keyMap.describe(a) + "(" + keyMap.serialize() + ")");
        if (panel.getVisibility() == View.VISIBLE) panel.refresh();
    }

    @Override public void note(String text) { DuoLog.log(text); }

    // ───────── 操作 ─────────

    private Pane active() {
        return SplitGeometry.tvOn(Store.side(this), Store.swapped(this)) ? tv : car;
    }

    private void focusActive() {
        if (panel.getVisibility() == View.VISIBLE) return;
        View v = active().view();
        noFocusHighlight(v);
        v.setFocusable(true);
        v.requestFocus();
    }

    /** 操作中の枠に Android が重ねる「選んでいる印」(薄い白)を出さない(本人: 邪魔)。中の部品にも。 */
    private static void noFocusHighlight(View v) {
        if (Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) noFocusHighlight(g.getChildAt(i));
        }
    }

    private void relayout() {
        applyLayout();
        if (padServer != null) padServer.setState(Store.single(this) ? "s F"
                : Store.side(this) == SplitGeometry.SIDE_LEFT ? "s L" : "s R");
        if (panel.getVisibility() == View.VISIBLE) panel.refresh();
        stateChanged();
    }

    @Override public void showGuide() {
        if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); applyLayout(); }
        guide.bringToFront();
        guide.open(this::guideDone);
    }

    private void guideDone() { Store.setGuided(this, true); focusActive(); }

    @Override public boolean bootSound() { return Store.bootSound(this); }
    @Override public void toggleBootSound() { Store.setBootSound(this, !Store.bootSound(this)); }

    private boolean guiding() { return guide != null && guide.getVisibility() == View.VISIBLE; }

    @Override public void switchSide() {
        int now = Store.side(this) == SplitGeometry.SIDE_LEFT ? SplitGeometry.SIDE_RIGHT : SplitGeometry.SIDE_LEFT;
        setSide(now);
    }

    void setSide(int side) {
        Store.setSide(this, side);
        relayout();
        focusActive();
        root.glow(Store.neonTarget(this), Store.neonLevel(this, 0) / 100f); // ターゲット切替: 設定の色・強さで一瞬
        DuoLog.log("操作する側: " + sideText());
    }

    /** 並べ方(入れ替え・操作側・1画面・操作盤の場所)を土台に伝えて並べ直す。 */
    /** いまの(アニメ中の)YouTube 幅の割合。普段は splitYt、ナビ中は navYt に向けて動く。 */
    private int curYt = -1;
    private android.animation.ValueAnimator ytAnim;

    private void applyLayout() {
        if (curYt < 0) curYt = Store.splitYt(this);
        root.setSingle(Store.single(this));
        root.apply(Store.swapped(this), Store.side(this), Store.panelHalf(this), curYt);
        updateHint();
    }

    /** CarPlay の接続状態(前回値)。変化した瞬間だけ、メインビューを色で知らせる。 */
    private boolean lastConn;

    /** 接続状態が変わったら、メインビューの縁を一瞬だけ光らせる(完了=青／不良=赤)。 */
    private void onConnChanged() {
        boolean conn = carOn() && carBluetooth(safeStatus(car));
        if (conn == lastConn) return;
        lastConn = conn;
        int which = conn ? 1 : 2;
        root.glow(Store.neon(this, which), Store.neonLevel(this, which) / 100f); // 完了 / 不良(切断)。色・強さは設定可
    }

    // ネオンの色・強さ(設定から RGB・強さを個別に変更)。which: 0=ターゲット切替 / 1=接続完了 / 2=接続不良。
    @Override public int neonColor(int which) { return Store.neon(this, which); }
    @Override public void setNeonColor(int which, int color) { Store.setNeon(this, which, color); }
    @Override public int neonLevel(int which) { return Store.neonLevel(this, which); }
    @Override public void setNeonLevel(int which, int lv) { Store.setNeonLevel(this, which, lv); }
    @Override public String neonColorText(int which) { int c = Store.neon(this, which); return String.format("#%06X", c & 0xFFFFFF); }

    /** 比率をなめらかに変える。 */
    private void animateYt(int target) {
        target = SplitGeometry.clampYt(target);
        if (curYt < 0) curYt = Store.splitYt(this);
        if (ytAnim != null) ytAnim.cancel();
        if (curYt == target) { applyLayout(); return; }
        ytAnim = android.animation.ValueAnimator.ofInt(curYt, target);
        ytAnim.setDuration(300);
        ytAnim.addUpdateListener(a -> { curYt = (int) a.getAnimatedValue(); applyLayout(); });
        ytAnim.start();
    }

    // ───────── ナビ音声での自動変更(比率・左右入れ替え) ─────────
    private boolean navEngaged, navDidSwap;
    private final Runnable navRevert = this::navRevert;

    /** CarPlay の音の有無が変わったら呼ぶ。設定がオンのときだけ、比率(と左右)を自動で変える。 */
    private void updateNavSplit() {
        boolean voice = false;
        for (String t : carSounds) if (!t.equals("media")) { voice = true; break; } // ナビ・通知・電話・Siri
        if (!Store.autoSplit(this) || Store.single(this)) {
            // 自動オフ: ナビ中に変えていたら元へ
            if (navEngaged) navRevert();
            return;
        }
        if (voice) {
            main.removeCallbacks(navRevert);
            if (!navEngaged) {
                navEngaged = true;
                // 地図を運転席の側へ(右ハンドルなら右、左ハンドルなら左)。入れ替え中＝地図が右
                if (Store.autoSwap(this) && Store.swapped(this) != Store.rightHand(this)) {
                    navDidSwap = true;
                    curYt = Store.navYt(this);
                    swap();                 // 地図を運転席側へ(くるっと)。比率も navYt で並べ直る
                } else {
                    navDidSwap = false;
                    animateYt(targetYt());
                }
            }
        } else if (navEngaged) {
            main.removeCallbacks(navRevert);
            main.postDelayed(navRevert, Store.navHoldMs(this)); // 少し待ってから戻す(短い案内で往復しない)
        }
    }

    private void navRevert() {
        main.removeCallbacks(navRevert);
        if (!navEngaged) return;
        navEngaged = false;
        if (navDidSwap) {
            navDidSwap = false;
            curYt = targetYt();
            if (Store.swapped(this) == Store.rightHand(this)) swap();   // 運転席側へ寄せる前の配置へ戻す
            else applyLayout();
        } else {
            animateYt(targetYt());
        }
    }

    /**
     * いま向かうべき YouTube の幅の割合。強いものから:
     * 曲がり角で地図を表示中(Unlimited Edition) → ナビ音声の間 → 到着後の全画面(Unlimited Edition) → 普段。
     */
    private int targetYt() {
        if (mapEngaged) return Store.mapYt(this);
        if (navEngaged) return Store.navYt(this);
        if (arriveEngaged) return 100;
        return Store.splitYt(this);
    }

    // ───────── 曲がり角の案内(Duo Cruise Unlimited Edition だけ。リリース版では案内が届かない) ─────────
    private final TurnPlan plan = new TurnPlan();
    private Route route;
    /** 曲がり角で地図を表示中 / そのために 1 画面の表示を地図へ切り替えた / 到着後の全画面 / 曲がり角の手前で音量を下げ中。 */
    private boolean mapEngaged, mapFlipped, arriveEngaged, turnDucked;
    private final Runnable mapRelease = this::mapRelease;

    /** CarPlay から(メインスレッド)。CarPlay が止まったら null。 */
    static void carRoute(Route r) {
        DuoActivity a = live;
        if (a != null && BuildConfig.UNLIMITED) { a.routeTesting = false; a.onRoute(r); }
    }

    private void onRoute(Route r) {
        boolean wasGuiding = route != null && route.getGuiding();
        route = r;
        if (r == null) plan.clear();
        else {
            plan.hintDist = Store.hintDist(this);
            plan.mapDist = Store.mapDist(this);
            plan.lightDist = Store.lightDist(this);
            plan.duckDist = Store.duckDist(this);
            plan.update(r.getGuiding(), r.getArrived(), r.turnIndex, r.distanceToTurnM, r.getSide());
        }
        // 新しいルートが始まったら、到着後の全画面をやめる
        if (r != null && r.getGuiding() && !wasGuiding && arriveEngaged) { arriveEngaged = false; animateYt(targetYt()); }
        applyRoute();
        if (plan.light != null && Store.turnLight(this)) {
            int c = Store.neon(this, 3), lv = Store.neonLevel(this, 3);
            root.turnGlow(plan.light, c, lv / 100f);
            if (padServer != null) padServer.turn(plan.light, c, lv);
            DuoLog.log("曲がり角の案内: " + ("L".equals(plan.light) ? "左" : "右") + "の縁を光らせました(あと " + r.distanceToTurnM + " m)");
        }
        if (plan.arrivedNow && Store.arriveFull(this)) arrive();
    }

    /** 設定が変わった・案内が届いたときに、表示・地図・音量を今の設定に合わせる。 */
    private void applyRoute() {
        updateHint();
        boolean want = plan.map && Store.mapFront(this);
        if (want) {
            main.removeCallbacks(mapRelease);
            if (!mapEngaged) engageMap();
        } else if (mapEngaged) {
            main.removeCallbacks(mapRelease);
            if (route == null || !Store.mapFront(this)) mapRelease();
            else main.postDelayed(mapRelease, Store.mapHoldMs(this)); // 曲がり終えて少し待ってから戻す
        }
        boolean duck = plan.duck && Store.turnDuck(this);
        if (duck != turnDucked) { turnDucked = duck; applyDuck(); }
    }

    /** 案内の板を出すか。表示の設定・地図が見えているか・メニューが出ているかで決める。 */
    private void updateHint() {
        if (!BuildConfig.UNLIMITED || root == null) return;
        int mode = Store.hintMode(this);
        Route r = route;
        boolean show = r != null && plan.hint && mode != 0 && (mode == 2 || !root.carVisible())
                && (panel == null || panel.getVisibility() != View.VISIBLE) && !guiding();
        root.turnHint(show, r == null ? 0 : r.getTurn(), r == null ? null : r.exitAngle,
                r == null ? -1 : r.drivingSide, r == null ? null : distanceText(r), r == null ? null : r.onto);
    }

    /** 曲がり角までの距離の文字。設定の単位(km / マイル)で、m から作る。距離が分からなければ iPhone の表示。 */
    private String distanceText(Route r) {
        if (r.distanceToTurnM < 0) return r.distanceText == null ? "" : r.distanceText;
        return Store.distance(this, r.distanceToTurnM);
    }

    /** 地図を表示する。2 画面なら地図を広げ、1 画面で YouTube を出していれば地図に切り替える。 */
    private void engageMap() {
        mapEngaged = true;
        if (Store.single(this)) {
            if (active() == tv) { mapFlipped = true; flipQuietly(); }
        } else {
            animateYt(targetYt());
        }
        DuoLog.log("曲がり角の案内: 地図を表示しました");
    }

    private void mapRelease() {
        main.removeCallbacks(mapRelease);
        if (!mapEngaged) return;
        mapEngaged = false;
        if (mapFlipped) {
            mapFlipped = false;
            if (Store.single(this) && active() == car) flipQuietly();
        } else {
            animateYt(targetYt());
        }
        DuoLog.log("曲がり角の案内: 元の表示に戻しました");
    }

    /** 到着: 地図をやめ、YouTube を全画面にする(次のルートが始まるまで)。 */
    private void arrive() {
        arriveEngaged = true;
        mapRelease();
        if (Store.single(this)) { if (active() == car) flipQuietly(); }
        else animateYt(targetYt());
        DuoLog.log("曲がり角の案内: 到着したので YouTube を全画面にしました");
    }

    /** 1 画面の表示を、光らせずに反対側へ(操作する側も一緒に移る)。 */
    private void flipQuietly() {
        Store.setSide(this, Store.side(this) == SplitGeometry.SIDE_LEFT ? SplitGeometry.SIDE_RIGHT : SplitGeometry.SIDE_LEFT);
        relayout();
        focusActive();
    }

    @Override public void routeSettingsChanged() { applyRoute(); }

    // ───────── 車の設定(メニュー › システム) ─────────

    @Override public boolean rightHand() { return Store.rightHand(this); }
    /** ハンドルの位置を変える。CarPlay は運転席側を iPhone に伝え直すため、動いていればつなぎ直す。 */
    @Override public void toggleRightHand() {
        Store.setRightHand(this, !Store.rightHand(this));
        DuoLog.log("ハンドルの位置: " + (Store.rightHand(this) ? "右" : "左") + "ハンドル");
        if (carOn) restartCar();
    }
    @Override public boolean miles() { return Store.miles(this); }
    @Override public void toggleMiles() { Store.setMiles(this, !Store.miles(this)); updateHint(); }

    @Override public String wifiSsid() { return car.wifiSsid(); }
    @Override public boolean hasWifiPass() { return car.hasWifiPass(); }
    /** CarPlay 用の Wi-Fi の名前かパスワードを、Duo Slate のキーボードで入れ直す。CarPlay を操作する側にして入力を待つ。 */
    @Override public void editWifi(boolean ssid) {
        closePanel();
        if (active() != car) {
            Store.setSide(this, Store.side(this) == SplitGeometry.SIDE_LEFT ? SplitGeometry.SIDE_RIGHT : SplitGeometry.SIDE_LEFT);
            relayout();
            focusActive();
        }
        carOn = true;
        car.editWifi(ssid);
        DuoLog.log("CarPlay 用の Wi-Fi の" + (ssid ? "名前" : "パスワード") + "を入れ直します");
    }

    /**
     * 見た目の確かめ用(Unlimited Edition)。停車中に、案内が届いたことにして表示・光・地図・音量を動かす。
     *   route-test 2 250 … 曲がり方 2(右折)・あと 250 m(曲がり方の番号は iAP2 の 0〜53。1=左折 4=Uターン 12=到着)
     *   route-end        … 案内を終わらせる
     * 番号は呼ぶたびに 1 つ進む(同じ番号のまま距離だけ縮めたいときは、末尾に same)。
     */
    private int testIndex;
    /** テストの案内を出している間(CarPlay がつながっていなくても消さない)。本物の案内が届いたら false。 */
    private boolean routeTesting;
    private String routeTest(String cmd) {
        if (!BuildConfig.UNLIMITED) return "この版(Duo Cruise)には曲がり角の案内がありません";
        String[] p = cmd.trim().split("\\s+");
        routeTesting = !p[0].equals("route-end");
        if (!routeTesting) { onRoute(null); return "案内を終わらせました"; }
        try {
            int type = p.length > 1 ? Integer.parseInt(p[1]) : 2;
            long dist = p.length > 2 ? Long.parseLong(p[2]) : 250;
            if (!(p.length > 3 && p[3].equals("same"))) testIndex++;
            int state = type == 12 ? 2 : 1;
            onRoute(new Route(state, dist, null, type, "テスト通り", null, null, 1, testIndex, "テスト", -1, "テスト"));
            return "案内(テスト): " + route;
        } catch (NumberFormatException e) {
            return "使い方: route-test <曲がり方 0〜53> <距離 m> [same] / route-end";
        }
    }

    // ───────── 設定(メニューから) ─────────
    /** 普段の比率を1段変える(50→60→…→100→30→…)。すぐ反映。 */
    @Override public void cycleSplitYt() {
        int v = Store.splitYt(this) + 10; if (v > 100) v = 30;
        Store.setSplitYt(this, v);
        animateYt(targetYt());
    }
    @Override public void cycleNavYt() { int v = Store.navYt(this) + 10; if (v > 100) v = 30; Store.setNavYt(this, v); animateYt(targetYt()); }
    // スライダーでの増減(左右で 5% ずつ)。
    @Override public int splitYtPct() { return Store.splitYt(this); }
    @Override public int navYtPct() { return Store.navYt(this); }
    @Override public void adjustSplitYt(int dir) {
        Store.setSplitYt(this, SplitGeometry.clampYt(Store.splitYt(this) + dir * 5));
        animateYt(targetYt());
    }
    @Override public void adjustNavYt(int dir) {
        Store.setNavYt(this, SplitGeometry.clampYt(Store.navYt(this) + dir * 5));
        animateYt(targetYt());
    }
    @Override public void toggleAutoSplit() { Store.setAutoSplit(this, !Store.autoSplit(this)); updateNavSplit(); }
    @Override public void toggleAutoSwap() { Store.setAutoSwap(this, !Store.autoSwap(this)); }
    @Override public void cycleNavHold() { long v = Store.navHoldMs(this) / 1000 + 2; if (v > 12) v = 3; Store.setNavHoldMs(this, v * 1000); }
    @Override public String splitYtText() { int y = Store.splitYt(this); return "YouTube " + y + "% ／ CarPlay " + (100 - y) + "%"; }
    @Override public String navYtText() { int y = Store.navYt(this); return "YouTube " + y + "% ／ CarPlay " + (100 - y) + "%"; }
    @Override public boolean autoSplitOn() { return Store.autoSplit(this); }
    @Override public boolean autoSwapOn() { return Store.autoSwap(this); }
    @Override public String navHoldText() { return Tx.s(R.string.seconds, Store.navHoldMs(this) / 1000); }

    /** 1画面と2画面の切り替え。1画面では操作中の側だけを出し、押し込み2回で YouTube だけ⇔マップだけになる。 */
    void toggleSingle() {
        Store.setSingle(this, !Store.single(this));
        relayout();
        focusActive();
        DuoLog.log("表示: " + (Store.single(this) ? "1画面 " + active().name() : "2画面"));
    }

    @Override public void swap() {
        if (Store.single(this)) { DuoLog.log("入れ替え: 1画面のときは何もしない"); return; }
        Store.setSwapped(this, !Store.swapped(this));
        // 操作している「中身」はそのまま(YouTube を操作中なら、入れ替え後も YouTube)
        Store.setSide(this, Store.side(this) == SplitGeometry.SIDE_LEFT ? SplitGeometry.SIDE_RIGHT : SplitGeometry.SIDE_LEFT);
        relayout();
        focusActive();
        DuoLog.log("入れ替え: " + layoutText());
    }

    void togglePanel() {
        if (panel.getVisibility() == View.VISIBLE) closePanel();
        else openPanel();
    }

    void openPanel() {
        panel.show(PanelView.MAIN);
        panel.setVisibility(View.VISIBLE);
        panel.enter();
        applyLayout();
    }

    @Override public void closePanel() {
        cancelLearn();
        if (panel.getVisibility() != View.VISIBLE) return;
        panel.leave(() -> {
            panel.setVisibility(View.GONE);
            applyLayout();
            focusActive();
        });
    }

    /** 操作盤の「CarPlay を開始/止める」。止めたら、次に開くか「開始」を押すまでつなぎ直さない。 */
    @Override public void toggleCar() {
        if (carOn) stopCar();
        else startCar("操作盤から");
    }

    /** iPhone の Bluetooth の一覧で分かりやすいよう、OTTO の Bluetooth の名前を「Duo Cruise」にする。 */
    static final String BT_NAME = "Duo Cruise";

    @android.annotation.SuppressLint("MissingPermission")
    private void nameBluetooth() {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || BT_NAME.equals(ad.getName())) return;
            DuoLog.log("Bluetooth の名前を「" + BT_NAME + "」にします(前: " + ad.getName() + ") " + (ad.setName(BT_NAME) ? "OK" : "できません"));
        } catch (Throwable t) {
            DuoLog.error("Bluetooth の名前を変えられません: " + DuoLog.why(t));
        }
    }

    private void startCar(String why) {
        nameBluetooth();
        DuoLog.log("CarPlay を開始します(" + why + ")");
        carOn = true;
        carRetryMs = CAR_RETRY_MIN_MS;
        try { car.start(); } catch (Throwable t) { DuoLog.error("CarPlay を始められません: " + DuoLog.why(t)); }
        stateChanged();
    }

    /** CarPlay の Bluetooth が iPhone とつながっている状態か(つながった後、Wi-Fi へ移って接続中まで)。 */
    static boolean carBluetooth(String s) {
        return s.startsWith("iPhone と準備中") || s.startsWith("無線 CarPlay 接続中");
    }

    /** つなぎ直しが要る状態か(失敗・切断・始められない・停止のまま)。 */
    static boolean carStopped(String s) {
        return s.startsWith("失敗") || s.startsWith("始められません") || s.startsWith("権限が足りません")
                || s.startsWith("iPhone との接続が終わりました") || s.equals("停止中");
    }

    /** 部品を作り直して、もう一度始める(前の Wi-Fi・Bluetooth・チップは手放してから)。 */
    private void restartCar() {
        carSounds.clear();
        applyDuck();
        try { car.destroy(); } catch (Throwable t) { DuoLog.error("CarPlay を止められません: " + DuoLog.why(t)); }
        car = Panes.car(this);
        root.setPanes(car.view(), tv.view(), panel);
        applyLayout();
        try { car.start(); } catch (Throwable t) { DuoLog.error("CarPlay を始められません: " + DuoLog.why(t)); }
        stateChanged();
    }

    @Override public boolean carOn() { return carOn; }

    /** 止めて、チップ・Wi-Fi・Bluetooth を手放す。新しい部品に取り替え、次の「開始」まで何もしない。 */
    void stopCar() {
        DuoLog.log("CarPlay を止めます");
        carOn = false;
        carSounds.clear();
        applyDuck();
        try { car.destroy(); } catch (Throwable t) { DuoLog.error("CarPlay を止められません: " + DuoLog.why(t)); }
        car = Panes.car(this);
        root.setPanes(car.view(), tv.view(), panel);
        applyLayout();
        stateChanged();
    }

    /** Duo Cruise 以外のホームアプリ(OTTO のホーム)。見つからなければ null。 */
    private ComponentName otherHome() {
        PackageManager pm = getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ComponentName pick = null;
        for (ResolveInfo r : pm.queryIntentActivities(home, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (pkg.equals("com.android.settings")) continue; // 設定の「ホームの代わり」は除く
            ComponentName c = new ComponentName(pkg, r.activityInfo.name);
            if (pick == null || pkg.equals("com.android.launcher3")) pick = c;
        }
        return pick;
    }

    @Override public String otherHomeName() {
        ComponentName c = otherHome();
        if (c == null) return Tx.s(R.string.not_found);
        try { return String.valueOf(getPackageManager().getActivityInfo(c, 0).loadLabel(getPackageManager())); }
        catch (Exception e) { return c.getPackageName(); }
    }

    /** OTTO のホームを、名指しで開く(Duo Cruise がホームアプリでも、ここから必ず抜けられる)。 */
    @Override public void goHome() {
        closePanel();
        ComponentName c = otherHome();
        try {
            if (c != null) {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(c)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                DuoLog.log("OTTO のホームを開きました: " + c.flattenToShortString());
            } else {
                DuoLog.error("ほかのホームアプリが見つからないため、設定を開きます");
                openSettings();
            }
        } catch (Exception e) {
            DuoLog.error("OTTO のホームを開けません: " + DuoLog.why(e) + "。設定を開きます");
            openSettings();
        }
    }

    @Override public void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { DuoLog.error("設定を開けません: " + DuoLog.why(e)); }
    }

    /** Android の「ホームアプリ」の選択画面。Duo Cruise を既定にするかは本人が選ぶ(自動では変えない)。 */
    @Override public void chooseHome() {
        try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) {
            DuoLog.error("ホームアプリの選択画面を開けません: " + DuoLog.why(e) + "。設定を開きます");
            openSettings();
        }
    }

    @Override public boolean isDefaultHome() {
        ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    @Override public void openAccessibility() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { DuoLog.error("ユーザー補助の設定を開けません: " + DuoLog.why(e)); }
    }

    @Override public boolean accessOn() {
        if (PaneService.isLive()) return true;
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(new ComponentName(this, PaneService.class).flattenToString());
    }

    @Override public boolean panelHalf() { return Store.panelHalf(this); }

    @Override public void togglePanelHalf() {
        Store.setPanelHalf(this, !Store.panelHalf(this));
        relayout();
    }

    @Override public String layoutText() {
        if (Store.single(this)) return Tx.s(R.string.layout_single, active().name());
        return Tx.s(Store.swapped(this) ? R.string.layout_rl : R.string.layout_lr);
    }

    @Override public String sideText() {
        return Tx.s(Store.side(this) == SplitGeometry.SIDE_LEFT ? R.string.side_left : R.string.side_right, active().name());
    }

    @Override public KeyMap keyMap() { return keyMap; }

    @Override public void startLearn(KeyMap.Action a) {
        learnAction = a;
        router.setLearning(true);
        main.removeCallbacks(learnTimeout);
        main.postDelayed(learnTimeout, 10_000);
        DuoLog.log("割り当ての登録: 「" + a.label() + "」にするボタンを押してください(短く/長く)");
    }

    @Override public void cancelLearn() {
        main.removeCallbacks(learnTimeout);
        learnAction = null;
        router.setLearning(false);
        if (panel.getVisibility() == View.VISIBLE) panel.refresh();
    }

    @Override public KeyMap.Action learning() { return learnAction; }

    @Override public void resetKeys() {
        keyMap = KeyMap.defaults();
        Store.setKeyMap(this, keyMap);
        router.setMap(keyMap);
        DuoLog.log("割り当てを初期値に戻しました: " + keyMap.serialize());
    }

    // SplitRoot.Listener

    @Override public void laidOut(int w, int h, String carRect, String tvRect) {
        sizes = "全体 " + w + "×" + h + " / CarPlay " + carRect + " / YouTube " + tvRect;
        DuoLog.log("枠の大きさ: " + sizes + " / " + insets);
    }

    // ───────── タッチパッド(PadInput.Host) ─────────

    @Override public View root() { return root; }

    @Override public boolean touch(MotionEvent e) { return root.dispatchTouchEvent(e); }

    /** 戻る: 押した回数を数えずに、すぐ操作中の側(または操作盤)へ。 */
    @Override public void back() {
        long t = SystemClock.uptimeMillis();
        deliver(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, t, null);
        deliver(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP, t, null);
    }

    @Override public void globalAction(int action) {
        if (!PaneService.global(action)) DuoLog.log("タッチパッド: ホーム・タスクはユーザー補助がオフのため使えません");
    }

    /** 文字は操作中の側へ(CarPlay 側は、OTTO の Wi-Fi のパスワードを入力中のときだけ受ける)。 */
    @Override public void typeText(String text) { active().typeText(text); }

    @Override public void typeKey(int keyCode) { active().typeKey(keyCode); }

    /** スクロール中は、操作する側を切り替えない(2本指スクロールで意図せず切り替わらないように)。 */
    @Override public void scrollActive(boolean on) { root.padScrolling = on; }

    /** タスクボタン長押し: 設定メニューを開く。 */
    @Override public void openMenu() { if (panel.getVisibility() != View.VISIBLE) openPanel(); }

    /** MAZDA コマンダー: 回す。メニュー表示中は項目移動、それ以外は操作中の画面のスクロールに回す。 */
    @Override public void commanderRotate(int steps) {
        if (guiding()) guide.rotate(steps);
        else if (panel.getVisibility() == View.VISIBLE) panel.rotate(steps);
        else { try { active().scroll(0, steps * 40f); active().scrollEnd(); } catch (Throwable ignored) {} }
    }

    /** MAZDA コマンダー: 倒す・押し込み。キーとして土台へ(メニュー表示中は操作盤、それ以外は操作中の画面)。 */
    @Override public void commanderKey(int keyCode) {
        long t = SystemClock.uptimeMillis();
        deliver(keyCode, KeyEvent.ACTION_DOWN, t, null);
        deliver(keyCode, KeyEvent.ACTION_UP, t, null);
    }

    /** 2本指スクロールは、操作中の画面本来の送り方(YouTube=矢印、CarPlay=ダイヤル)に渡す。メニュー表示中は裏へ渡さない。 */
    @Override public void scroll(float dx, float dy) {
        if (panel.getVisibility() == View.VISIBLE) { menuScroll(dx, dy); return; }
        active().scroll(dx, dy);
    }
    @Override public void scrollEnd() { if (panel.getVisibility() == View.VISIBLE) { menuAcc = 0; return; } active().scrollEnd(); }

    /** メニュー表示中の2本指スクロール(Duo Slate)。縦になぞった量を、ダイヤルの1目盛りずつに直す。調整中は値が動く。 */
    private float menuAcc;
    private void menuScroll(float dx, float dy) {
        float v = Math.abs(dy) >= Math.abs(dx) ? dy : dx; // 横になぞっても回せる(スライダーで自然)
        menuAcc += v;
        float step = 28f;
        while (menuAcc >= step) { menuAcc -= step; panel.rotate(1); }
        while (menuAcc <= -step) { menuAcc += step; panel.rotate(-1); }
    }

    @Override public void touched(int side) {
        if (side != Store.side(this)) setSide(side);
    }

    // ───────── 状態 ─────────

    String stateText() {
        StringBuilder b = new StringBuilder();
        b.append(layoutText()).append(" | ").append(sideText());
        b.append(" | 操作盤 ").append(panel.getVisibility() == View.VISIBLE ? "表示" : "非表示");
        b.append(" | ").append(sizes).append(" | ").append(insets);
        b.append(" | ユーザー補助 ").append(accessOn() ? "ON" : "OFF");
        if (padServer != null) b.append(" | ").append(padServer.status());
        b.append(" | ホームアプリ ").append(isDefaultHome() ? "Duo Cruise" : "ほか");
        b.append(" | 割り当て ").append(keyMap.serialize()).append(" 長押し").append(Store.longMs(this)).append("ms");
        b.append(" | CarPlay").append(car.real() ? "" : "(作れず)").append(": ").append(safeStatus(car));
        b.append(" | YouTube").append(tv.real() ? "" : "(作れず)").append(": ").append(safeStatus(tv));
        return b.toString();
    }

    private static String safeStatus(Pane p) {
        try { return p.status(); } catch (Throwable t) { return "状態を読めません " + DuoLog.why(t); }
    }

    /** いま鳴っている CarPlay の音の種類(audioType)。 */
    private final java.util.Set<String> carSounds = new java.util.HashSet<>();
    private final Runnable applyDuck = this::applyDuck;
    /** ナビ音声のあいだ YouTube を下げる音量。 */
    static final float DUCK_NAV = 0.2f;

    /** CarPlay の音が始まった/終わった(メインスレッド)。 */
    static void carAudio(String audioType, boolean active) {
        DuoActivity a = live;
        if (a == null) return;
        if (active) a.carSounds.add(audioType); else a.carSounds.remove(audioType);
        a.main.removeCallbacks(a.applyDuck);
        // 下げるのはすぐ、戻すのは1秒待ってから(ナビの言葉の切れ目で上がったり下がったりしないように)
        if (active) a.applyDuck(); else a.main.postDelayed(a.applyDuck, 1000);
        a.updateNavSplit();
    }

    /**
     * Apple の機器と同じ考え方: 基本は YouTube。CarPlay の
     * ・ナビの声・通知音(default/alert/compatibility) → YouTube を 20% に下げて重ねる
     * ・電話・Siri(telephony/speechrecognition) → YouTube を一時停止し、終わったら再開
     * ・音楽(media) → 何もしない
     * 鳴り終わったら元の音量に戻す。
     */
    private void applyDuck() {
        float level = 1f;
        boolean call = false;
        for (String t : carSounds) {
            if (t.equals("telephony") || t.equals("speechrecognition")) { level = 0f; call = true; }
            else if (!t.equals("media")) level = Math.min(level, DUCK_NAV);
        }
        if (turnDucked) level = Math.min(level, Store.duckLevel(this) / 100f); // 曲がり角の手前(Unlimited Edition)
        tv.duck(level);
        tv.hold(call); // 電話・Siri の間は一時停止。終わったら、止める前に再生していれば再開
    }

    /** 状態が変わったら、少し待ってまとめて1回送る。 */
    static void stateChanged() {
        DuoActivity a = live;
        if (a == null) return;
        boolean bt = carBluetooth(safeStatus(a.car));
        if (a.padServer != null) a.padServer.setBluetooth(bt);
        if (!bt && a.route != null && !a.routeTesting) a.onRoute(null); // iPhone と切れたら、案内も終わり
        a.onConnChanged();
        a.main.removeCallbacks(a.sendState);
        a.main.postDelayed(a.sendState, 800);
    }

    /** 指示ブロードキャストから(メインスレッドで呼ぶ)。結果の1行を返す。 */
    String command(String cmd) {
        if (cmd.startsWith("tv-")) {
            String r = tv.command(cmd);
            return r == null ? "YouTube の部品は「" + cmd + "」を扱いません" : r;
        }
        switch (cmd) {
            case "state": return stateText();
            case "swap": swap(); return "入れ替えました: " + layoutText();
            case "side": switchSide(); return sideText();
            case "guide-reset": Store.setGuided(this, false); return "次に開いたとき、はじめての案内を出します";
            case "panel": togglePanel(); return "操作盤 " + (panel.getVisibility() == View.VISIBLE ? "表示" : "非表示");
            case "keys-reset": resetKeys(); return "割り当てを初期値に戻しました: " + keyMap.serialize();
            // 遠くからは「止める」だけ。始めるのは OTTO の画面の操作盤からだけ
            case "car-stop": if (carOn) stopCar(); return "CarPlay は止まっています";
            case "car-wifi-reset": { String r = car.command(cmd); return r == null ? "CarPlay の部品がありません" : r; }
            case "route-end": return routeTest(cmd);
            default: return cmd.startsWith("route-test") ? routeTest(cmd) : null;
        }
    }
}
