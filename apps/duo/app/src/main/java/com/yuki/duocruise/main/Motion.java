package com.yuki.duocruise.main;

import android.animation.TimeInterpolator;

/**
 * 画面の動きの緩急(本人の指示: 遅い→速い→遅い、終わりに少しだけバウンド)。
 * 前の 72% で目標を 4% だけ行き過ぎるまで滑らかに加速・減速し(速さ 0 で折り返す)、残りでゆっくり戻って止まる。
 */
final class Motion {
    private Motion() {}

    static final long MS = 520;
    private static final float OVER = 0.04f, SPLIT = 0.72f;

    static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        if (t < SPLIT) {
            float p = t / SPLIT;
            return (1 + OVER) * p * p * p * (p * (p * 6 - 15) + 10);
        }
        float p = (t - SPLIT) / (1 - SPLIT);
        return 1 + OVER - OVER * p * p * (3 - 2 * p);
    }

    static final TimeInterpolator EASE = Motion::ease;
}
