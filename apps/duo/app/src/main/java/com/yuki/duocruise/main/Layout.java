package com.yuki.duocruise.main;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 左右の窓の位置を決めて、アプリを並べて開く。 */
final class Layout {
    private Layout() {}

    static final String[] METHOD_NAMES = {"方式A 位置だけ指定", "方式B 自由な窓で指定", "方式C 隣に並べる"};
    private static final int WINDOWING_MODE_FREEFORM = 5;

    static Point screen(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new Point(b.width(), b.height());
        }
        Point p = new Point();
        Display d = wm.getDefaultDisplay();
        d.getRealSize(p);
        return p;
    }

    /** 画面上の左半分(side=0)か右半分(side=1)。 */
    static Rect half(Context c, int side) {
        Point s = screen(c);
        int mid = s.x / 2;
        return side == Store.SIDE_LEFT ? new Rect(0, 0, mid, s.y) : new Rect(mid, 0, s.x, s.y);
    }

    /** CarPlay(左に置くアプリ)を起動する Intent。見つからなければ null。 */
    static Intent carIntent(Context c) {
        String v = Store.leftApp(c);
        if (v.isEmpty()) {
            AppItem guess = guessCarApp(c);
            if (guess == null) return null;
            v = guess.key();
            Store.setLeftApp(c, v);
            Store.log("左のアプリを自動で選びました: " + guess.label);
        }
        String[] pc = v.split("/", 2);
        if (pc.length != 2) return null;
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(pc[0], pc[1]));
    }

    static final class AppItem {
        final String label, pkg, cls;
        AppItem(String l, String p, String c) { label = l; pkg = p; cls = c; }
        String key() { return pkg + "/" + cls; }
    }

    static List<AppItem> launchableApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppItem> out = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (r.activityInfo.packageName.equals(c.getPackageName())) continue;
            out.add(new AppItem(String.valueOf(r.loadLabel(pm)), r.activityInfo.packageName, r.activityInfo.name));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    static AppItem guessCarApp(Context c) {
        for (AppItem a : launchableApps(c)) {
            String s = (a.label + " " + a.pkg).toLowerCase();
            if (s.contains("multiplay") || s.contains("carplay")) return a;
        }
        return null;
    }

    static String labelOf(Context c, String key) {
        for (AppItem a : launchableApps(c)) if (a.key().equals(key)) return a.label;
        return key.isEmpty() ? "未選択" : key;
    }

    /** 1つのアプリを、指定した場所に開く。 */
    static void open(Context c, Intent i, Rect r, int method, String name) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (method == 2) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(r);
        if (method == 1) {
            try {
                Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                m.invoke(o, WINDOWING_MODE_FREEFORM);
            } catch (Throwable t) {
                Store.log("自由な窓の指定は使えません(" + t.getClass().getSimpleName() + ")");
            }
        }
        try {
            c.startActivity(i, o.toBundle());
            Store.log(name + " を " + r.toShortString() + " に開きました(" + METHOD_NAMES[method] + ")");
        } catch (Exception e) {
            Store.log(name + " を開けません: " + e.getMessage());
        }
    }

    /** 2画面で開く。CarPlay を先に、TV 版 YouTube を後に開く(後から開いた側に操作が向く)。 */
    static void start(Context c) {
        boolean sw = Store.swapped(c);
        int carSide = sw ? Store.SIDE_RIGHT : Store.SIDE_LEFT;
        int tvSide = sw ? Store.SIDE_LEFT : Store.SIDE_RIGHT;
        int method = Store.method(c);
        Intent car = carIntent(c);
        if (car == null) Store.log("左に置くアプリが見つかりません。「左のアプリ」で選んでください");
        else open(c, car, half(c, carSide), method, "CarPlay");
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent tv = new Intent(app, TvActivity.class);
            open(app, tv, half(app, tvSide), method, "TV版YouTube");
            Store.setSide(app, tvSide);
            PaneService.refreshFrame();
        }, 700);
    }

    /** 左右を入れ替えて開き直す。 */
    static void swap(Context c) {
        Store.setSwapped(c, !Store.swapped(c));
        Store.log(Store.swapped(c) ? "入れ替え: 左=YouTube / 右=CarPlay" : "入れ替え: 左=CarPlay / 右=YouTube");
        TvActivity.closeIfOpen();
        new Handler(Looper.getMainLooper()).postDelayed(() -> start(c.getApplicationContext()), 400);
    }

    /** 診断: 端末が窓の並べ方に対応しているか。 */
    static String diagnose(Context c) {
        PackageManager pm = c.getPackageManager();
        Point s = screen(c);
        StringBuilder b = new StringBuilder();
        b.append("画面 ").append(s.x).append("×").append(s.y);
        b.append(" / Android ").append(Build.VERSION.RELEASE);
        b.append(" / 自由な窓 ").append(pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ? "対応" : "非対応");
        b.append(" / 画面分割 ").append(pm.hasSystemFeature("android.software.activities_on_secondary_displays") ? "あり" : "-");
        b.append(" / freeform設定 ").append(global(c, "enable_freeform_support"));
        b.append(" / 強制サイズ変更 ").append(global(c, "force_resizable_activities"));
        return b.toString();
    }

    private static String global(Context c, String k) {
        try {
            String v = Settings.Global.getString(c.getContentResolver(), k);
            return v == null ? "-" : v;
        } catch (Exception e) { return "?"; }
    }
}
