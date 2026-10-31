package com.yuki.duocruise.main;

/**
 * 曲がり角の案内の判断(TurnPlan)の単体テスト(JUnit なし。Java だけで動く)。
 *
 *   JH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
 *   M=apps/duo/app/src/main/java/com/yuki/duocruise/main
 *   "$JH/bin/javac" -encoding UTF-8 --release 17 -d /tmp/turntest $M/TurnPlan.java \
 *       apps/duo/app/src/test/java/com/yuki/duocruise/main/TurnPlanTest.java
 *   "$JH/bin/java" -cp /tmp/turntest com.yuki.duocruise.main.TurnPlanTest   # 「すべて合格」で成功
 */
public final class TurnPlanTest {
    private static int fails = 0, checks = 0;

    private static void ok(boolean c, String what) {
        checks++;
        if (!c) { fails++; System.out.println("NG: " + what); }
    }

    public static void main(String[] a) {
        TurnPlan p = new TurnPlan();

        p.update(true, false, 1, 1500, "R");
        ok(!p.hint && !p.map && !p.duck && p.light == null, "遠いうちは何もしない");
        p.update(true, false, 1, 900, "R");
        ok(p.hint && !p.map, "1000 m を切ったら案内だけ");
        p.update(true, false, 1, 280, "R");
        ok(p.hint && p.map && !p.duck && p.light == null, "300 m で地図、まだ光らない");
        p.update(true, false, 1, 190, "R");
        ok("R".equals(p.light), "200 m を切った瞬間に右が光る");
        p.update(true, false, 1, 120, "R");
        ok(p.light == null && p.duck, "同じ曲がり角では二度光らない・150 m で音量");

        p.update(true, false, 2, 80, "L");
        ok("L".equals(p.light), "次の曲がり角が初めから近ければ、変わった瞬間に光る");
        p.update(true, false, 3, 2400, null);
        ok(!p.hint && !p.map && !p.duck && p.light == null, "曲がり終えて遠くなったら全部やめる");

        p.update(true, false, 4, 100, null);
        ok(p.light == null && p.map, "向きの無い曲がり角(直進など)は光らない");

        p.update(false, true, 4, 0, null);
        ok(p.arrivedNow && !p.hint && !p.map, "到着した瞬間");
        p.update(false, true, 4, 0, null);
        ok(!p.arrivedNow, "到着は 1 回だけ");

        p.update(true, false, 1, 150, "R");
        ok("R".equals(p.light), "新しいルートでは同じ番号でもまた光る");
        p.update(true, false, 1, -1, "R");
        ok(!p.hint && !p.map && !p.duck, "距離が分からないときは何もしない");

        p.clear();
        ok(!p.hint && !p.map && !p.duck && p.light == null && !p.arrivedNow, "止まったら全部やめる");

        System.out.println(fails == 0 ? "すべて合格(" + checks + " 件)" : fails + " 件 不合格 / " + checks);
        if (fails > 0) System.exit(1);
    }
}
