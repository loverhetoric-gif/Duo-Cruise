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

    /** ボタンの割り当て(KeyMap の文字列)。未設定なら初期値。keymap3＝車での実測後の初期値(長押しの頃の keymap2 は使わない)。 */
    static KeyMap keyMap(Context c) { return KeyMap.parse(p(c).getString("keymap3", KeyMap.DEFAULTS)); }
    static void setKeyMap(Context c, KeyMap m) { p(c).edit().putString("keymap3", m.serialize()).apply(); }

    /** 長押しとみなす時間(ms)。 */
    static long longMs(Context c) { return p(c).getLong("longMs", KeyMap.DEFAULT_LONG_MS); }

    /** 操作盤を、操作している側の半分だけに出すか(false＝画面全体)。 */
    static boolean panelHalf(Context c) { return p(c).getBoolean("panelHalf", false); }
    static void setPanelHalf(Context c, boolean v) { p(c).edit().putBoolean("panelHalf", v).apply(); }
}
