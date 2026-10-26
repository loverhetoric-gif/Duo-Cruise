package com.yuki.duocruise.updater;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 自分のアプリを更新している数分間だけ、インストール確認の肯定ボタンを押す。
 * あわせて、指示「updater:screenshot」のときに今の画面を1枚撮る(Android 11 以上)、
 * 指示「updater:state」のときに窓の並び(位置と大きさ)を読む。それ以外では何もしない。
 */
public class ConfirmClickService extends AccessibilityService {
    private static final String[] OK = {"更新", "インストール", "Update", "Install", "続行", "Continue"};
    private static volatile ConfirmClickService instance;
    private static final Executor SHOT = Executors.newSingleThreadExecutor();
    private long lastClick = 0;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Remote.kick();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

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

    /** 今の画面を1枚撮り、PNG にして記録へ送る。結果は kind=result で返す。 */
    static void screenshot(Context c, int row, String cmd) {
        ConfirmClickService s = instance;
        if (s == null) {
            Remote.result(cmd + " → 撮れません(「Duo Cruise 更新」のユーザー補助がオフです)", row);
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            Remote.result(cmd + " → 撮れません(Android 11 以上が必要です)", row);
            return;
        }
        s.takeScreenshot(Display.DEFAULT_DISPLAY, SHOT, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                try (HardwareBuffer hb = r.getHardwareBuffer()) {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    if (hw == null) throw new Exception("画像にできません");
                    Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    String size = bmp.getWidth() + "x" + bmp.getHeight();
                    bmp.recycle();
                    byte[] png = out.toByteArray();
                    Remote.addPng("画面 " + size, png, row);
                    Remote.result(cmd + " → 撮りました(" + size + "、" + (png.length / 1024) + " KB)", row);
                } catch (Throwable e) {
                    Remote.result(cmd + " → 撮れましたが保存に失敗: " + e.getMessage(), row);
                }
            }

            @Override public void onFailure(int code) {
                Remote.result(cmd + " → 撮れませんでした(理由の番号 " + code + ")", row);
            }
        });
    }

    /** 今の窓の並び(種類・位置と大きさ・アプリ)。ユーザー補助がオフなら空。 */
    static String windows() {
        ConfirmClickService s = instance;
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                AccessibilityNodeInfo root = w.getRoot();
                CharSequence pkg = root == null ? null : root.getPackageName();
                sb.append("  ").append(typeName(w.getType()))
                        .append(" ").append(b.left).append(",").append(b.top).append(" ")
                        .append(b.width()).append("x").append(b.height())
                        .append(w.isActive() ? " 操作中" : "").append(w.isFocused() ? " 入力先" : "")
                        .append(" ").append(pkg == null ? "" : pkg)
                        .append(w.getTitle() == null ? "" : " 「" + w.getTitle() + "」").append('\n');
            }
        } catch (Throwable e) { sb.append("  窓を読めません(").append(e.getMessage()).append(")\n"); }
        return sb.toString();
    }

    /** 今、窓があるアプリのパッケージ名(空白区切り)。ユーザー補助がオフなら null。 */
    static String windowPackages() {
        ConfirmClickService s = instance;
        if (s == null) return null;
        TreeSet<String> set = new TreeSet<>();
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null && root.getPackageName() != null) set.add(root.getPackageName().toString());
            }
        } catch (Throwable ignored) {}
        return String.join(" ", set);
    }

    private static String typeName(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "アプリ";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "入力";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "システム";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "補助の重ね";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "分割線";
            default: return "種類" + t;
        }
    }
}
