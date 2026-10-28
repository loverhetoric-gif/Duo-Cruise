package com.yuki.duocruise.main;

/**
 * 左右2つの枠の位置を計算する(Android に依存しない計算だけ)。
 * 位置は {左, 上, 右, 下} の4つの数(px)で返す。
 */
final class SplitGeometry {
    private SplitGeometry() {}

    static final int SIDE_LEFT = 0, SIDE_RIGHT = 1;

    /** YouTube の幅の割合(%)の下限・上限。 */
    // 通常の分割比は、CarPlay を完全には消さない(必ず 15% 以上残す)。
    // YouTube を全画面にしたいときは「1画面」モードを使う。
    static int clampYt(int pct) { return pct < 20 ? 20 : pct > 85 ? 85 : pct; }

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

    /**
     * 1画面のときの、出す側の枠。YouTube は横いっぱい。
     * CarPlay は iPhone と決めた大きさ(半分の幅)のまま真ん中に置く(引き伸ばすと絵がゆがむため)。
     */
    static int[] single(int w, int h, boolean tv) {
        if (tv) return new int[]{0, 0, w, h};
        int hw = w / 2, left = (w - hw) / 2;
        return new int[]{left, 0, left + hw, h};
    }

    /**
     * 1画面のときの、出さない側の置き場所(画面の右の外)。大きさは半分のまま変えない。
     * 隠す(INVISIBLE)と CarPlay の映像の面が消えて作り直しになるため、外へ出すだけにする。YouTube の再生も止めない。
     */
    static int[] offscreen(int w, int h) { return new int[]{w, 0, w + w / 2, h}; }

    static String str(int[] r) { return (r[2] - r[0]) + "×" + (r[3] - r[1]) + "@" + r[0] + "," + r[1]; }
}
