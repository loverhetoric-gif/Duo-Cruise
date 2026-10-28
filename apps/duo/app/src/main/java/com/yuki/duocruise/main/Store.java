package com.yuki.duocruise.main;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定の保存。 */
final class Store {
    private Store() {}

    static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences("duo", Context.MODE_PRIVATE); }

    /** true のとき、CarPlay を右・YouTube を左に置く。 */
    static boolean swapped(Context c) { return p(c).getBoolean("swapped", false); }
    static void setSwapped(Context c, boolean v) { p(c).edit().putBoolean("swapped", v).apply(); }

    /** いま操作している側(画面上の左右。SplitGeometry.SIDE_LEFT / SIDE_RIGHT)。最初は CarPlay(左)。 */
    static int side(Context c) { return p(c).getInt("side", SplitGeometry.SIDE_LEFT); }
    static void setSide(Context c, int v) { p(c).edit().putInt("side", v).apply(); }

    /** ボタンの割り当て(KeyMap の文字列)。未設定なら初期値。keymap4＝1画面の切り替えを足した初期値(それより前の保存は使わない)。 */
    static KeyMap keyMap(Context c) { return KeyMap.parse(p(c).getString("keymap4", KeyMap.DEFAULTS)); }
    static void setKeyMap(Context c, KeyMap m) { p(c).edit().putString("keymap4", m.serialize()).apply(); }

    /** true のとき1画面(操作中の側だけを出す)。 */
    static boolean single(Context c) { return p(c).getBoolean("single", false); }
    static void setSingle(Context c, boolean v) { p(c).edit().putBoolean("single", v).apply(); }

    /** 長押しとみなす時間(ms)。 */
    static long longMs(Context c) { return p(c).getLong("longMs", KeyMap.DEFAULT_LONG_MS); }

    /** 操作盤を、操作している側の半分だけに出すか(false＝画面全体)。 */
    static boolean panelHalf(Context c) { return p(c).getBoolean("panelHalf", false); }
    static void setPanelHalf(Context c, boolean v) { p(c).edit().putBoolean("panelHalf", v).apply(); }

    // ───────── 左右の比率(YouTube の幅の割合 %)と、ナビ音声での自動変更 ─────────

    /** 普段の、YouTube の幅の割合(20〜100%)。50＝半々、100＝YouTube 全画面。 */
    static int splitYt(Context c) { return SplitGeometry.clampYt(p(c).getInt("splitYt", 50)); }
    static void setSplitYt(Context c, int v) { p(c).edit().putInt("splitYt", SplitGeometry.clampYt(v)).apply(); }

    /** ナビ音声が流れている間の、YouTube の幅の割合(%)。 */
    static int navYt(Context c) { return SplitGeometry.clampYt(p(c).getInt("navYt", 50)); }
    static void setNavYt(Context c, int v) { p(c).edit().putInt("navYt", SplitGeometry.clampYt(v)).apply(); }

    /** ナビ音声に合わせて比率を自動で変えるか。 */
    static boolean autoSplit(Context c) { return p(c).getBoolean("autoSplit", false); }
    static void setAutoSplit(Context c, boolean v) { p(c).edit().putBoolean("autoSplit", v).apply(); }

    /** ナビ音声のとき、左右も自動で入れ替えるか(マップを右へ)。 */
    static boolean autoSwap(Context c) { return p(c).getBoolean("autoSwapNav", false); }
    static void setAutoSwap(Context c, boolean v) { p(c).edit().putBoolean("autoSwapNav", v).apply(); }

    /** ナビ音声が切れてから、元の比率・配置に戻すまでの待ち(ms)。 */
    static long navHoldMs(Context c) { return p(c).getLong("navHoldMs", 5000); }
    static void setNavHoldMs(Context c, long v) { p(c).edit().putLong("navHoldMs", Math.max(0, v)).apply(); }

    // ───────── ネオンの色(メインビューのイベント発光) ─────────
    /** ターゲット切替の色(既定: 白)。 */
    static int neonTarget(Context c) { return p(c).getInt("neonTarget", Theme.NEON_WHITE); }
    /** 接続完了の色(既定: 青)。 */
    static int neonOk(Context c) { return p(c).getInt("neonOk", Theme.NEON_NAV); }
    /** 接続不良の色(既定: 赤)。 */
    static int neonAlert(Context c) { return p(c).getInt("neonAlert", Theme.NEON_ALERT); }
    static void setNeon(Context c, int which, int color) {
        p(c).edit().putInt(which == 0 ? "neonTarget" : which == 1 ? "neonOk" : "neonAlert", color | 0xFF000000).apply();
    }
    static int neon(Context c, int which) { return which == 0 ? neonTarget(c) : which == 1 ? neonOk(c) : neonAlert(c); }

    /** 各イベントの発光の強さ(0〜100%)。既定 55%(控えめ)。 */
    static int neonLevel(Context c, int which) {
        return p(c).getInt(which == 0 ? "neonLvT" : which == 1 ? "neonLvO" : "neonLvA", 55);
    }
    static void setNeonLevel(Context c, int which, int lv) {
        p(c).edit().putInt(which == 0 ? "neonLvT" : which == 1 ? "neonLvO" : "neonLvA", Math.max(0, Math.min(100, lv))).apply();
    }
}
