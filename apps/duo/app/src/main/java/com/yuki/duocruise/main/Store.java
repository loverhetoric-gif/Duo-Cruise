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

    /**
     * ボタンの割り当て(KeyMap の文字列)。未設定なら初期値。
     * keymap5＝ナビボタンを使う 2026-10-29 の初期値(それより前の保存 keymap4 は使わない)。
     */
    static KeyMap keyMap(Context c) {
        KeyMap m = KeyMap.parse(p(c).getString("keymap5", KeyMap.DEFAULTS));
        m.addMissingDefaults();
        return m;
    }
    static void setKeyMap(Context c, KeyMap m) { p(c).edit().putString("keymap5", m.serialize()).apply(); }

    /** true のとき1画面(操作中の側だけを出す)。 */
    /** 起動音を鳴らすか(初期値: 鳴らす)。 */
    /** モードの帯の一行説明を、そのモードで初めて出すときだけ添える(見たら印を付ける)。 */
    static boolean modeSeen(Context c, String key) { return p(c).getBoolean("modeSeen_" + key, false); }
    static void setModeSeen(Context c, String key) { p(c).edit().putBoolean("modeSeen_" + key, true).apply(); }

    static boolean bootSound(Context c) { return p(c).getBoolean("bootSound", true); }
    static void setBootSound(Context c, boolean v) { p(c).edit().putBoolean("bootSound", v).apply(); }

    /** はじめての案内を見終えたか。 */
    static boolean guided(Context c) { return p(c).getBoolean("guided", false); }
    static void setGuided(Context c, boolean v) { p(c).edit().putBoolean("guided", v).apply(); }

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

    /** 曲がる方向の光の色(既定: アンバー。方向指示器と同じ色味)。Duo Cruise Unlimited Edition。 */
    static int neonTurn(Context c) { return p(c).getInt("neonTurn", NEON_TURN); }
    static final int NEON_TURN = 0xFFFFC24F;

    private static String neonKey(int which) {
        return which == 0 ? "neonTarget" : which == 1 ? "neonOk" : which == 3 ? "neonTurn" : "neonAlert";
    }
    private static String levelKey(int which) {
        return which == 0 ? "neonLvT" : which == 1 ? "neonLvO" : which == 3 ? "neonLvTurn" : "neonLvA";
    }

    /** which: 0=ターゲット切替 / 1=接続完了 / 2=接続不良 / 3=曲がる方向(Unlimited Edition)。 */
    static void setNeon(Context c, int which, int color) {
        p(c).edit().putInt(neonKey(which), color | 0xFF000000).apply();
    }
    static int neon(Context c, int which) {
        return which == 0 ? neonTarget(c) : which == 1 ? neonOk(c) : which == 3 ? neonTurn(c) : neonAlert(c);
    }

    /** 各イベントの発光の強さ(0〜100%)。既定 55%(控えめ)。 */
    static int neonLevel(Context c, int which) { return p(c).getInt(levelKey(which), 55); }
    static void setNeonLevel(Context c, int which, int lv) {
        p(c).edit().putInt(levelKey(which), Math.max(0, Math.min(100, lv))).apply();
    }

    // ───────── 曲がり角の案内(Duo Cruise Unlimited Edition だけが使う) ─────────
    // 距離はすべて m。初期値は、出しゃばらない側(画面と音を勝手に変えるものはオフ)。

    /** 曲がり角の案内の表示: 0=表示しない / 1=地図が見えないときだけ / 2=常に。 */
    static int hintMode(Context c) { return p(c).getInt("rtHint", 1); }
    static void setHintMode(Context c, int v) { p(c).edit().putInt("rtHint", ((v % 3) + 3) % 3).apply(); }
    /** 案内を出し始める距離。 */
    static int hintDist(Context c) { return p(c).getInt("rtHintDist", 1000); }
    static void setHintDist(Context c, int v) { p(c).edit().putInt("rtHintDist", clampDist(v)).apply(); }

    /** 曲がり角が近づいたら地図を表示するか。 */
    static boolean mapFront(Context c) { return p(c).getBoolean("rtMap", false); }
    static void setMapFront(Context c, boolean v) { p(c).edit().putBoolean("rtMap", v).apply(); }
    static int mapDist(Context c) { return p(c).getInt("rtMapDist", 300); }
    static void setMapDist(Context c, int v) { p(c).edit().putInt("rtMapDist", clampDist(v)).apply(); }
    /** 地図を表示している間の、YouTube の幅の割合(%)。 */
    static int mapYt(Context c) { return SplitGeometry.clampYt(p(c).getInt("rtMapYt", 30)); }
    static void setMapYt(Context c, int v) { p(c).edit().putInt("rtMapYt", SplitGeometry.clampYt(v)).apply(); }
    /** 曲がり終えてから元に戻すまでの待ち(ms)。 */
    static long mapHoldMs(Context c) { return p(c).getLong("rtMapHold", 3000); }
    static void setMapHoldMs(Context c, long v) { p(c).edit().putLong("rtMapHold", Math.max(0, v)).apply(); }

    // ───────── 運転中の自動の切り替え ─────────
    /** 止まったら(信号待ち・渋滞)YouTube を大きくするか。 */
    static boolean stopOn(Context c) { return p(c).getBoolean("drStop", true); }
    static void setStopOn(Context c, boolean v) { p(c).edit().putBoolean("drStop", v).apply(); }
    static int stopYt(Context c) { return SplitGeometry.clampYt(p(c).getInt("drStopYt", 70)); }
    static void setStopYt(Context c, int v) { p(c).edit().putInt("drStopYt", SplitGeometry.clampYt(v)).apply(); }
    /** 駐車したら YouTube を全画面にするか。駐車とみなす止まっている時間(ms)。 */
    static boolean parkOn(Context c) { return p(c).getBoolean("drPark", true); }
    static void setParkOn(Context c, boolean v) { p(c).edit().putBoolean("drPark", v).apply(); }
    static long parkMs(Context c) { return p(c).getLong("drParkMs", 120_000); }
    static void setParkMs(Context c, long v) { p(c).edit().putLong("drParkMs", v).apply(); }
    /** 昼(日の出〜日の入り)はネオンを濃く・強くするか。 */
    static boolean dayNeon(Context c) { return p(c).getBoolean("drDay", true); }
    static void setDayNeon(Context c, boolean v) { p(c).edit().putBoolean("drDay", v).apply(); }
    /** Y!カーナビの警告を縁の光(と YouTube の音を下げる)で知らせるか。 */
    static boolean naviAlert(Context c) { return p(c).getBoolean("drAlert", true); }
    static void setNaviAlert(Context c, boolean v) { p(c).edit().putBoolean("drAlert", v).apply(); }

    /** 一本道(次の曲がり角が遠い)のあいだ、YouTube を大きくするか。 */
    static boolean straightOn(Context c) { return p(c).getBoolean("rtStraight", false); }
    static void setStraightOn(Context c, boolean v) { p(c).edit().putBoolean("rtStraight", v).apply(); }
    /** 次の曲がり角がこの距離より遠ければ一本道とみなす。 */
    static int straightDist(Context c) { return p(c).getInt("rtStraightDist", 2000); }
    static void setStraightDist(Context c, int v) { p(c).edit().putInt("rtStraightDist", clampDist(v)).apply(); }
    /** 一本道のあいだの、YouTube の幅の割合(%)。 */
    static int straightYt(Context c) { return SplitGeometry.clampYt(p(c).getInt("rtStraightYt", 70)); }
    static void setStraightYt(Context c, int v) { p(c).edit().putInt("rtStraightYt", SplitGeometry.clampYt(v)).apply(); }

    /** 曲がる方向を縁の光で知らせるか。 */
    static boolean turnLight(Context c) { return p(c).getBoolean("rtLight", true); }
    static void setTurnLight(Context c, boolean v) { p(c).edit().putBoolean("rtLight", v).apply(); }
    static int lightDist(Context c) { return p(c).getInt("rtLightDist", 200); }
    static void setLightDist(Context c, int v) { p(c).edit().putInt("rtLightDist", clampDist(v)).apply(); }

    /** 曲がり角の手前で YouTube の音量を下げるか。 */
    static boolean turnDuck(Context c) { return p(c).getBoolean("rtDuck", false); }
    static void setTurnDuck(Context c, boolean v) { p(c).edit().putBoolean("rtDuck", v).apply(); }
    static int duckDist(Context c) { return p(c).getInt("rtDuckDist", 150); }
    static void setDuckDist(Context c, int v) { p(c).edit().putInt("rtDuckDist", clampDist(v)).apply(); }
    /** 下げたときの音量(%)。 */
    static int duckLevel(Context c) { return p(c).getInt("rtDuckLv", 30); }
    static void setDuckLevel(Context c, int v) { p(c).edit().putInt("rtDuckLv", Math.max(0, Math.min(100, v))).apply(); }

    /** 到着したら YouTube を全画面にするか。 */
    static boolean arriveFull(Context c) { return p(c).getBoolean("rtArrive", false); }
    static void setArriveFull(Context c, boolean v) { p(c).edit().putBoolean("rtArrive", v).apply(); }

    // ───────── 車の設定(メニュー › システム) ─────────

    /** 右ハンドルか(false＝左ハンドル)。運転席がどちらの側かを決める。CarPlay とナビ案内中の地図の配置に使う。 */
    static boolean rightHand(Context c) { return p(c).getBoolean("rightHand", true); }
    static void setRightHand(Context c, boolean v) { p(c).edit().putBoolean("rightHand", v).apply(); }

    /** 距離をマイルで出すか(false＝キロメートル)。初期値は端末の地域(米・英などはマイル)。 */
    static boolean miles(Context c) {
        String cc = java.util.Locale.getDefault().getCountry();
        boolean def = cc.equals("US") || cc.equals("GB") || cc.equals("LR") || cc.equals("MM");
        return p(c).getBoolean("miles", def);
    }
    static void setMiles(Context c, boolean v) { p(c).edit().putBoolean("miles", v).apply(); }

    /** 距離(m)を、設定の単位の文字にする。km: 「300 m」「1.2 km」、マイル: 「500 ft」「0.3 mi」。 */
    static String distance(Context c, long m) {
        if (m < 0) return "";
        if (miles(c)) {
            double mi = m / 1609.344;
            if (mi < 0.1) return Tx.s(R.string.feet, Math.round(m * 3.28084 / 50.0) * 50);
            return mi >= 10 ? Math.round(mi) + " mi" : String.format(java.util.Locale.ROOT, "%.1f mi", mi);
        }
        if (m >= 10_000) return (m / 1000) + " km";
        if (m >= 1_000) return String.format(java.util.Locale.ROOT, "%.1f km", m / 1000.0);
        return Tx.s(R.string.meters, m >= 100 ? (m + 25) / 50 * 50 : (m + 5) / 10 * 10);
    }

    /** 距離の設定の範囲(50〜2000 m)。 */
    static final int DIST_MIN = 50, DIST_MAX = 2000;
    static int clampDist(int v) { return Math.max(DIST_MIN, Math.min(DIST_MAX, v)); }

    // ───────── 走行の記録(Unlimited Edition・本人の検証用。Trace) ─────────
    /** 走行の記録を取るか(次の起動でも続く)。 */
    static boolean traceOn(Context c) { return p(c).getBoolean("traceOn", false); }
    static void setTraceOn(Context c, boolean v) { p(c).edit().putBoolean("traceOn", v).apply(); }
    /** 記録に全画面スクショも含めるか(画面の許可が要る)。 */
    static boolean traceShots(Context c) { return p(c).getBoolean("traceShots", false); }
    static void setTraceShots(Context c, boolean v) { p(c).edit().putBoolean("traceShots", v).apply(); }
}
