package com.yuki.duocruise.updater;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/** 自分のアプリを更新している数分間だけ、インストール確認の肯定ボタンを押す。 */
public class ConfirmClickService extends AccessibilityService {
    private static final String[] OK = {"更新", "インストール", "Update", "Install", "続行", "Continue"};
    private long lastClick = 0;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (System.currentTimeMillis() > UpdateEngine.autoConfirmUntil) return;
        if (System.currentTimeMillis() - lastClick < 1500) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        for (String t : OK) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(t);
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isClickable() && n.isEnabled() && n.getClassName() != null && n.getClassName().toString().contains("Button")) {
                    UpdLog.init(this);
                    UpdLog.add("確認画面の「" + t + "」を自動で押しました");
                    lastClick = System.currentTimeMillis();
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return;
                }
            }
        }
    }

    @Override public void onInterrupt() {}
}
