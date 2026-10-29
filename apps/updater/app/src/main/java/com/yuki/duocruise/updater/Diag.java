package com.yuki.duocruise.updater;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 記録シートの「指示」から来る診断。受け付けるのは決まった一覧だけ(任意のコマンドは実行しない)。
 *   updater:state / updater:packages / updater:check / updater:test-channel on|off / updater:screenshot
 *   updater:multiplay-log … 機器の CarPlay(MultiPlay)の部品の記録(logcat)を送る(要 READ_LOGS。adb で許可する)
 *   com.yuki.duocruise.main:〜 … 本体へブロードキャスト(キー記録は 2026-10-27 に片付けた)で渡すだけ
 * 結果は kind=result で記録に返す。
 */
final class Diag {
    static final String PERM = "com.yuki.duocruise.permission.LOG";
    static final String ACTION_COMMAND = "com.yuki.duocruise.COMMAND";
    static final String MAIN = "com.yuki.duocruise.main";
    /** 自作アプリへ渡す指示の中身として許す文字(英数字と - _ . = : 空白、80文字まで)。 */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9 _.=:-]{1,80}");
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private Diag() {}

    static void run(Context c, String raw, int row) {
        final String cmd = raw == null ? "" : raw.trim();
        EXEC.execute(() -> {
            try {
                exec(c, cmd, row);
            } catch (Throwable e) {
                Remote.result(cmd + " → 失敗: " + e, row);
            }
        });
    }

    /**
     * MultiPlay の CarPlay を受け持つ機器の部品の記録の名前(tag)。アプリの中からは他のプログラムの番号(PID)が
     * 見えないため、名前で絞る(2026-10-29 機器で確認: lylinkservice・com.ap.play は carlife などで出す)。
     */
    private static final String[] MULTIPLAY_TAGS = {"carlife", "lylinkservice", "jlylink", "iAP2Client", "iAP2", "lyscrservice"};
    /** 1 回に送る上限(記録シートの 10 行ぶん)。 */
    private static final int MULTIPLAY_LOG_MAX = 300_000;

    /**
     * MultiPlay の CarPlay の部品が出した記録(logcat)を、新しい方から上限まで集める。
     * コマンダーが Duo Cruise の CarPlay に効かない件(2026-10-29)で、MultiPlay がダイヤルを
     * iPhone にどう伝えているか(hid_des など)を比べるため。読むには READ_LOGS が要る(adb で pm grant)。
     */
    private static String multiPlayLog() throws Exception {
        StringBuilder cmd = new StringBuilder("logcat -d -b all -v threadtime -s");
        for (String tag : MULTIPLAY_TAGS) cmd.append(' ').append(tag).append(":V");
        String log = run(cmd.toString());
        if (log.length() > MULTIPLAY_LOG_MAX) return "…(古い分は省略)\n" + log.substring(log.length() - MULTIPLAY_LOG_MAX);
        return log.isEmpty() ? "(MultiPlay の記録はありません。READ_LOGS が無いか、記録が流れて消えた)" : log;
    }

    private static String run(String command) throws Exception {
        Process p = Runtime.getRuntime().exec(command);
        StringBuilder b = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        p.waitFor();
        return b.toString();
    }

    private static void exec(Context c, String cmd, int row) throws Exception {
        int colon = cmd.indexOf(':');
        String target = colon > 0 ? cmd.substring(0, colon).trim() : "";
        String arg = colon > 0 ? cmd.substring(colon + 1).trim() : "";
        if ("updater".equals(target) || BuildConfig.APPLICATION_ID.equals(target)) {
            switch (arg) {
                case "state":
                    Remote.add("state", state(c), BuildConfig.APPLICATION_ID, String.valueOf(BuildConfig.VERSION_CODE), row, 0);
                    Remote.result(cmd + " → 状態を送りました", row);
                    return;
                case "check": {
                    int n = UpdateEngine.checkAndInstall(c, false);
                    Remote.result(cmd + " → " + UpdateEngine.lastResult + "(開始 " + n + " 件)", row);
                    return;
                }
                case "test-channel on":
                case "test-channel off": {
                    boolean on = arg.endsWith("on");
                    UpdateEngine.setTestChannel(c, on);
                    UpdLog.add("テスト版を受け取る: " + (on ? "オン" : "オフ") + "(指示による)");
                    Remote.result(cmd + " → テスト版を受け取る を" + (on ? "オン" : "オフ") + "にしました", row);
                    return;
                }
                case "packages": {
                    String all = packages(c);
                    int parts = 0;
                    for (int i = 0; i < all.length(); i += 30000, parts++) {
                        Remote.add("state", all.substring(i, Math.min(all.length(), i + 30000)),
                                BuildConfig.APPLICATION_ID, String.valueOf(BuildConfig.VERSION_CODE), row, 0);
                    }
                    Remote.result(cmd + " → アプリの一覧を送りました(" + parts + " 件に分割)", row);
                    return;
                }
                case "multiplay-log": {
                    String all = multiPlayLog();
                    int parts = 0;
                    for (int i = 0; i < all.length(); i += 30000, parts++) {
                        Remote.add("state", all.substring(i, Math.min(all.length(), i + 30000)),
                                BuildConfig.APPLICATION_ID, String.valueOf(BuildConfig.VERSION_CODE), row, 0);
                    }
                    Remote.result(cmd + " → MultiPlay の記録を送りました(" + all.length() + " 文字、" + parts + " 件に分割)", row);
                    return;
                }
                case "screenshot":
                    ConfirmClickService.screenshot(c, row, cmd);
                    return;
                case "confirm":
                    ConfirmClickService.confirmNow(c, row, cmd);
                    return;
                default:
                    Remote.result(cmd + " → 受け付けない指示です(updater:state / packages / check / test-channel on|off / screenshot / confirm / multiplay-log)", row);
                    return;
            }
        }
        if (MAIN.equals(target)) {
            if (!SAFE.matcher(arg).matches()) {
                Remote.result(cmd + " → 受け付けない指示です(使えない文字・長すぎ)", row);
                return;
            }
            if (UpdateEngine.installedVersion(c, target) < 0) {
                Remote.result(cmd + " → " + target + " が入っていません", row);
                return;
            }
            Intent i = new Intent(ACTION_COMMAND).setPackage(target)
                    .putExtra("command", arg).putExtra("row", row)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            // 受け取る側も同じ署名の権限を持つときだけ届く
            c.sendBroadcast(i, PERM);
            Remote.result(cmd + " → " + target + " へ渡しました(結果はそのアプリから届きます)", row);
            return;
        }
        Remote.result(cmd + " → 受け付けない指示です", row);
    }

    // ---------- 状態 ----------

    static String netType(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "なし";
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return "不明";
            String t = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "モバイル"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "有線"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ? "Bluetooth"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : "その他";
            return t + (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "(つながる)" : "(外へ出られない)");
        } catch (Exception e) { return "不明(" + e.getMessage() + ")"; }
    }

    private static String v(Context c, String pkg) {
        long x = UpdateEngine.installedVersion(c, pkg);
        return x < 0 ? "未導入" : "v" + x;
    }

    private static String onOff(Context c, String pkg) {
        ComponentName cn = A11y.find(c, pkg);
        if (cn == null) return UpdateEngine.installedVersion(c, pkg) < 0 ? "未導入" : "なし";
        return A11y.enabled(c, cn) ? "ON" : "OFF";
    }

    static String state(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("端末: ").append(Remote.device()).append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("版: 更新 ").append(v(c, BuildConfig.APPLICATION_ID))
                .append(" / 本体 ").append(v(c, MAIN)).append('\n');
        sb.append("画面: ").append(screen(c)).append('\n');
        sb.append("ネット: ").append(netType(c)).append('\n');
        boolean can = Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
        sb.append("更新: テスト版 ").append(UpdateEngine.testChannel(c) ? "ON" : "OFF")
                .append(" / インストール許可 ").append(can ? "済" : "未")
                .append(" / 最後の確認 ").append(UpdateEngine.lastResult).append('\n');
        sb.append("ユーザー補助: 更新 ").append(onOff(c, BuildConfig.APPLICATION_ID))
                .append(" / 本体 ").append(onOff(c, MAIN)).append('\n');
        sb.append("送信: 送信待ち ").append(Remote.pending()).append(" 件 / 捨てた ").append(Remote.dropped)
                .append(" 件 / ").append(Remote.lastSend).append(" / 指示 ").append(Remote.lastPoll).append('\n');
        String w = ConfirmClickService.windows();
        if (!w.isEmpty()) sb.append("窓:\n").append(w);
        String a = MainActivity.lastLayout;
        if (a != null) sb.append("更新アプリの画面: ").append(a).append('\n');
        return sb.toString();
    }

    /**
     * 入っている全アプリの一覧(読み取りだけ。止めたり無効にしたりはしない)。
     * 1行1アプリ:パッケージ名 | 表示名 | 版 | システム/更新済みシステム/自分で入れた | 有効/無効 | 窓あり(いま画面に出ている)
     */
    static String packages(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        StringBuilder sb = new StringBuilder();
        // ホームアプリの候補と既定
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo def = pm.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        String defPkg = def == null || def.activityInfo == null ? "" : def.activityInfo.packageName;
        sb.append("ホームの既定: ").append(defPkg.isEmpty() ? "なし" : defPkg)
                .append("android".equals(defPkg) ? "(未選択。選ぶ画面が出る状態)" : "").append('\n');
        sb.append("ホームの候補:");
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(home, 0)) {
            sb.append(' ').append(r.activityInfo.packageName).append('/').append(r.activityInfo.name);
        }
        sb.append('\n');
        String windows = ConfirmClickService.windowPackages();
        sb.append("いま窓があるアプリ(更新アプリのユーザー補助から): ")
                .append(windows == null ? "取れません(ユーザー補助がオフ)" : windows).append('\n');
        sb.append("パッケージ名 | 表示名 | 版 | 種類 | 状態 | 窓\n");
        java.util.List<android.content.pm.PackageInfo> list = pm.getInstalledPackages(
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
        java.util.Collections.sort(list, (a, b) -> a.packageName.compareTo(b.packageName));
        for (android.content.pm.PackageInfo p : list) {
            android.content.pm.ApplicationInfo ai = p.applicationInfo;
            String label = "";
            try { label = String.valueOf(ai.loadLabel(pm)).replace('\n', ' '); } catch (Exception ignored) {}
            long vc = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            boolean sys = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean upd = (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean stopped = (ai.flags & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0;
            boolean launcher = pm.getLaunchIntentForPackage(p.packageName) != null;
            sb.append(p.packageName).append(" | ").append(label).append(" | ")
                    .append(p.versionName).append("(").append(vc).append(") | ")
                    .append(upd ? "システム(更新済み)" : sys ? "システム" : "自分で入れた")
                    .append(launcher ? "・ランチャーに出る" : "").append(" | ")
                    .append(ai.enabled ? "有効" : "無効").append(stopped ? "・停止状態" : "").append(" | ")
                    .append(windows != null && (" " + windows + " ").contains(" " + p.packageName + " ") ? "窓あり" : "")
                    .append('\n');
        }
        sb.append("合計 ").append(list.size()).append(" 件\n");
        return sb.toString();
    }

    private static String screen(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : dm.getDisplays()) {
                Point p = new Point();
                d.getRealSize(p);
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                sb.append("画面").append(d.getDisplayId()).append(" ").append(p.x).append("x").append(p.y)
                        .append(" 密度").append(m.density).append("(").append(m.densityDpi).append("dpi) 回転")
                        .append(d.getRotation() * 90).append("  ");
            }
        } catch (Exception e) { sb.append("画面の情報を読めません(").append(e.getMessage()).append(") "); }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                WindowMetrics wmx = wm.getMaximumWindowMetrics();
                Rect b = wmx.getBounds();
                Insets in = wmx.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                sb.append("/ アプリの最大領域 ").append(b.width()).append("x").append(b.height())
                        .append(" インセット 上").append(in.top).append(" 下").append(in.bottom)
                        .append(" 左").append(in.left).append(" 右").append(in.right);
            } catch (Throwable e) { sb.append("/ インセットを読めません(").append(e.getMessage()).append(")"); }
        }
        return sb.toString();
    }
}
