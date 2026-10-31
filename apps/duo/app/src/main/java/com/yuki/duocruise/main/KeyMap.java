package com.yuki.duocruise.main;

import java.util.ArrayList;
import java.util.List;

/**
 * ボタンの割り当て(どのボタンを、短く/長く押すと、何をするか)。
 *
 * ★ 割り当てはここ1か所で決まる。車での実測のあと本人が決めるまで、DEFAULTS は「仮」。
 *   操作盤の「ボタンの割り当て」からも変えられる(保存は Store の文字列1つ)。
 *
 * Android に依存しない(キーの番号は android.view.KeyEvent と同じ値)。
 */
final class KeyMap {
    // android.view.KeyEvent の番号
    static final int HOME = 3, BACK = 4, COMMA = 55, DPAD_UP = 19, DPAD_DOWN = 20, DPAD_LEFT = 21, DPAD_RIGHT = 22,
            DPAD_CENTER = 23, TAB = 61, ENTER = 66, MENU = 82, SEARCH = 84, APP_SWITCH = 187,
            ASSIST = 219, VOICE_ASSIST = 231, NAVIGATE_PREVIOUS = 260, NAVIGATE_NEXT = 261;

    /**
     * 押し方。SHORT=1回、LONG=長押し、2 以上=その回数だけ素早く押す(2=2回、3=3回…)。
     * 車(MX-30)のコマンダーは押した瞬間に離した形で届くため長押しが使えず、回数で分ける。
     */
    static final int SHORT = 0, LONG = 1, DOUBLE = 2, TRIPLE = 3;
    static final int MAX_TAPS = 4;

    /** 割り当てられる動作(この順で「ボタンの割り当て」に並ぶ)。 */
    enum Action {
        SWITCH_SIDE(R.string.act_switch),
        SWAP(R.string.act_swap),
        SINGLE(R.string.act_single),
        /** 分割比の調整: 入ったあとは回して境目を動かし、押し込みで決定・戻るで取り消し。 */
        SPLIT(R.string.act_split),
        PANEL(R.string.act_panel);
        private final int labelId;
        Action(int id) { labelId = id; }
        /** 画面に出す名前(端末の言語)。 */
        String label() { return Tx.s(labelId); }
    }

    /** 1つの割り当て。 */
    static final class Bind {
        final int code, gesture;
        final Action action;
        Bind(int code, int gesture, Action action) { this.code = code; this.gesture = gesture; this.action = action; }
    }

    /**
     * 初期値(2026-10-29 車での実測のあと本人が決めた)。
     * コマンダーの押し込みの 3 回以上は押しにくい。ホームとタスク(♪)は、ホームアプリにしても機器が先に取るため届かない。
     * MX-30 のナビボタン(紙飛行機)は COMMA で届き、本来の働きが無いので 1 回押しから使える。
     * ・ナビボタンを1回 → ターゲットの切り替え(操作する側)
     * ・押し込みを2回 → ウィンドウの切り替え(左右の入れ替え)
     * ・戻るを2回 → 1画面と2画面の切り替え(1画面のときは操作中の側だけを出す)
     * ・ナビボタンを2回 → 分割比の調整(回して調整、押し込みで決定)
     * ・押し込みを3回 → メニューを開く・閉じる
     * ・押し込み・戻るを1回 → いつもの「決定」「戻る」として操作中の側へ(回数を数えるため少し遅れて届く)
     * 操作盤の「ボタンの割り当て」で、押す回数(1〜4回)や長押しごとに変えられる。
     */
    static final String DEFAULTS = "SWITCH_SIDE=55:S;SWAP=23:2;SINGLE=4:2;SPLIT=55:2;PANEL=23:3";

    /**
     * 後から足した動作の初期の割り当て。保存済みの割り当てに無く、そのボタン＋押し方が空いていれば足す
     * (前の版から使っている人にも、新しい動作が届くように)。
     */
    void addMissingDefaults() {
        KeyMap d = defaults();
        for (Action a : Action.values()) {
            if (!of(a).isEmpty()) continue;
            for (Bind b : d.of(a)) if (find(b.code, b.gesture) == null) binds.add(new Bind(b.code, b.gesture, a));
        }
    }

    /** 長押しとみなす時間(ms)。 */
    static final long DEFAULT_LONG_MS = 600;

    private final List<Bind> binds = new ArrayList<>();

    KeyMap() {}

    static KeyMap parse(String s) {
        KeyMap m = new KeyMap();
        if (s == null) s = DEFAULTS;
        for (String part : s.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            Action a;
            try { a = Action.valueOf(kv[0].trim()); } catch (IllegalArgumentException e) { continue; }
            for (String b : kv[1].split(",")) {
                String[] cg = b.trim().split(":");
                if (cg.length != 2) continue;
                try {
                    int code = Integer.parseInt(cg[0].trim());
                    String gs = cg[1].trim();
                    int g = "L".equalsIgnoreCase(gs) ? LONG : "S".equalsIgnoreCase(gs) ? SHORT : Integer.parseInt(gs);
                    if (g < SHORT || g > MAX_TAPS) continue;
                    m.set(a, code, g == 1 && !"L".equalsIgnoreCase(gs) ? SHORT : g, false);
                } catch (NumberFormatException ignored) {}
            }
        }
        return m;
    }

