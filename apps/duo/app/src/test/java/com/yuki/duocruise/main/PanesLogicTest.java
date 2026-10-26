package com.yuki.duocruise.main;

import java.util.ArrayList;
import java.util.List;

/**
 * 2画面・操作の計算の単体テスト(JUnit なし。Java だけで動く。app/build.gradle は B の持ち場のため)。
 *
 *   JH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
 *   M=apps/duo/app/src/main/java/com/yuki/duocruise/main
 *   "$JH/bin/javac" -encoding UTF-8 --release 17 -d /tmp/panestest $M/SplitGeometry.java $M/PanelGrid.java \
 *       $M/KeyMap.java $M/KeyRouter.java apps/duo/app/src/test/java/com/yuki/duocruise/main/PanesLogicTest.java
 *   "$JH/bin/java" -cp /tmp/panestest com.yuki.duocruise.main.PanesLogicTest   # 「すべて合格」で成功
 */
public final class PanesLogicTest {
    private static int fails = 0, checks = 0;

    private static void ok(boolean c, String what) {
        checks++;
        if (!c) { fails++; System.out.println("NG: " + what); }
    }

    public static void main(String[] a) {
        geometry();
        grid(1480, 416, 8, "主ページ 1480×416");
        grid(740, 416, 8, "主ページ 740×416");
        grid(1480, 416, 6, "割り当て 1480×416");
        grid(740, 416, 6, "割り当て 740×416");
        grid(740, 392, 8, "主ページ 740×392(ステータスバーが残った場合)");
        grid(1480, 480, 8, "主ページ 1480×480");
        navigation();
        keymap();
        router();
        System.out.println(fails == 0 ? "すべて合格(" + checks + " 件)" : fails + " 件 不合格 / " + checks);
        if (fails != 0) System.exit(1);
    }

    static void geometry() {
        int[] c = SplitGeometry.car(1480, 416, false), t = SplitGeometry.tv(1480, 416, false);
        ok(c[0] == 0 && c[2] == 740 && c[3] == 416, "CarPlay 左 740×416");
        ok(t[0] == 740 && t[2] == 1480 && t[3] == 416, "YouTube 右 740×416");
        int[] cs = SplitGeometry.car(1480, 416, true), ts = SplitGeometry.tv(1480, 416, true);
        ok(cs[0] == 740 && ts[0] == 0, "入れ替えで左右が逆");
        int[] o1 = SplitGeometry.half(1481, 416, 0), o2 = SplitGeometry.half(1481, 416, 1);
        ok(o1[2] == o2[0] && o2[2] == 1481, "奇数幅でも隙間・重なりなし");
        ok(SplitGeometry.tvOn(SplitGeometry.SIDE_RIGHT, false) && SplitGeometry.tvOn(SplitGeometry.SIDE_LEFT, true), "YouTube の側");
        int[] ph = SplitGeometry.panel(1480, 416, true, SplitGeometry.SIDE_RIGHT);
        ok(ph[0] == 740 && ph[2] == 1480, "操作盤(半分)は操作側の半分");
    }

