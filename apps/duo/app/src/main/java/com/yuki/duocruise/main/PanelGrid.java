package com.yuki.duocruise.main;

/**
 * 操作盤のボタンの並べ方と、ダイヤルでの移り方(Android に依存しない計算だけ)。
 * ・幅が広い(1480)ときは横長の格子、狭い(740)ときは2列の格子
 * ・どの大きさでも縦に収める(はみ出すときはボタンを低くする。下限を下回る並べ方は作らない)
 * ・左右(回す)は横の順に全部を一巡、上下(倒す)は縦の順に全部を一巡
 */
final class PanelGrid {
    static final int NEXT = 0, PREV = 1, RIGHT = 2, LEFT = 3, DOWN = 4, UP = 5;

    /** 寸法(dp)。 */
    static final float PAD = 16, HEADER = 52, GAP = 10, MIN_TILE_H = 48, MAX_TILE_H = 112, WIDE_DP = 1000, NARROW_DP = 560;

    final int n, cols, rows;
    /** ボタンを置く範囲と、1つのボタンの大きさ(px)。 */
    final int areaX, areaY, areaW, areaH, tileW, tileH, gap;

    private PanelGrid(int n, int cols, int rows, int areaX, int areaY, int areaW, int areaH, int tileW, int tileH, int gap) {
        this.n = n; this.cols = cols; this.rows = rows;
        this.areaX = areaX; this.areaY = areaY; this.areaW = areaW; this.areaH = areaH;
        this.tileW = tileW; this.tileH = tileH; this.gap = gap;
    }

    /** 何列にするか。 */
    static int columns(int wPx, float dp, int n) {
        float wDp = wPx / dp;
        if (n <= 1) return 1;
        if (wDp >= WIDE_DP) return Math.min(5, n <= 4 ? n : (n + 1) / 2);
        if (wDp >= NARROW_DP) return 2;
        return 1;
    }

    /**
     * w×h(px)の操作盤に n 個のボタンを並べる。footerPx は下に取っておく高さ(記録の表示など)。
     * ボタンの範囲は見出しの下から始まる。
     */
    static PanelGrid compute(int w, int h, int n, float dp, int footerPx) {
        int pad = Math.round(PAD * dp), head = Math.round(HEADER * dp), gap = Math.round(GAP * dp);
        int cols = columns(w, dp, n);
        int rows = Math.max(1, (n + cols - 1) / cols);
        int availW = Math.max(0, w - pad * 2);
        int availH = Math.max(0, h - pad * 2 - head - footerPx);
        int tileW = Math.max(0, (availW - gap * (cols - 1)) / cols);
        int tileH = Math.max(0, (availH - gap * (rows - 1)) / rows);
        tileH = Math.min(tileH, Math.round(MAX_TILE_H * dp));
        int usedH = tileH * rows + gap * (rows - 1);
        int areaY = pad + head + Math.max(0, (availH - usedH) / 2);
        return new PanelGrid(n, cols, rows, pad, areaY, availW, usedH, tileW, tileH, gap);
    }

    /** ボタンが押しやすい高さで収まっているか。 */
    boolean fits(float dp) { return tileH >= Math.round(MIN_TILE_H * dp) && tileW > 0; }

    /** i 番目のボタンの位置 {左, 上, 右, 下}(px)。最後の行が欠けるときは中央に寄せる。 */
    int[] rect(int i) {
        int r = i / cols, c = i % cols;
        int inRow = Math.min(cols, n - r * cols);
        int rowW = tileW * inRow + gap * (inRow - 1);
        int x0 = areaX + (areaW - rowW) / 2;
        int x = x0 + c * (tileW + gap);
        int y = areaY + r * (tileH + gap);
        return new int[]{x, y, x + tileW, y + tileH};
    }

    /** いまの i から、向き dir へ動いた先。必ず一巡する(端で止まらない)。 */
    int move(int i, int dir) {
        if (n <= 0) return 0;
        i = Math.max(0, Math.min(n - 1, i));
        switch (dir) {
            case NEXT: case RIGHT: return (i + 1) % n;
            case PREV: case LEFT: return (i - 1 + n) % n;
            case DOWN: case UP: {
                int[] order = columnOrder();
                int pos = 0;
                for (int k = 0; k < n; k++) if (order[k] == i) pos = k;
                pos = dir == DOWN ? (pos + 1) % n : (pos - 1 + n) % n;
                return order[pos];
            }
            default: return i;
        }
    }

    /** 縦の順(列ごとに上から下)。 */
    int[] columnOrder() {
        int[] o = new int[n];
        int k = 0;
        for (int c = 0; c < cols; c++)
            for (int r = 0; r < rows; r++) {
                int i = r * cols + c;
                if (i < n) o[k++] = i;
            }
        return o;
    }
}
