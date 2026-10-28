package com.yuki.duocruise.main;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * 左右の枠に入る部品(CarPlay / TV 版 YouTube)を、DuoActivity から同じ形で扱うための窓口。
 * B の CarPlayPane、D の TvPane を、Panes がこの形に合わせる。
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
    /** ダイヤルの回転が「スクロール」で届いたとき(操作中のときだけ)。扱ったら true。 */
    boolean onGenericMotion(MotionEvent e);
    String status();
    /** 記録シートの指示(例「tv-status」)。結果の1行を返す。扱わない部品は null。 */
    String command(String cmd);
    /** 音量を下げる(1＝そのまま、0.2＝小さく、0＝消す)。音を出す部品だけが使う。 */
    default void duck(float level) {}
    /** 電話・Siri の間は止め(on)、終わったら止める前の状態に戻す(off)。音を出す部品だけが使う。 */
    default void hold(boolean on) {}
    /** タッチパッドのキーボードの文字・キー。文字を受ける部品だけが使う。 */
    default void typeText(String text) {}
    default void typeKey(int keyCode) {}
    /** 2本指スクロール。その画面本来の送り方(YouTube=矢印、CarPlay=ダイヤル)にする。dx,dy は OTTO の px。 */
    default void scroll(float dx, float dy) {}
    /** スクロールが終わった(たまりを捨てる)。 */
    default void scrollEnd() {}
    /** 「CarPlay」「YouTube」。 */
    String name();
    /** 本物の部品なら true、仮の部品なら false。 */
    boolean real();
}
