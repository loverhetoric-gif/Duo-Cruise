package com.yuki.duocruise.authcheck;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.system.StructStat;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * OTTO の中を「読むだけ」で調べて、確かめた事実だけを画面に出す。
 * I2C の部品（チップ）には一切問い合わせない（書き込みも読み取りもしない）。
 * 読むのは、OS が用意している一覧（/dev、/sys、アプリの一覧）だけ。
 */
public class MainActivity extends Activity {

    private TextView out;
    private final StringBuilder sb = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        out = new TextView(this);
        out.setTextColor(Color.parseColor("#E8EEF8"));
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(15);
        out.setPadding(24, 16, 24, 16);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#0E1420"));
        sv.setFocusable(true);
        sv.addView(out);
        setContentView(sv);
        sv.requestFocus();
        new Thread(this::run).start();
    }

    private void line(String s) {
        sb.append(s).append('\n');
        final String text = sb.toString();
        runOnUiThread(() -> out.setText(text));
    }

    private void run() {
        line("■ Duo Cruise 認証チェック（読むだけ。チップには触れない）");
        line("機種 " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                + " / 種類 " + Build.TYPE + " / 鍵 " + Build.TAGS);
        line("SELinux " + exec("getenforce") + " / enforce=" + read("/sys/fs/selinux/enforce"));
        line("");

        // /dev にある I2C の口（ファイルがあるか・誰が使えるか）だけを見る。開かない。
        String[] dev = new File("/dev").list((d, n) -> n.startsWith("i2c"));
        if (dev == null) {
            line("■ I2C の口: 一覧が読めない");
        } else {
            Arrays.sort(dev);
            line("■ I2C の口: " + dev.length + " 個（ファイルがあることだけを確認。開いていない）");
            for (String n : dev) line("  /dev/" + n + "  " + stat("/dev/" + n));
        }
        line("");

        // OS が名前を登録している I2C 部品の一覧。OS が持っている情報を読むだけ。
        String[] sys = new File("/sys/bus/i2c/devices").list();
        if (sys == null) {
            line("■ OS に登録されている I2C 部品: 一覧が読めない");
        } else {
            Arrays.sort(sys);
            line("■ OS に登録されている I2C 部品（「口番号-番地 名前」）");
            for (String n : sys) line("  " + n + "  " + read("/sys/bus/i2c/devices/" + n + "/name"));
        }
        line("");

        // 名前で探すだけ。そのアプリが実際に CarPlay を受けているかは、ここでは分からない。
        line("■ 名前に multiplay / carplay / autokit / otto を含むアプリ");
        line("  （名前で探しただけ。CarPlay を受けているかは未確認）");
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        int hits = 0;
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            String label = String.valueOf(ri.loadLabel(pm));
            String key = (pkg + " " + label).toLowerCase(Locale.ROOT);
            if (!(key.contains("multiplay") || key.contains("carplay") || key.contains("autokit") || key.contains("otto"))) continue;
            hits++;
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                ApplicationInfo ai = pi.applicationInfo;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                line("  " + label + " (" + pkg + ") / v" + pi.versionName
                        + " / " + (system ? "本体組み込み" : "後から入れたもの")
                        + " / 共有ID " + pi.sharedUserId + " / uid " + ai.uid);
            } catch (Exception e) {
                line("  " + label + " (" + pkg + ") / 詳細が読めない");
            }
        }
        if (hits == 0) line("  見つからない");
        line("");

        line("■ 未確認のこと");
        line("  ・0x10 / 0x11 にある部品が何か（名前・種類は OS に登録されていない）");
        line("  ・どのアプリが CarPlay を受けているか");
        line("  この画面では、これらを確かめる操作はしていません。");
    }

    private static String stat(String path) {
        try {
            StructStat s = Os.stat(path);
            return String.format(Locale.ROOT, "mode=%o uid=%d gid=%d", s.st_mode & 07777, s.st_uid, s.st_gid);
        } catch (Exception e) {
            return "状態が読めない";
        }
    }

    private static String read(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "読めない";
        }
    }

    private static String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s = r.readLine();
                return s == null ? "(なし)" : s.trim();
            }
        } catch (Exception e) {
            return "実行できない";
        }
    }
}
