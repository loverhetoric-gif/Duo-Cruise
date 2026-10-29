package com.yuki.duocruise.main;

/**
 * 左右2つの枠の位置を計算する(Android に依存しない計算だけ)。
 * 位置は {左, 上, 右, 下} の4つの数(px)で返す。
 */
final class SplitGeometry {
    private SplitGeometry() {}

    static final int SIDE_LEFT = 0, SIDE_RIGHT = 1;

    /** YouTube の幅の割合(%)の下限・上限。 */
    // 0〜100%。0 で CarPlay だけ、100 で YouTube だけの全画面になる。
    static int clampYt(int pct) { return pct < 0 ? 0 : pct > 100 ? 100 : pct; }

    /** 分割比で選べるのは 0(CarPlay だけ)・30〜70(5% きざみ)・100(YouTube だけ)。細すぎる幅は使えないので飛ばす。 */
    static final int YT_MIN = 30, YT_MAX = 70;

    /** 分割比を 1 段動かす(dir: +1 で YouTube を広く)。間の値からは、その向きの次の段へ。 */
    static int stepYt(int pct, int dir) {
        pct = clampYt(pct);
        if (dir > 0) {
            if (pct < YT_MIN) return YT_MIN;
            if (pct >= YT_MAX) return 100;
            return Math.min(YT_MAX, (pct / 5 + 1) * 5);
        }
        if (dir < 0) {
            if (pct > YT_MAX) return YT_MAX;
            if (pct <= YT_MIN) return 0;
            return Math.max(YT_MIN, ((pct + 4) / 5 - 1) * 5);
        }
        return pct;
    }

    /** 画面上の左半分(side=0)か右半分(side=1)。50:50。操作盤などで使う。 */
    static int[] half(int w, int h, int side) {
        int mid = w / 2;
        return side == SIDE_LEFT ? new int[]{0, 0, mid, h} : new int[]{mid, 0, w, h};
    }

    /** 左右の境目の x。ytPct は YouTube の幅の割合。入れ替え中は YouTube が左。 */
    private static int boundary(int w, boolean swapped, int ytPct) {
        int yt = Math.round(w * clampYt(ytPct) / 100f);
        return swapped ? yt : w - yt; // 入れ替え中: YouTube 左(幅 yt)。通常: YouTube 右(幅 yt)
    }

    /** CarPlay の枠。入れ替え中は右。ytPct は YouTube の幅の割合。 */
    static int[] car(int w, int h, boolean swapped, int ytPct) {
        int b = boundary(w, swapped, ytPct);
        return swapped ? new int[]{b, 0, w, h} : new int[]{0, 0, b, h};
    }

    /** YouTube の枠。入れ替え中は左。 */
    static int[] tv(int w, int h, boolean swapped, int ytPct) {
        int b = boundary(w, swapped, ytPct);
        return swapped ? new int[]{0, 0, b, h} : new int[]{b, 0, w, h};
    }

    /** 操作している枠(線・光を描く場所)。比率対応。 */
    static int[] activeHalf(int w, int h, int side, boolean swapped, int ytPct) {
        return tvOn(side, swapped) ? tv(w, h, swapped, ytPct) : car(w, h, swapped, ytPct);
    }

    /** 画面上のその側にあるのは YouTube か。 */
    static boolean tvOn(int side, boolean swapped) { return swapped ? side == SIDE_LEFT : side == SIDE_RIGHT; }

    /** 操作盤の位置。全体か、操作している側の半分。 */
    static int[] panel(int w, int h, boolean halfOnly, int side) {
        return halfOnly ? half(w, h, side) : new int[]{0, 0, w, h};
    }

    /** 1画面のときの、出す側の枠。どちらも横いっぱい(CarPlay は iPhone に画面全体の範囲で描かせる)。 */
    static int[] single(int w, int h, boolean tv) { return new int[]{0, 0, w, h}; }

    /** 曲がり角の小窓(YouTube)。幅は画面の 1/3、16:9。下の隅(left なら左下、普段は右下)。 */
    static int[] pip(int w, int h, boolean left) {
        int pw = Math.round(w / 3f), ph = Math.min(h, Math.round(pw * 9 / 16f)), m = 16;
        return left ? new int[]{m, h - m - ph, m + pw, h - m} : new int[]{w - m - pw, h - m - ph, w - m, h - m};
    }

    /**
     * 1画面のときの、出さない側の置き場所(画面の右の外)。大きさは画面と同じ。
     * 隠す(INVISIBLE)と CarPlay の映像の面が消えて作り直しになるため、外へ出すだけにする。YouTube の再生も止めない。
     */
    static int[] offscreen(int w, int h) { return new int[]{w, 0, w + w, h}; }

    static String str(int[] r) { return (r[2] - r[0]) + "×" + (r[3] - r[1]) + "@" + r[0] + "," + r[1]; }
}
