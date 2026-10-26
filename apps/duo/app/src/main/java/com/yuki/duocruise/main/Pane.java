package com.yuki.duocruise.main;

import android.view.KeyEvent;
import android.view.View;

/**
 * 左右の枠に入る部品(CarPlay / TV 版 YouTube)を、DuoActivity から同じ形で扱うための窓口。
 * 本物の部品(B の CarPlayPane、D の TvPane)は Panes が見つけて、この形に合わせる。
 * 見つからない間は、同じ約束の仮の部品(StubCarPane / StubTvPane)が入る。
 */
interface Pane {
    View view();
    /** 画面ができたとき1回(CarPlay は接続の開始)。 */
    void start();
    void resume();
    void pause();
    /** 画面を閉じるとき1回。 */
    void destroy();
    /** 操作中のとき、キーが届く。扱ったら true。 */
    boolean onKey(KeyEvent e);
    String status();
    /** 記録シートの指示(例「tv-status」)。結果の1行を返す。扱わない部品は null。 */
    String command(String cmd);
    /** 「CarPlay」「YouTube」。 */
    String name();
    /** 本物の部品なら true、仮の部品なら false。 */
    boolean real();
}