    static void grid(int w, int h, int n, String name) {
        PanelGrid g = PanelGrid.compute(w, h, n, 1f, 0);
        ok(g.fits(1f), name + ": ボタンの高さ " + g.tileH + " が 48 以上");
        for (int i = 0; i < n; i++) {
            int[] r = g.rect(i);
            ok(r[0] >= 0 && r[2] <= w && r[1] >= 0 && r[3] <= h, name + ": " + i + " 番が画面内 " + java.util.Arrays.toString(r));
            ok(r[2] - r[0] >= 140, name + ": " + i + " 番の幅 " + (r[2] - r[0]) + " が 140 以上");
            ok(r[1] >= PanelGrid.PAD + PanelGrid.HEADER, name + ": " + i + " 番が見出しと重ならない");
        }
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            int[] p = g.rect(i), q = g.rect(j);
            boolean overlap = p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3];
            ok(!overlap, name + ": " + i + " と " + j + " が重ならない");
        }
        System.out.println(name + ": " + g.cols + "列×" + g.rows + "段, ボタン " + g.tileW + "×" + g.tileH);
    }

    static void navigation() {
        for (int[] wn : new int[][]{{1480, 8}, {740, 8}, {1480, 6}, {740, 6}, {740, 5}}) {
            PanelGrid g = PanelGrid.compute(wn[0], 416, wn[1], 1f, 0);
            for (int dir : new int[]{PanelGrid.RIGHT, PanelGrid.LEFT, PanelGrid.DOWN, PanelGrid.UP, PanelGrid.NEXT}) {
                boolean[] seen = new boolean[wn[1]];
                int i = 0;
                for (int k = 0; k < wn[1]; k++) { seen[i] = true; i = g.move(i, dir); }
                boolean all = true;
                for (boolean s : seen) all &= s;
                ok(all && i == 0, wn[0] + "幅 " + wn[1] + "個 向き" + dir + ": 全部を一巡して元に戻る");
            }
        }
        PanelGrid g = PanelGrid.compute(740, 416, 8, 1f, 0); // 2列×4段
        ok(g.move(0, PanelGrid.DOWN) == 2, "740: 下に倒すと真下");
        ok(g.move(0, PanelGrid.RIGHT) == 1, "740: 右に回すと隣");
        ok(g.move(6, PanelGrid.DOWN) == 1, "740: 左列の下端から次の列の上へ");
    }

    static void keymap() {
        KeyMap m = KeyMap.defaults();
        ok(m.find(KeyMap.DPAD_CENTER, KeyMap.LONG) == KeyMap.Action.SWITCH_SIDE, "初期値: 決定の長押し=切替");
        ok(m.find(KeyMap.BACK, KeyMap.LONG) == KeyMap.Action.SWAP, "初期値: 戻るの長押し=入替");
        ok(m.find(KeyMap.HOME, KeyMap.SHORT) == KeyMap.Action.PANEL, "初期値: ホーム=操作盤");
        ok(KeyMap.parse(m.serialize()).serialize().equals(m.serialize()), "保存して読み直しても同じ");
        m.set(KeyMap.Action.SWAP, KeyMap.DPAD_CENTER, KeyMap.LONG, true);
        ok(m.find(KeyMap.DPAD_CENTER, KeyMap.LONG) == KeyMap.Action.SWAP, "同じ押し方は1つの動作だけ");
        ok(m.find(KeyMap.BACK, KeyMap.LONG) == null, "置き換えで前の割り当てが外れる");
        ok(KeyMap.parse("XXX=1:L;SWAP=abc").serialize().isEmpty(), "壊れた文字列でも落ちない");
    }

    /** 時計を手で進めるタイマー。 */
    static final class FakeTimer implements KeyRouter.Timer {
        long now = 0;
        final List<Object[]> q = new ArrayList<>();
        @Override public Object post(Runnable r, long ms) { Object[] e = {now + ms, r}; q.add(e); return e; }
        @Override public void cancel(Object t) { q.remove(t); }
        void advance(long ms) {
            now += ms;
            for (Object[] e : new ArrayList<>(q)) if ((long) e[0] <= now && q.remove(e)) ((Runnable) e[1]).run();
        }
    }

    static final class Rec implements KeyRouter.Sink {
        final List<String> ev = new ArrayList<>();
        @Override public void deliver(int code, int action, long d, Object raw) { ev.add("渡" + code + (action == 0 ? "押" : "離") + (raw == null ? "作" : "")); }
        @Override public void action(KeyMap.Action a) { ev.add("動" + a.name()); }
        @Override public void systemDefault(int code) { ev.add("系" + code); }
        @Override public void learned(int code, int g) { ev.add("登" + code + (g == KeyMap.LONG ? "長" : "短")); }
        @Override public void note(String t) { ev.add("注"); }
        String s() { String r = String.join(",", ev); ev.clear(); return r; }
    }

    static void router() {
        FakeTimer t = new FakeTimer();
        Rec r = new Rec();
        KeyRouter k = new KeyRouter(KeyMap.defaults(), 600, r, t);
        int C = KeyMap.DPAD_CENTER, B = KeyMap.BACK, H = KeyMap.HOME, R = KeyMap.DPAD_RIGHT;

        // 割り当ての無いキーは、そのまま(遅れなし)
        k.onKey(R, 0, 0, t.now, false, "raw"); k.onKey(R, 1, 0, t.now, false, "raw");
        ok(r.s().equals("渡22押,渡22離"), "割り当て無しはそのまま");

        // 決定の短押し → 押す/離すを作って届ける
        k.onKey(C, 0, 0, t.now, false, null); t.advance(100); k.onKey(C, 1, 0, t.now, false, null);
        ok(r.s().equals("渡23押作,渡23離作"), "決定の短押し");

        // 決定の長押し → 切替。離しても何も届かない
        k.onKey(C, 0, 0, t.now, false, null); t.advance(700);
        ok(r.s().equals("動SWITCH_SIDE"), "決定の長押しで切替(離す前に実行)");
        k.onKey(C, 1, 0, t.now, false, null);
        ok(r.s().isEmpty(), "長押しの後の離すは捨てる");

        // 押したままの繰り返し(repeat>0 や、押すの連打)でも1回だけ
        k.onKey(B, 0, 0, t.now, false, null);
        for (int i = 1; i <= 15; i++) { t.advance(50); k.onKey(B, 0, i, t.now, false, null); }
        t.advance(50); k.onKey(B, 1, 0, t.now, false, null);
        ok(r.s().equals("動SWAP"), "繰り返しが来ても入替は1回");

        // 押す と 離す が同時に届く機器 → 短押し
        k.onKey(B, 0, 0, t.now, false, null); k.onKey(B, 1, 0, t.now, false, null);
        ok(r.s().equals("渡4押作,渡4離作"), "同時に届くと短押し");

        // 離す だけ届く → 短押し
        t.advance(2000);
        k.onKey(C, 1, 0, t.now, false, null);
        ok(r.s().equals("渡23押作,渡23離作"), "離すだけでも短押し");

        // 離す が届かない機器 → 2回目から短押しだけ
        t.advance(2000);
        k.onKey(B, 0, 0, t.now, false, null); t.advance(700);
        String first = r.s();
        t.advance(3000);
        k.onKey(B, 0, 0, t.now, false, null);
        String second = r.s();
        ok(first.equals("動SWAP") && second.equals("注,渡4押作,渡4離作") && k.hasNoUp(B), "離す無しの検出: " + first + " / " + second);
        t.advance(3000);
        k.onKey(B, 0, 0, t.now, false, null); t.advance(700);
        ok(r.s().equals("渡4押作,渡4離作"), "離す無しのキーは以後すぐ短押し(長押しにならない)");
        k.onKey(B, 1, 0, t.now, false, null); // 離す が届いたら元に戻る
        ok(!k.hasNoUp(B), "離すが届いたら通常に戻る");
        r.s();

        // ホーム(システムのキー): 割り当てが短押しにだけ → 押した瞬間に操作盤、システムへは渡さない
        boolean h1 = k.onKey(H, 0, 0, t.now, true, null), h2 = k.onKey(H, 1, 0, t.now, true, null);
        ok(h1 && h2 && r.s().equals("動PANEL"), "ホームで操作盤");

        // タスク: 割り当てが無い設定にすると、システムへそのまま
        KeyMap m = KeyMap.parse("SWAP=4:L");
        k.setMap(m);
        boolean s1 = k.onKey(KeyMap.APP_SWITCH, 0, 0, t.now, true, null);
        ok(!s1 && r.s().isEmpty(), "割り当て無しのシステムのキーは横取りしない");

        // ホームに長押しだけ割り当て → 短押しはシステム本来のホームに戻す
        m.set(KeyMap.Action.PANEL, H, KeyMap.LONG, true);
        k.onKey(H, 0, 0, t.now, true, null); t.advance(100); k.onKey(H, 1, 0, t.now, true, null);
        ok(r.s().equals("系3"), "ホーム短押しはシステムのホームへ");

        // 別のキーが割り込んだら、待っていたキーは短押しで確定
        k.setMap(KeyMap.defaults());
        k.onKey(C, 0, 0, t.now, false, null); t.advance(100); k.onKey(R, 0, 0, t.now, false, "raw");
        String ir = r.s();
        ok(ir.equals("渡23押作,渡23離作,渡22押"), "割り込みで短押し確定: " + ir);
        k.onKey(R, 1, 0, t.now, false, "raw"); k.onKey(C, 1, 0, t.now, false, null);
        r.s();

        // 割り当ての登録: 短く/長く
        k.setLearning(true);
        k.onKey(KeyMap.DPAD_LEFT, 0, 0, t.now, false, null); t.advance(80); k.onKey(KeyMap.DPAD_LEFT, 1, 0, t.now, false, null);
        ok(r.s().equals("登21短") && !k.learning(), "登録: 短く");
        k.setLearning(true);
        k.onKey(H, 0, 0, t.now, true, null); t.advance(700); k.onKey(H, 1, 0, t.now, true, null);
        String hl = r.s();
        ok(hl.equals("登3長"), "登録: ホームを長く " + hl);
    }
}
