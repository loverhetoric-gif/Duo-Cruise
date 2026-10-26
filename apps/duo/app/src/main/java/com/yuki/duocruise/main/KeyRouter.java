package com.yuki.duocruise.main;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * キーの振り分け(Android に依存しない判断だけ)。
 *
 * ・割り当て(KeyMap)に「長押し」があるキーは、押した時点では届けずに待つ。
 *   長押しの時間を過ぎたら動作を実行、その前に離したら「短押し」として扱う。
 * ・短押しの扱い: 短押しの割り当てがあればその動作。無ければ、アプリのキーは操作中の側へ
 *   「押す→離す」を作って届け、ホーム等のシステムのキーはシステム本来の動作に回す。
 * ・割り当てが無いキーは、待たずにそのまま操作中の側へ届ける(遅れなし)。
 *
 * 押す/離すが分かれて届かない機器でも壊れないようにする:
 * ・押す と 離す が同時に届く → 短押し(長押しは使えないだけ)
 * ・離す だけ届く → 短押し
 * ・押したままの間、押す が何度も届く(繰り返し) → 1回の押下として扱う
 * ・離す が届かない(押す だけが間をあけて届く) → そのキーを「離す無し」と覚え、以後は押した瞬間に短押し
 */
final class KeyRouter {
    static final int DOWN = 0, UP = 1; // android.view.KeyEvent.ACTION_DOWN / ACTION_UP と同じ値

    /** これより間があいて同じキーの「押す」が来たら、別の押下とみなす(ms)。 */
    static final long HOLD_GAP = 1000;

    interface Sink {
        /** 操作中の側(または操作盤)へキーを届ける。raw が null なら作って届ける。 */
        void deliver(int code, int action, long downTime, Object raw);
        void action(KeyMap.Action a);
        /** ホーム・タスク等を、システム本来の動作で実行する(横取りした分を返す)。 */
        void systemDefault(int code);
        /** 割り当ての登録中に押されたボタン。 */
        void learned(int code, int gesture);
        void note(String text);
    }

    interface Timer {
        Object post(Runnable r, long delayMs);
        void cancel(Object token);
    }

    private static final class Pending {
        int code;
        boolean system, fired;
        long downTime, lastSeen;
        Object token;
    }

    private final Sink sink;
    private final Timer timer;
    private KeyMap map;
    private long longMs;
    private boolean learning;
    private Pending pending;
    private final Set<Integer> noUp = new HashSet<>();
    private final Map<Integer, Long> shortDown = new HashMap<>();

    KeyRouter(KeyMap map, long longMs, Sink sink, Timer timer) {
        this.map = map; this.longMs = longMs; this.sink = sink; this.timer = timer;
    }

    void setMap(KeyMap m) { map = m; }
    void setLongMs(long ms) { longMs = ms; }
    void setLearning(boolean on) { learning = on; }
    boolean learning() { return learning; }
    boolean hasNoUp(int code) { return noUp.contains(code); }

    /**
     * 1つのキーの出来事。system=true はユーザー補助から来たシステムのキー(ホーム等)。
     * 戻り値 true＝ここで扱った(システムへは渡さない)。
     */
    boolean onKey(int code, int action, int repeat, long time, boolean system, Object raw) {
        if (action != DOWN && action != UP) {
            if (system) return false;
            sink.deliver(code, action, time, raw);
            return true;
        }
        if (action == DOWN && pending != null && pending.code != code) finish(pending); // 別のキーが来たら、待っていたキーは短押しで確定
        // 待っている途中のキーは、割り当てや登録の状態が変わっても最後まで同じ扱いにする
        boolean longBound = learning || map.hasLong(code) || (pending != null && pending.code == code);
        if (!longBound) {
            KeyMap.Action s = map.find(code, KeyMap.SHORT);
            if (s == null) {
                if (system) return false;
                sink.deliver(code, action, time, raw);
                return true;
            }
            if (action == DOWN) {
                Long prev = shortDown.put(code, time);
                if (prev == null || time - prev > HOLD_GAP) sink.action(s);
            } else if (shortDown.remove(code) == null) {
                sink.action(s); // 離す だけ届いた
            }
            return true;
        }

        if (action == DOWN) {
            Pending p = pending;
            if (p != null && p.code == code) {
                if (time - p.lastSeen <= HOLD_GAP && !noUp.contains(code)) {
                    p.lastSeen = time; // 押したままの繰り返し
                    if (!p.fired && time - p.downTime >= longMs) fireLong(p);
                    return true;
                }
                noUp.add(code);
                sink.note(KeyMap.keyName(code) + " は「離す」が届かないため、以後は短押しだけで扱います");
                finish(p);
            }
            if (noUp.contains(code)) {
                shortPress(code, system, time);
                return true;
            }
            Pending n = new Pending();
            n.code = code; n.system = system; n.downTime = time; n.lastSeen = time;
            pending = n;
            n.token = timer.post(() -> fireLong(n), longMs);
            return true;
        }

        // UP
        noUp.remove(code);
        Pending p = pending;
        if (p != null && p.code == code) {
            timer.cancel(p.token);
            pending = null;
            if (!p.fired) shortPress(code, p.system, p.downTime);
            return true;
        }
        shortPress(code, system, time); // 離す だけ届いた
        return true;
    }

    private void finish(Pending p) {
        timer.cancel(p.token);
        if (pending == p) pending = null;
        if (!p.fired) shortPress(p.code, p.system, p.downTime);
    }

    private void fireLong(Pending p) {
        if (p.fired) return;
        p.fired = true;
        if (learning) {
            learning = false;
            sink.learned(p.code, KeyMap.LONG);
            return;
        }
        KeyMap.Action a = map.find(p.code, KeyMap.LONG);
        if (a != null) sink.action(a);
        else {
            p.fired = false; // 割り当てが途中で消えた。離したときに短押しで扱う
        }
    }

    private void shortPress(int code, boolean system, long downTime) {
        if (learning) {
            learning = false;
            sink.learned(code, KeyMap.SHORT);
            return;
        }
        KeyMap.Action a = map.find(code, KeyMap.SHORT);
        if (a != null) { sink.action(a); return; }
        if (system) { sink.systemDefault(code); return; }
        sink.deliver(code, DOWN, downTime, null);
        sink.deliver(code, UP, downTime, null);
    }
}
