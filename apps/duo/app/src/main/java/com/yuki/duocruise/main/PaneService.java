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

    static boolean isLive() { return live != null; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        DuoLog.init(this);
        live = this;
        DuoLog.log("ユーザー補助(ホーム・タスクの受け取り)が動き始めました");
        DuoActivity.stateChanged();
    }

    @Override public boolean onUnbind(android.content.Intent i) {
        live = null;
        DuoLog.log("ユーザー補助が止まりました");
        DuoActivity.stateChanged();
        return super.onUnbind(i);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override public void onInterrupt() {}

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
