package com.yuki.duocruise.main;

import android.content.Context;

/**
 * Duo Slate の受け口を、いま前面のアプリに合わせて切り替える。
 *
 * ・Duo Cruise が前面 → Duo モード(CarPlay・YouTube・操作盤。DuoActivity の窓の中を操作)
 * ・それ以外(OTTO のホームや他のアプリ) → 汎用モード(ユーザー補助で、画面のどこでもカーソルで操作)
 *
 * アプリごとのモードは {@link #modeFor()} で決める。あとで作るオリジナルのホームは、ここに1行足すだけ
 * (例: そのパッケージ名のときは別のモード)。
 *
 * 口(47800)は、ここが1つだけ持つ。Duo Cruise を閉じても、ユーザー補助が生きていれば口は開いたまま
 * (汎用モードで使えるように)。両方とも無くなったら口を閉じて Wi-Fi ロックを放す。
 */
final class PadRouter implements PadServer.Sink {
    enum Mode { DUO, GENERIC }

    static final String DUO_PACKAGE = "com.yuki.duocruise.main";

    private static PadRouter instance;

    private final Context app;
    private PadServer server;
    /** Duo Cruise の窓の中を操作する受け口(DuoActivity が出し入れする)。null なら Duo は動いていない。 */
    private volatile PadServer.Sink duoSink;
    /** 汎用モードの受け口(ユーザー補助が生きているときだけ)。 */
    private volatile PadServer.Sink systemSink;
    /** いちばん前のアプリのパッケージ名(ユーザー補助のイベントで更新。控え)。 */
    private volatile String foreground = "";
    private volatile Mode lastMode;
    /** いま前面のアプリを、その場で確かめる口(ユーザー補助)。イベントより確実。 */
    private volatile Foreground foregroundSource;

    /** いま前面のアプリのパッケージ名を返す。 */
    interface Foreground { String current(); }

    static synchronized PadRouter get(Context c) {
        if (instance == null) instance = new PadRouter(c);
        return instance;
    }

    private PadRouter(Context c) { app = c.getApplicationContext(); }

    /** 口を開く(まだなら)。 */
    synchronized PadServer ensureServer() {
        if (server == null) server = PadServer.shared(app, this);
        server.start();
        return server;
    }

    /** 口。状態(操作している側・CarPlay の Bluetooth)を知らせるのに DuoActivity が使う。 */
    synchronized PadServer server() { return ensureServer(); }

    void setForeground(String pkg) { if (pkg != null && !pkg.isEmpty()) foreground = pkg; }

    void setForegroundSource(Foreground f) { foregroundSource = f; }

    /** いま前面のアプリ。その場で確かめられればそれを、だめなら控えを使う。 */
    private String foreground() {
        Foreground f = foregroundSource;
        if (f != null) {
            try { String p = f.current(); if (p != null && !p.isEmpty()) return p; } catch (Throwable ignored) {}
        }
        return foreground;
    }

    /** Duo Cruise が開いた/閉じた。 */
    void setDuoSink(PadServer.Sink s) { duoSink = s; refresh(); }

    /** ユーザー補助が動き始めた/止まった。 */
    void setSystemSink(PadServer.Sink s) { systemSink = s; refresh(); }

    private synchronized void refresh() {
        if (duoSink == null && systemSink == null) {
            if (server != null) { server.stop(); server = null; }
        } else {
            ensureServer();
        }
    }

    private Mode modeFor() {
        // Duo Cruise が前面、または汎用モードが使えない(ユーザー補助オフ)なら Duo モード
        if (duoSink != null && (foreground().equals(DUO_PACKAGE) || systemSink == null)) return Mode.DUO;
        return Mode.GENERIC;
    }

    private PadServer.Sink active() {
        if (modeFor() == Mode.DUO && duoSink != null) return duoSink;
        if (systemSink != null) return systemSink;
        return duoSink;
    }

    @Override public void line(String s) {
        pushMode();
        PadServer.Sink k = active();
        if (k != null) k.line(s);
    }

    /** 今のモードを Pad に知らせる(変わったときだけ送られる)。 */
    private void pushMode() {
        Mode m = modeFor();
        if (m == lastMode) return;
        lastMode = m;
        PadServer sv = server;
        if (sv != null) sv.setMode(m == Mode.DUO ? "duo" : "generic");
    }

    @Override public void connected(boolean on, String who) {
        // つながった/切れたは、どちらの受け口にも知らせる(それぞれが押しかけの状態を片付けられるように)
        if (on) { lastMode = null; pushMode(); }
        PadServer.Sink d = duoSink, s = systemSink;
        if (d != null) d.connected(on, who);
        if (s != null) s.connected(on, who);
    }

    @Override public int[] screen() {
        PadServer.Sink k = active();
        return k != null ? k.screen() : new int[]{1, 1};
    }
}
