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

/**
 * Duo Cruise 本体の画面。1つの画面の中に、左右2つの枠(各 740×416)を持つ。
 * ・左＝CarPlay(B の CarPlayPane)、右＝TV 版 YouTube(D の TvPane)。入れ替え中は逆
 * ・入れ替え＝枠の置き直しだけ(YouTube を読み込み直さない)
 * ・操作側の切り替え・枠線・キーの振り分けは、この画面の中で行う(KeyRouter)
 * ・ホーム・タスクは、この画面が前面のときだけユーザー補助(PaneService)が横取りしてここへ渡す
 */
public class DuoActivity extends Activity implements KeyRouter.Sink, PanelView.Host, SplitRoot.Listener {

    /** 前面にいるときだけ入る(ユーザー補助がホーム等を渡す先)。 */
    static volatile DuoActivity front;
    /** 作られている間だけ入る(指示ブロードキャストの届け先)。 */
    static volatile DuoActivity live;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Pane car, tv;
    /** 本人が操作盤で CarPlay を開始したか。開いた直後は false(チップに触れない)。 */
    private boolean carOn;
    private SplitRoot root;
    private PanelView panel;
    private KeyRouter router;
    private KeyMap keyMap;
    private KeyMap.Action learnAction;
    private PaneService service;
    private String sizes = "-", insets = "-";
    private final Runnable learnTimeout = () -> {
        if (learnAction != null) { DuoLog.log("割り当ての登録: 時間切れで取りやめ"); cancelLearn(); }
    };
    private final Runnable sendState = () -> DuoLog.state(stateText());

    // ───────── 一生 ─────────

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        DuoLog.init(this);
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
        root.setPanes(car.view(), tv.view(), panel);
        setContentView(root);
        setupInsets();
        root.apply(Store.swapped(this), Store.side(this), Store.panelHalf(this));

