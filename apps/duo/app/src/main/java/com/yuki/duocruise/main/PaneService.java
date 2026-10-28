package com.yuki.duocruise.main;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * ホーム・タスク等の「アプリには届かないキー」を、Duo Cruise が前面のときだけ横取りして DuoActivity へ渡す。
 * ・それ以外のキーは何もしない(いつもどおり前面の画面へ届く)
 * ・Duo Cruise が前面でないときは、どのキーも触らない
 * ・割り当てが無いときは、横取りしない(または、横取りした分をシステム本来の動作で返す)
 */
public class PaneService extends AccessibilityService {
    private static volatile PaneService live;
    /** 汎用モード(ホームや他アプリを操作)の受け口。ユーザー補助が動いている間だけある。 */
    private SystemPad systemPad;

    static boolean isLive() { return live != null; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        DuoLog.init(this);
        live = this;
        // Duo Slate を、Duo Cruise 以外のアプリ(ホームなど)でも使えるようにする
        try {
            systemPad = new SystemPad(this);
            PadRouter.get(this).setSystemSink(systemPad);
            PadRouter.get(this).setForegroundSource(this::foregroundPackage);
        } catch (Throwable t) {
            DuoLog.error("汎用タッチパッドを用意できません: " + DuoLog.why(t));
        }
        DuoLog.log("ユーザー補助が動き始めました(ホーム・他アプリでも Duo Slate が使えます)");
        DuoActivity.stateChanged();
    }

    @Override public boolean onUnbind(android.content.Intent i) {
        live = null;
        PadRouter.get(this).setForegroundSource(null);
        PadRouter.get(this).setSystemSink(null);
        if (systemPad != null) { systemPad.dispose(); systemPad = null; }
        DuoLog.log("ユーザー補助が止まりました");
        DuoActivity.stateChanged();
        return super.onUnbind(i);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        // いま前面のアプリを覚える(Pad のモードの切り替えに使う)
        if (e != null && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            PadRouter.get(this).setForeground(e.getPackageName().toString());
        }
    }

    @Override public void onInterrupt() {}

    /** いま前面のアプリのパッケージ名。確かめられなければ空。 */
    private String foregroundPackage() {
        try {
            android.view.accessibility.AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                CharSequence p = r.getPackageName();
                r.recycle();
                return p == null ? "" : p.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        DuoActivity a = DuoActivity.front;
        if (a == null || !KeyMap.isSystemKey(e.getKeyCode())) return false;
        try {
            return a.onSystemKey(e, this);
        } catch (Throwable t) {
            DuoLog.error("ホーム等の受け取りで失敗: " + DuoLog.why(t));
            return false;
        }
    }

    /** タッチパッドの「ホーム」「タスク」。ユーザー補助がオフなら false。 */
    static boolean global(int action) {
        PaneService s = live;
        return s != null && s.performGlobalAction(action);
    }

    /** 横取りしたキーを、システム本来の動作で実行する。 */
    void systemDefault(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_HOME: performGlobalAction(GLOBAL_ACTION_HOME); break;
            case KeyEvent.KEYCODE_APP_SWITCH: performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            default: DuoLog.log("システムのキー " + KeyMap.keyName(code) + " は、本来の動作に戻せません");
        }
    }
}
