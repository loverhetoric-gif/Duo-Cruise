package com.yuki.duocruise.updater;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

/** 自作アプリのユーザー補助が、入っているか・オンかを調べ、設定のその項目を開く(オンにするのは本人の操作)。 */
final class A11y {
    private A11y() {}

    /** そのアプリのユーザー補助サービス(最初の1つ)。無ければ null。 */
    static ComponentName find(Context c, String pkg) {
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            for (AccessibilityServiceInfo i : am.getInstalledAccessibilityServiceList()) {
                if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null
                        && pkg.equals(i.getResolveInfo().serviceInfo.packageName)) {
                    return new ComponentName(pkg, i.getResolveInfo().serviceInfo.name);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    static boolean enabled(Context c, ComponentName cn) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null || cn == null) return false;
        for (String part : s.split(":")) {
            ComponentName x = ComponentName.unflattenFromString(part.trim());
            if (cn.equals(x)) return true;
        }
        return false;
    }

    /** 設定の「そのサービス」の画面を直接開く。開けなければユーザー補助の一覧を開く。 */
    static void open(Activity a, ComponentName cn) {
        try {
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString());
            a.startActivity(i);
            return;
        } catch (Exception ignored) {}
        try { a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
    }

    /** アプリ情報(︙→「制限付き設定を許可」がある画面)を開く。 */
    static void openAppInfo(Activity a, String pkg) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (Exception ignored) {}
    }
}
