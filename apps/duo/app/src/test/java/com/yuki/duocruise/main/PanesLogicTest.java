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
        single();
        grid(1480, 480, 7, "割り当て 1480×480(動作4つ)");
        grid(740, 480, 7, "割り当て 740×480(動作4つ)");
        grid(1480, 480, 8, "主ページ 1480×480");
        grid(740, 480, 8, "主ページ 740×480");
        grid(1480, 480, 6, "割り当て 1480×480");
        grid(740, 480, 6, "割り当て 740×480");
        grid(740, 392, 8, "主ページ 740×392(ステータスバーが残った場合)");
        grid(1480, 480, 8, "主ページ 1480×480");
        navigation();
        keymap();
        router();
        taps();
        playout();
        System.out.println(fails == 0 ? "すべて合格(" + checks + " 件)" : fails + " 件 不合格 / " + checks);
        if (fails != 0) System.exit(1);
    }

    static void single() {
        int[] t = SplitGeometry.single(1480, 480, true), c = SplitGeometry.single(1480, 480, false), o = SplitGeometry.offscreen(1480, 480);
        ok(t[0] == 0 && t[2] == 1480 && t[3] == 480, "1画面 YouTube は横いっぱい");
        ok(c[0] == 370 && c[2] == 1110 && c[3] == 480, "1画面 CarPlay は 740 幅のまま真ん中");
        ok(o[0] == 1480 && o[2] - o[0] == 1480, "出さない側は画面の外、大きさは画面と同じ");
        int[] c7 = SplitGeometry.single(741, 480, false);
        ok(c7[0] >= 0 && c7[2] <= 741, "奇数幅でもはみ出さない");
    }

    /** 揺れ吸収: 8ms ごとに送った動きが、0〜150ms ばらばらに届いても、ほぼ 8ms 間隔で再生される。 */
    static void playout() {
        PadPlayout po = new PadPlayout();
        java.util.Random rnd = new java.util.Random(1);
        long clockGap = 5_000_000; // 送る側と OTTO の時計は別物
        java.util.List<long[]> arrivals = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            long sent = 1000 + i * 8L;
            long jitter = rnd.nextInt(10) == 0 ? 60 + rnd.nextInt(90) : rnd.nextInt(20);
            arrivals.add(new long[]{sent + clockGap + 7 + jitter, sent, i});
        }
        arrivals.sort((x, y) -> Long.compare(x[0], y[0]));
        java.util.List<Long> played = new java.util.ArrayList<>();
        java.util.List<Integer> order = new java.util.ArrayList<>();
        int k = 0;
        for (long now = arrivals.get(0)[0]; now < arrivals.get(arrivals.size() - 1)[0] + 300; now += 1) {
            while (k < arrivals.size() && arrivals.get(k)[0] <= now) {
                po.offer("@" + arrivals.get(k)[1] + " m " + arrivals.get(k)[2] + " 0", now); k++;
            }
            String[] d = po.due(now);
            if (d != null) for (String l : d) { played.add(now); order.add(Integer.parseInt(l.split(" ")[1])); }
        }
        ok(played.size() == 300, "揺れ吸収: 全部再生 " + played.size());
        int smooth = 0;
        for (int i = 150; i < played.size(); i++) { long g = played.get(i) - played.get(i - 1); if (g >= 0 && g <= 16) smooth++; }
        ok(smooth >= 140, "揺れ吸収: 後半の間隔がほぼ一定 " + smooth + "/150 delay=" + po.delay());
        PadPlayout plain = new PadPlayout();
        plain.offer("c", 100);
        String[] d = plain.due(100);
        ok(d != null && d.length == 1 && d[0].equals("c"), "時刻なしの行はすぐ再生");
    }

    static void geometry() {
        int[] c = SplitGeometry.car(1480, 480, false, 50), t = SplitGeometry.tv(1480, 480, false, 50);
        ok(c[0] == 0 && c[2] == 740 && c[3] == 480, "CarPlay 左 740×480");
        ok(t[0] == 740 && t[2] == 1480 && t[3] == 480, "YouTube 右 740×480");
        int[] cs = SplitGeometry.car(1480, 480, true, 50), ts = SplitGeometry.tv(1480, 480, true, 50);
        ok(cs[0] == 740 && ts[0] == 0, "入れ替えで左右が逆");
        int[] o1 = SplitGeometry.half(1481, 480, 0), o2 = SplitGeometry.half(1481, 480, 1);
        ok(o1[2] == o2[0] && o2[2] == 1481, "奇数幅でも隙間・重なりなし");
        ok(SplitGeometry.tvOn(SplitGeometry.SIDE_RIGHT, false) && SplitGeometry.tvOn(SplitGeometry.SIDE_LEFT, true), "YouTube の側");
        int[] ph = SplitGeometry.panel(1480, 480, true, SplitGeometry.SIDE_RIGHT);
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
            PanelGrid g = PanelGrid.compute(wn[0], 480, wn[1], 1f, 0);
            for (int dir : new int[]{PanelGrid.RIGHT, PanelGrid.LEFT, PanelGrid.DOWN, PanelGrid.UP, PanelGrid.NEXT}) {
                boolean[] seen = new boolean[wn[1]];
                int i = 0;
                for (int k = 0; k < wn[1]; k++) { seen[i] = true; i = g.move(i, dir); }
                boolean all = true;
                for (boolean s : seen) all &= s;
                ok(all && i == 0, wn[0] + "幅 " + wn[1] + "個 向き" + dir + ": 全部を一巡して元に戻る");
            }
        }
        PanelGrid g = PanelGrid.compute(740, 480, 8, 1f, 0); // 2列×4段
        ok(g.move(0, PanelGrid.DOWN) == 2, "740: 下に倒すと真下");
        ok(g.move(0, PanelGrid.RIGHT) == 1, "740: 右に回すと隣");
        ok(g.move(6, PanelGrid.DOWN) == 1, "740: 左列の下端から次の列の上へ");
    }

    static void keymap() {
        KeyMap d = KeyMap.defaults();
        ok(d.find(KeyMap.DPAD_CENTER, KeyMap.DOUBLE) == KeyMap.Action.SWITCH_SIDE, "初期値: 押し込み2回=切替");
        ok(d.find(KeyMap.BACK, KeyMap.DOUBLE) == KeyMap.Action.SWAP, "初期値: 戻る2回=入替");
        ok(d.find(KeyMap.DPAD_CENTER, KeyMap.TRIPLE) == KeyMap.Action.PANEL, "初期値: 押し込み3回=操作盤");
        ok(d.find(KeyMap.BACK, KeyMap.TRIPLE) == KeyMap.Action.SINGLE, "初期値: 戻る3回=1画面");
        ok(d.find(KeyMap.HOME, KeyMap.SHORT) == KeyMap.Action.PANEL, "初期値: ホーム=操作盤");
        ok(d.find(KeyMap.DPAD_CENTER, KeyMap.SHORT) == null && d.maxTaps(KeyMap.DPAD_CENTER) == 3, "初期値: 押し込み1回は割り当て無し");
        ok(KeyMap.parse(d.serialize()).serialize().equals(d.serialize()), "回数も保存して読み直して同じ");
        ok(KeyMap.parse("SWAP=4:9").serialize().isEmpty(), "多すぎる回数は捨てる");
        KeyMap m = KeyMap.parse(OLD);
        ok(m.find(KeyMap.DPAD_CENTER, KeyMap.LONG) == KeyMap.Action.SWITCH_SIDE, "旧: 決定の長押し=切替");
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
        @Override public void learned(int code, int g) { ev.add("登" + code + (g == KeyMap.LONG ? "長" : g == KeyMap.SHORT ? "短" : g + "回")); }
        @Override public void note(String t) { ev.add("注"); }
        String s() { String r = String.join(",", ev); ev.clear(); return r; }
    }

    /** 長押しを使う旧い割り当て(長押しの仕組みが壊れていないかを確かめる用)。 */
    static final String OLD = "SWITCH_SIDE=23:L,66:L,187:S;SWAP=4:L;PANEL=3:S";

    /** 押し込みの回数(車のコマンダー: 押す と 離す が 10〜25ms で続けて届く)。 */
    static void taps() {
        FakeTimer t = new FakeTimer();
        Rec r = new Rec();
        KeyRouter k = new KeyRouter(KeyMap.defaults(), 600, r, t);
        int B = KeyMap.DPAD_CENTER, R = KeyMap.DPAD_RIGHT;
        Runnable press = () -> { k.onKey(B, 0, 0, t.now, false, null); t.advance(15); k.onKey(B, 1, 0, t.now, false, null); };

        press.run();
        ok(r.s().isEmpty(), "押し込み1回はすぐには届かない(数えている)");
        t.advance(500);
        ok(r.s().equals("渡23押作,渡23離作"), "押し込み1回は待ったあと普通の決定");

        press.run(); t.advance(150); press.run();
        ok(r.s().isEmpty(), "押し込み2回: 3回目を待つ");
        t.advance(500);
        ok(r.s().equals("動SWITCH_SIDE"), "押し込み2回で切替");

        press.run(); t.advance(150); press.run(); t.advance(150); press.run();
        ok(r.s().equals("動PANEL"), "押し込み3回で操作盤(待たずに)");
        t.advance(500);
        ok(r.s().isEmpty(), "3回のあとに余計なものは届かない");

        int K = KeyMap.BACK;
        k.onKey(K, 0, 0, t.now, false, null); t.advance(15); k.onKey(K, 1, 0, t.now, false, null);
        t.advance(150);
        k.onKey(K, 0, 0, t.now, false, null); t.advance(15); k.onKey(K, 1, 0, t.now, false, null);
        ok(r.s().isEmpty(), "戻る2回: 3回目を待つ");
        t.advance(500);
        ok(r.s().equals("動SWAP"), "戻る2回で入替");
        for (int i = 0; i < 3; i++) { k.onKey(K, 0, 0, t.now, false, null); t.advance(15); k.onKey(K, 1, 0, t.now, false, null); t.advance(150); }
        ok(r.s().equals("動SINGLE"), "戻る3回で1画面(待たずに)");
        t.advance(500); r.s();
        k.onKey(K, 0, 0, t.now, false, null); t.advance(15); k.onKey(K, 1, 0, t.now, false, null); t.advance(500);
        ok(r.s().equals("渡4押作,渡4離作"), "戻る1回は待ったあと普通の戻る");

        // 登録: 押した回数で覚える
        k.setLearning(true);
        k.onKey(K, 0, 0, t.now, false, null); t.advance(15); k.onKey(K, 1, 0, t.now, false, null); t.advance(150);
        k.onKey(K, 0, 0, t.now, false, null); t.advance(15); k.onKey(K, 1, 0, t.now, false, null);
        ok(r.s().isEmpty(), "登録: 2回目のあとも続きを待つ");
        t.advance(500);
        ok(r.s().equals("登4" + "2回") && !k.learning(), "登録: 戻る2回");

        press.run(); t.advance(150); k.onKey(R, 0, 0, t.now, false, "raw");
        ok(r.s().equals("渡23押作,渡23離作,渡22押"), "数えている途中で別のキー → 押し込み1回で確定");
        k.onKey(R, 1, 0, t.now, false, "raw"); r.s();

        press.run(); t.advance(700); press.run(); t.advance(500);
        ok(r.s().equals("渡23押作,渡23離作,渡23押作,渡23離作"), "間があけば別々の決定");
    }

    static void router() {
        FakeTimer t = new FakeTimer();
        Rec r = new Rec();
        KeyRouter k = new KeyRouter(KeyMap.parse(OLD), 600, r, t);
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
        k.setMap(KeyMap.parse(OLD));
        k.onKey(C, 0, 0, t.now, false, null); t.advance(100); k.onKey(R, 0, 0, t.now, false, "raw");
        String ir = r.s();
        ok(ir.equals("渡23押作,渡23離作,渡22押"), "割り込みで短押し確定: " + ir);
        k.onKey(R, 1, 0, t.now, false, "raw"); k.onKey(C, 1, 0, t.now, false, null);
        r.s();

        // 割り当ての登録: 短く/長く
        k.setLearning(true);
        k.onKey(KeyMap.DPAD_LEFT, 0, 0, t.now, false, null); t.advance(80); k.onKey(KeyMap.DPAD_LEFT, 1, 0, t.now, false, null);
        t.advance(500);
        ok(r.s().equals("登21短") && !k.learning(), "登録: 短く(1回)");
        k.setLearning(true);
        k.onKey(H, 0, 0, t.now, true, null); t.advance(700); k.onKey(H, 1, 0, t.now, true, null);
        String hl = r.s();
        ok(hl.equals("登3長"), "登録: ホームを長く " + hl);
    }
}