    static KeyMap defaults() { return parse(DEFAULTS); }

    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Action a : Action.values()) {
            StringBuilder one = new StringBuilder();
            for (Bind b : binds) if (b.action == a) {
                if (one.length() > 0) one.append(',');
                one.append(b.code).append(':').append(b.gesture == LONG ? "L" : b.gesture == SHORT ? "S" : String.valueOf(b.gesture));
            }
            if (one.length() == 0) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(a.name()).append('=').append(one);
        }
        return sb.toString();
    }

    /**
     * 割り当てる。同じ「ボタン＋押し方」が他の動作にあれば外す(1つの押し方は1つの動作だけ)。
     * replace=true なら、その動作の今までの割り当てを全部外してから付ける。
     */
    void set(Action a, int code, int gesture, boolean replace) {
        for (int i = binds.size() - 1; i >= 0; i--) {
            Bind b = binds.get(i);
            if ((b.code == code && b.gesture == gesture) || (replace && b.action == a)) binds.remove(i);
        }
        binds.add(new Bind(code, gesture, a));
    }

    /**
     * 「ボタンの割り当て」の登録。1つの動作に1つの押し方だけ。重複させない:
     * 同じ押し方を別の動作が持っていたら、その動作には、こちらが今まで使っていた押し方を渡す(入れ替え)。
     * こちらに今までの押し方が無ければ、相手は未割り当てになる。戻り値は入れ替えた相手(無ければ null)。
     */
    Action assign(Action a, int code, int gesture) {
        Action other = find(code, gesture);
        if (other == a) return null;
        List<Bind> mine = of(a);
        clear(a);
        if (other != null) {
            clear(other);
            for (Bind b : mine) binds.add(new Bind(b.code, b.gesture, other));
        }
        binds.add(new Bind(code, gesture, a));
        return other;
    }

    void clear(Action a) {
        for (int i = binds.size() - 1; i >= 0; i--) if (binds.get(i).action == a) binds.remove(i);
    }

    /** そのボタン＋押し方の動作。無ければ null。 */
    Action find(int code, int gesture) {
        for (Bind b : binds) if (b.code == code && b.gesture == gesture) return b.action;
        return null;
    }

    boolean hasLong(int code) { return find(code, LONG) != null; }

    /** そのボタンに割り当てのある一番多い回数(2 以上)。回数の割り当てが無ければ 1。 */
    int maxTaps(int code) {
        int n = 1;
        for (Bind b : binds) if (b.code == code && b.gesture >= DOUBLE) n = Math.max(n, b.gesture);
        return n;
    }

    List<Bind> of(Action a) {
        List<Bind> out = new ArrayList<>();
        for (Bind b : binds) if (b.action == a) out.add(b);
        return out;
    }

    /** 人が読む形。例「決定(長) / タスク(短)」。 */
    String describe(Action a) {
        StringBuilder sb = new StringBuilder();
        for (Bind b : of(a)) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(keyName(b.code)).append(gestureName(b.gesture));
        }
        return sb.length() == 0 ? Tx.s(R.string.k_unassigned) : sb.toString();
    }

    static String gestureName(int g) {
        return g == LONG ? Tx.s(R.string.g_long) : g == SHORT ? Tx.s(R.string.g_short) : Tx.s(R.string.g_n, g);
    }

    /** よく使うキーの名前(端末の言語)。知らないキーは番号。 */
    static String keyName(int code) {
        switch (code) {
            case HOME: return Tx.s(R.string.key_home);
            case BACK: return Tx.s(R.string.key_back);
            case COMMA: return Tx.s(R.string.key_nav);
            case DPAD_UP: return Tx.s(R.string.key_up);
            case DPAD_DOWN: return Tx.s(R.string.key_down);
            case DPAD_LEFT: return Tx.s(R.string.key_left);
            case DPAD_RIGHT: return Tx.s(R.string.key_right);
            case DPAD_CENTER: return Tx.s(R.string.key_center);
            case ENTER: return "Enter";
            case TAB: return "Tab";
            case MENU: return Tx.s(R.string.key_menu);
            case SEARCH: return Tx.s(R.string.key_search);
            case APP_SWITCH: return Tx.s(R.string.key_tasks);
            case ASSIST: case VOICE_ASSIST: return Tx.s(R.string.key_voice);
            case NAVIGATE_NEXT: return Tx.s(R.string.key_next);
            case NAVIGATE_PREVIOUS: return Tx.s(R.string.key_prev);
            case 85: return Tx.s(R.string.key_playpause);
            case 87: return Tx.s(R.string.key_nexttrack);
            case 88: return Tx.s(R.string.key_prevtrack);
            default: return Tx.s(R.string.key_code, code);
        }
    }

    /**
     * 1回押し(短押し)を取り上げると、コマンダーでの普通の操作(回す・倒す・決定・戻る)ができなくなるキー。
     * 鉄則「操作はコマンダーだけ」を守るため、これらの1回押しは割り当てられない。
     */
    static boolean reservedSingle(int code) {
        return code == BACK || code == DPAD_UP || code == DPAD_DOWN || code == DPAD_LEFT
                || code == DPAD_RIGHT || code == DPAD_CENTER || code == ENTER;
    }

    /**
     * アプリには届かず、ユーザー補助でしか受けられないキー(Duo Cruise が前面のときだけ横取りする)。
     */
    static boolean isSystemKey(int code) {
        return code == HOME || code == APP_SWITCH || code == ASSIST || code == VOICE_ASSIST;
    }
}
