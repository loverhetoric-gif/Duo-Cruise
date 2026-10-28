package com.yuki.duocruise.main;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * タッチパッドの指示を、送った側と同じ間隔で再生する「揺れ吸収」(ゲームの通信と同じ考え方)。
 * Android に依存しない(時刻は呼ぶ側が渡す)。
 *
 * OTTO の Wi-Fi は届く間隔が大きく揺れる(親機まで平均 28ms・最大 150ms、2026-10-27 測定)。
 * 届いた順にすぐ動かすと、止まったり跳んだりする。そこで:
 * ・iPhone・Mac は各行の頭に「@送った時刻(ms)」を付ける
 * ・ここでは「届いた時刻 − 送った時刻」の最小(＝揺れの無いときの差)を基準にし、
 *   最近の揺れの大きさ(90% がこの中に入る幅)だけ遅らせて、送った間隔どおりに再生する
 * ・遅らせる幅は揺れに合わせて自動で変わる(MIN_DELAY〜MAX_DELAY)
 * ・時刻が付いていない行(古いアプリ)は、届いたらすぐ再生する
 */
final class PadPlayout {
    static final long MIN_DELAY = 6, MAX_DELAY = 90;
    private static final int WINDOW = 120;

    private static final class Item {
        final long due;
        final String line;
        Item(long due, String line) { this.due = due; this.line = line; }
    }

    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final long[] diffs = new long[WINDOW];
    private int count, next;
    private long lastDue = Long.MIN_VALUE;

    /** 1行届いた(どのスレッドからでもよい)。now は届いた時刻(ms、単調)。 */
    synchronized void offer(String raw, long now) {
        long due = now;
        String line = raw;
        if (raw.startsWith("@")) {
            int sp = raw.indexOf(' ');
            if (sp > 1) {
                try {
                    long sent = Long.parseLong(raw.substring(1, sp));
                    line = raw.substring(sp + 1);
                    long diff = now - sent;
                    diffs[next] = diff;
                    next = (next + 1) % WINDOW;
                    if (count < WINDOW) count++;
                    due = sent + base() + delay();
                } catch (NumberFormatException ignored) {}
            }
        }
        // 順番は必ず守る(押す→動かす→離す が入れ替わらないように)。遅すぎた行は今すぐ
        if (due < lastDue) due = lastDue;
        if (due < now - MAX_DELAY) due = now;
        lastDue = due;
        queue.addLast(new Item(due, line));
    }

    /** 時刻 now までに再生すべき行を取り出す(画面の書き換え1コマごとに呼ぶ)。 */
    synchronized String[] due(long now) {
        if (queue.isEmpty() || queue.peekFirst().due > now) return null;
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        while (!queue.isEmpty() && queue.peekFirst().due <= now) out.add(queue.pollFirst().line);
        return out.toArray(new String[0]);
    }

    synchronized boolean pending() { return !queue.isEmpty(); }

    /** 揺れの無いときの「届いた − 送った」。時計のずれもここで吸収する。 */
    private long base() {
        long m = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) m = Math.min(m, diffs[i]);
        return m;
    }

    /** 最近の揺れの 90% が収まる幅だけ遅らせる。 */
    synchronized long delay() {
        if (count < 8) return 40;
        long[] d = Arrays.copyOf(diffs, count);
        Arrays.sort(d);
        long jitter = d[(int) (count * 0.9) - 1] - d[0];
        return Math.max(MIN_DELAY, Math.min(MAX_DELAY, jitter));
    }
}