        askPermissions();
        // CarPlay は開いただけでは始めない(チップ・Wi-Fi・Bluetooth に触れない)。操作盤の「CarPlay を開始」でだけ始める
        try { tv.start(); } catch (Throwable t) { DuoLog.error("YouTube を始められません: " + DuoLog.why(t)); }
        boolean asHome = getIntent() != null && getIntent().hasCategory(Intent.CATEGORY_HOME);
        DuoLog.log("Duo Cruise を開きました(版 " + DuoLog.version() + (asHome ? "、ホームとして" : "") + ")");
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
        if (root != null) root.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        front = this;
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
        if (panel.getVisibility() == View.VISIBLE) { panel.onKey(ev); return; }
        try { active().onKey(ev); }
        catch (Throwable t) { DuoLog.error(active().name() + " へキーを渡せません: " + DuoLog.why(t)); }
    }

    @Override public void action(KeyMap.Action a) {
        DuoLog.log("ボタン操作: " + a.label);
        switch (a) {
            case SWITCH_SIDE: switchSide(); break;
            case SWAP: swap(); break;
            case PANEL: togglePanel(); break;
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
        keyMap.set(a, code, gesture, true);
        Store.setKeyMap(this, keyMap);
        router.setMap(keyMap);
        DuoLog.log("割り当て: " + a.label + " = " + keyMap.describe(a) + "(" + keyMap.serialize() + ")");
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
        v.setFocusable(true);
        v.requestFocus();
    }

    private void relayout() {
        root.apply(Store.swapped(this), Store.side(this), Store.panelHalf(this));
        if (panel.getVisibility() == View.VISIBLE) panel.refresh();
        stateChanged();
    }

    @Override public void switchSide() {
        int now = Store.side(this) == SplitGeometry.SIDE_LEFT ? SplitGeometry.SIDE_RIGHT : SplitGeometry.SIDE_LEFT;
        setSide(now);
    }

    void setSide(int side) {
        Store.setSide(this, side);
        relayout();
        focusActive();
        root.showLabel("操作: " + active().name());
        DuoLog.log("操作する側: " + sideText());
    }

    @Override public void swap() {
        Store.setSwapped(this, !Store.swapped(this));
        // 操作している「中身」はそのまま(YouTube を操作中なら、入れ替え後も YouTube)
        Store.setSide(this, Store.side(this) == SplitGeometry.SIDE_LEFT ? SplitGeometry.SIDE_RIGHT : SplitGeometry.SIDE_LEFT);
        relayout();
        focusActive();
        root.showLabel(layoutText());
        DuoLog.log("入れ替え: " + layoutText());
    }

    void togglePanel() {
        if (panel.getVisibility() == View.VISIBLE) closePanel();
        else openPanel();
    }

    void openPanel() {
        panel.show(PanelView.MAIN);
        panel.setVisibility(View.VISIBLE);
        root.apply(Store.swapped(this), Store.side(this), Store.panelHalf(this));
    }

    @Override public void closePanel() {
        cancelLearn();
        panel.setVisibility(View.GONE);
        root.apply(Store.swapped(this), Store.side(this), Store.panelHalf(this));
        focusActive();
    }

    /** 操作盤の「CarPlay を開始/止める」。CarPlay を始めるのは、本人がこれを押したときだけ。 */
    @Override public void toggleCar() {
        if (carOn) stopCar();
        else {
            DuoLog.log("CarPlay を開始します(操作盤から)");
            carOn = true;
            try { car.start(); } catch (Throwable t) { DuoLog.error("CarPlay を始められません: " + DuoLog.why(t)); }
            stateChanged();
        }
    }

    @Override public boolean carOn() { return carOn; }

    /** 止めて、チップ・Wi-Fi・Bluetooth を手放す。新しい部品に取り替え、次の「開始」まで何もしない。 */
    void stopCar() {
        DuoLog.log("CarPlay を止めます");
        carOn = false;
        try { car.destroy(); } catch (Throwable t) { DuoLog.error("CarPlay を止められません: " + DuoLog.why(t)); }
        car = Panes.car(this);
        root.setPanes(car.view(), tv.view(), panel);
        root.apply(Store.swapped(this), Store.side(this), Store.panelHalf(this));
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
        if (c == null) return "見つかりません";
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
        return Store.swapped(this) ? "左 YouTube ／ 右 CarPlay" : "左 CarPlay ／ 右 YouTube";
    }

    @Override public String sideText() {
        return "操作中: " + (Store.side(this) == SplitGeometry.SIDE_LEFT ? "左" : "右") + " " + active().name();
    }

    @Override public KeyMap keyMap() { return keyMap; }

    @Override public void startLearn(KeyMap.Action a) {
        learnAction = a;
        router.setLearning(true);
        main.removeCallbacks(learnTimeout);
        main.postDelayed(learnTimeout, 10_000);
        DuoLog.log("割り当ての登録: 「" + a.label + "」にするボタンを押してください(短く/長く)");
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
        b.append(" | ホームアプリ ").append(isDefaultHome() ? "Duo Cruise" : "ほか");
        b.append(" | 割り当て ").append(keyMap.serialize()).append(" 長押し").append(Store.longMs(this)).append("ms");
        b.append(" | CarPlay").append(car.real() ? "" : "(作れず)").append(": ").append(safeStatus(car));
        b.append(" | YouTube").append(tv.real() ? "" : "(作れず)").append(": ").append(safeStatus(tv));
        return b.toString();
    }

    private static String safeStatus(Pane p) {
        try { return p.status(); } catch (Throwable t) { return "状態を読めません " + DuoLog.why(t); }
    }

    /** 状態が変わったら、少し待ってまとめて1回送る。 */
    static void stateChanged() {
        DuoActivity a = live;
        if (a == null) return;
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
            case "panel": togglePanel(); return "操作盤 " + (panel.getVisibility() == View.VISIBLE ? "表示" : "非表示");
            case "keys-reset": resetKeys(); return "割り当てを初期値に戻しました: " + keyMap.serialize();
            // 遠くからは「止める」だけ。始めるのは OTTO の画面の操作盤からだけ
            case "car-stop": if (carOn) stopCar(); return "CarPlay は止まっています";
            default: return null;
        }
    }
}
