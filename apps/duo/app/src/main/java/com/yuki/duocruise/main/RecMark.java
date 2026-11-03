package com.yuki.duocruise.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * 画面収録中に出す REC マーク。右上に赤い丸と「REC」、1 秒周期でゆっくり点滅して「録っている」と伝える。
 * 画面いっぱいに重ねるが、描くのは右上の小さな印だけ。操作は邪魔しない(タッチは下へ素通り)。
 */
final class RecMark extends View {
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    RecMark(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        dot.setColor(0xFFFF3B30);          // 録画の赤
        text.setColor(0xFFFFFFFF);
        text.setFakeBoldText(true);
        text.setTextSize(13 * dp);
        setVisibility(GONE);
    }

    @Override protected void onDraw(Canvas cv) {
        if (getVisibility() != VISIBLE) return;
        float a = (SystemClock.uptimeMillis() % 1000) < 500 ? 1f : 0.3f; // 点滅
        float r = 5 * dp, cx = getWidth() - 58 * dp, cy = 18 * dp;
        dot.setAlpha(Math.round(255 * a));
        cv.drawCircle(cx, cy, r, dot);
        text.setAlpha(Math.round(255 * a));
        cv.drawText("REC", cx + 10 * dp, cy + text.getTextSize() * 0.36f, text);
        postInvalidateDelayed(120);
    }
}
