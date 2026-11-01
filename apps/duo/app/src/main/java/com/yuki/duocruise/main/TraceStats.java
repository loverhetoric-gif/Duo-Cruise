package com.yuki.duocruise.main;

import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.RandomAccessFile;

/**
 * 数秒ごとに CPU・メモリの使用を測り、Trace に渡す。重さの原因や、落ちた前の逼迫を見るため。
 *   cpu     … 機器全体の CPU 使用率(%)
 *   app     … この Duo Cruise の CPU 使用率(%)
 *   availMB … 空きメモリ(MB)
 *   pssMB   … この Duo Cruise が使っているメモリ(PSS, MB)
 *   thr     … この Duo Cruise のスレッド数
 * /proc を読むだけ(権限は要らない)。専用スレッドで動く。
 */
final class TraceStats {
    private static final long MS = 3000;

    private static HandlerThread th;
    private static Handler h;
    private static long lastTotal, lastIdle, lastProc; // 前回の jiffies

    private TraceStats() {}

    static synchronized void start() {
        if (th != null) return;
        th = new HandlerThread("duo-stat");
        th.start();
        h = new Handler(th.getLooper());
        lastTotal = lastIdle = lastProc = 0;
        h.post(tick);
    }

    static synchronized void stop() {
        if (th == null) return;
        h.removeCallbacksAndMessages(null);
        th.quitSafely();
        th = null;
        h = null;
    }

    private static final Runnable tick = TraceStats::sample;

    private static void sample() {
        try {
            long[] sys = readSysCpu();      // {total, idle}
            long proc = readProcCpu();      // utime+stime
            int threads = readThreads();
            long availKb = readMemAvailable();

            int cpu = -1, app = -1;
            if (lastTotal != 0 && sys[0] > lastTotal) {
                long dt = sys[0] - lastTotal, di = sys[1] - lastIdle;
                cpu = (int) Math.round((dt - di) * 100.0 / dt);
                app = (int) Math.round((proc - lastProc) * 100.0 / dt);
            }
            lastTotal = sys[0];
            lastIdle = sys[1];
            lastProc = proc;

            long pssKb = Debug.getPss(); // この版のアプリが使っているメモリ(KB)

            if (cpu >= 0) {
                Trace.stat("\"cpu\":" + cpu + ",\"app\":" + app
                        + ",\"availMB\":" + (availKb / 1024)
                        + ",\"pssMB\":" + (pssKb / 1024)
                        + ",\"thr\":" + threads);
            }
        } catch (Throwable ignored) {
            // 測れなくても本体を巻き込まない
        }
        if (h != null) h.postDelayed(tick, MS);
    }

    /** /proc/stat の先頭行 "cpu  user nice system idle iowait irq softirq steal" → {total, idle+iowait}。 */
    private static long[] readSysCpu() throws Exception {
        try (RandomAccessFile f = new RandomAccessFile("/proc/stat", "r")) {
            String line = f.readLine();
            String[] p = line.trim().split("\\s+"); // p[0]="cpu"
            long total = 0, idle = 0;
            for (int i = 1; i < p.length; i++) {
                long v = Long.parseLong(p[i]);
                total += v;
                if (i == 4 || i == 5) idle += v; // idle, iowait
            }
            return new long[]{total, idle};
        }
    }

    /** /proc/self/stat の utime(14)+stime(15)。comm に空白・括弧が混じるので ')' の後ろから数える。 */
    private static long readProcCpu() throws Exception {
        try (RandomAccessFile f = new RandomAccessFile("/proc/self/stat", "r")) {
            String s = f.readLine();
            int rp = s.lastIndexOf(')');
            String[] a = s.substring(rp + 2).trim().split("\\s+"); // a[0]=state(14 番目=utime は a[11])
            return Long.parseLong(a[11]) + Long.parseLong(a[12]);
        }
    }

    /** /proc/self/stat の num_threads(20 番目=a[17])。 */
    private static int readThreads() {
        try (RandomAccessFile f = new RandomAccessFile("/proc/self/stat", "r")) {
            String s = f.readLine();
            int rp = s.lastIndexOf(')');
            String[] a = s.substring(rp + 2).trim().split("\\s+");
            return Integer.parseInt(a[17]);
        } catch (Throwable t) { return -1; }
    }

    /** /proc/meminfo の MemAvailable(KB)。 */
    private static long readMemAvailable() {
        try (RandomAccessFile f = new RandomAccessFile("/proc/meminfo", "r")) {
            String line;
            while ((line = f.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] p = line.trim().split("\\s+"); // ["MemAvailable:", "123456", "kB"]
                    return Long.parseLong(p[1]);
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
