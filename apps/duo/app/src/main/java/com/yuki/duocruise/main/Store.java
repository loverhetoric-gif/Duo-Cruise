package com.yuki.duocruise.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 設定の保存と、画面・logcat(DUO)への記録。 */
final class Store {
    static final int SIDE_LEFT = 0, SIDE_RIGHT = 1;

    interface Listener { void changed(); }
    static volatile Listener listener;

    private static final List<String> lines = new ArrayList<>();
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);

    private Store() {}

    static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences("duo", Context.MODE_PRIVATE); }

    /** 左に置くアプリ(パッケージ名/クラス名)。未設定なら空。 */
    static String leftApp(Context c) { return p(c).getString("leftApp", ""); }
    static void setLeftApp(Context c, String v) { p(c).edit().putString("leftApp", v).apply(); }

    /** true のとき、CarPlay を右・YouTube を左に置く。 */
    static boolean swapped(Context c) { return p(c).getBoolean("swapped", false); }
    static void setSwapped(Context c, boolean v) { p(c).edit().putBoolean("swapped", v).apply(); }

    /** いま操作している側(画面上の左右)。 */
    static int side(Context c) { return p(c).getInt("side", SIDE_RIGHT); }
    static void setSide(Context c, int v) { p(c).edit().putInt("side", v).apply(); }

    /** 長押しで操作側を切り替えるキー。0 は未登録。 */
    static int switchKey(Context c) { return p(c).getInt("switchKey", 0); }
    static void setSwitchKey(Context c, int v) { p(c).edit().putInt("switchKey", v).apply(); }

    /** 長押しで左右を入れ替えるキー。0 は未登録。 */
    static int swapKey(Context c) { return p(c).getInt("swapKey", 0); }
    static void setSwapKey(Context c, int v) { p(c).edit().putInt("swapKey", v).apply(); }

    /** 窓の出し方(0=位置指定のみ / 1=位置指定+自由窓 / 2=隣に並べる)。端末に合うものを診断で選ぶ。 */
    static int method(Context c) { return p(c).getInt("method", 1); }
    static void setMethod(Context c, int v) { p(c).edit().putInt("method", v).apply(); }

    static synchronized void log(String t) {
        lines.add(0, F.format(new Date()) + "  " + t);
        while (lines.size() > 150) lines.remove(lines.size() - 1);
        Log.i("DUO", t);
        Listener l = listener;
        if (l != null) l.changed();
    }

    static synchronized List<String> snapshot() { return new ArrayList<>(lines); }
}
