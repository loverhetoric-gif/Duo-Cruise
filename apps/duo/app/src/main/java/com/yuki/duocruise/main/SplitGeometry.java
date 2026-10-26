package com.yuki.duocruise.main;

/**
 * 左右2つの枠の位置を計算する(Android に依存しない計算だけ)。
 * 位置は {左, 上, 右, 下} の4つの数(px)で返す。
 */
final class SplitGeometry {
    private SplitGeometry() {}

    static final int SIDE_LEFT = 0, SIDE_RIGHT = 1;

    /** 画面上の左半分(side=0)か右半分(side=1)。幅が奇数でも隙間・重なりを作らない。 */
    static int[] half(int w, int h, int side) {
        int mid = w / 2;
        return side == SIDE_LEFT ? new int[]{0, 0, mid, h} : new int[]{mid, 0, w, h};
    }

    /** CarPlay の枠。入れ替え中は右。 */
    static int[] car(int w, int h, boolean swapped) { return half(w, h, swapped ? SIDE_RIGHT : SIDE_LEFT); }

    /** YouTube の枠。入れ替え中は左。 */
    static int[] tv(int w, int h, boolean swapped) { return half(w, h, swapped ? SIDE_LEFT : SIDE_RIGHT); }

    /** 画面上のその側にあるのは YouTube か。 */
    static boolean tvOn(int side, boolean swapped) { return swapped ? side == SIDE_LEFT : side == SIDE_RIGHT; }

    /** 操作盤の位置。全体か、操作している側の半分。 */
    static int[] panel(int w, int h, boolean halfOnly, int side) {
        return halfOnly ? half(w, h, side) : new int[]{0, 0, w, h};
    }

    static String str(int[] r) { return (r[2] - r[0]) + "×" + (r[3] - r[1]) + "@" + r[0] + "," + r[1]; }
}
