package com.yuki.duocruise.main;

/**
 * 曲がり角の案内から、いま何をするかを決める(Duo Cruise Unlimited Edition)。画面には触らない(テストできるように)。
 *
 * 届くのは「いま向かっている曲がり角の番号」と「そこまでの距離」。曲がり終えると番号が次へ進み、距離が伸びる。
 * ・表示・地図・音量: 距離がそれぞれの設定以下の間だけ「要る」。戻すときの待ちは呼ぶ側が持つ
 * ・光: 曲がり角ごとに 1 回だけ(設定の距離を切った瞬間。初めから内側なら、その曲がり角に変わった瞬間)
 * ・到着: 到着に変わった瞬間に 1 回だけ
 */
final class TurnPlan {
    /** 設定(m)。 */
    int hintDist = 1000, mapDist = 300, lightDist = 200, duckDist = 150;

    /** 案内を出す・地図を出す・音量を下げる、が要るか。 */
    boolean hint, map, duck;
    /** 今回の知らせで光らせる側("L"/"R")。光らせないなら null。 */
    String light;
    /** 今回の知らせで到着した。 */
    boolean arrivedNow;

    private int litIndex = Integer.MIN_VALUE;
    private boolean wasArrived;

    /**
     * 新しい知らせ。guiding=案内の途中、arrived=到着、index=曲がり角の番号、dist=そこまでの距離(m、不明は -1)、
     * side=曲がる側("L"/"R"/null)。
     */
    void update(boolean guiding, boolean arrived, int index, long dist, String side) {
        light = null;
        arrivedNow = arrived && !wasArrived;
        wasArrived = arrived;
        boolean known = guiding && index >= 0 && dist >= 0;
        hint = known && dist <= hintDist;
        map = known && dist <= mapDist;
        duck = known && dist <= duckDist;
        if (known && side != null && dist <= lightDist && index != litIndex) {
            light = side;
            litIndex = index;
        }
        if (!guiding) litIndex = Integer.MIN_VALUE; // 次のルートでは、同じ番号でもまた光らせる
    }

    /** CarPlay が止まった・ルートが消えた。 */
    void clear() {
        hint = map = duck = arrivedNow = false;
        light = null;
        litIndex = Integer.MIN_VALUE;
        wasArrived = false;
    }
}
