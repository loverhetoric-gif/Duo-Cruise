package com.yuki.duocruise.keys;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** どのアプリが前面でも、届いたキーを記録だけする(キーは消費せず、そのまま通す)。 */
public class KeyRecorderService extends AccessibilityService {
    private String lastPkg = "";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        KeyLog.init(this);
        KeyLog.serviceActive = true;
        KeyLog.add("[サービス] 記録を開始しました");
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        KeyLog.add(KeyLog.describe(event, "全体"));
        return false; // 記録だけ。キーは各アプリへそのまま渡す
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals(lastPkg)) return;
        lastPkg = pkg;
        KeyLog.add("[前面] " + pkg);
    }

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent intent) {
        KeyLog.serviceActive = false;
        return super.onUnbind(intent);
    }
}
