package com.yuki.duocruise.main;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * iPhone のタッチパッドアプリ(Duo Slate)の受け口。
 *
 * ・車の中の Wi-Fi(CarPlay と同じ)で、iPhone から直接つながる。外のサーバーは通らない
 * ・Bonjour(_duopad._tcp。iPhone 側と揃える通信上の名前なので旧名のまま)で名乗るので、iPhone は住所を入れなくても見つけられる
 * ・1行1つの短い文字の指示。遅れを出さないため Nagle を切る(TCP_NODELAY)
 * ・何台つながってもよい(どれからでも操作できる)。前は「新しい方だけ」にしていたが、
 *   2台が互いを切り合って、0.5 秒ごとにつなぎ直しを繰り返した(2026-10-27)
 * ・指示の中身は {@link PadInput} がメインスレッドで扱う
 *
 * 指示(iPhone → OTTO)は PadInput の説明を参照。OTTO → iPhone は
 * 「i 幅 高さ」(つながった直後の画面の大きさ)、「a 番号」(生きているかの返事)、
 * 「s L|R|F」(操作している側: 左・右・1画面。つながった直後と変わったとき)、
 * 「b 1|0」(CarPlay の Bluetooth が iPhone とつながっているか。つながった直後と変わったとき)。
 */
final class PadServer {
    static final int PORT = 47800;
    static final String SERVICE_TYPE = "_duopad._tcp";

    interface Sink {
        /** 1行の指示(メインスレッドで呼ぶ)。 */
        void line(String s);
        /** つながった/切れた(メインスレッドで呼ぶ)。 */
        void connected(boolean on, String who);
        /** 画面の大きさ(つながった直後に iPhone へ知らせる)。 */
        int[] screen();
    }

    private final Context app;
    private volatile Sink sink;
    /** つながっている間だけ、Wi-Fi の省電力を止めて遅れを減らす(往復が平均 130ms・最大 700ms あった)。 */
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile ServerSocket server;
    /** つながっている iPhone と、その送り口。 */
    private final java.util.Map<Socket, OutputStream> clients = new java.util.concurrent.ConcurrentHashMap<>();
    private NsdManager nsd;
    private NsdManager.RegistrationListener reg;
    private volatile int linesTotal;
    /** つながっている iPhone・Mac ごとの揺れ吸収(時計が別々なので分ける)。 */
    private final java.util.Set<PadPlayout> playouts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean frameScheduled; // メインスレッドだけで触る
    private final android.view.Choreographer.FrameCallback frame = t -> playFrame();
    private volatile String state = "s L";
    private volatile String bt = "b 0";
    /** 今どちらのモードか(Duo Cruise の中＝duo／ホーム等＝generic)。Pad の見た目の切り替えに使う。 */
    private volatile String modeMsg = "g duo";
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static PadServer shared;

    /**
     * アプリに1つだけの受け口。画面を作り直しても口(47800)は開いたまま、届け先だけを新しい画面へ替える
     * (画面ごとに作ると、前の画面が閉じきる前に口を取り合っていた)。
     */
    static synchronized PadServer shared(Context c, Sink sink) {
        if (shared == null) shared = new PadServer(c, sink);
        else shared.sink = sink;
        return shared;
    }

    private PadServer(Context c, Sink sink) {
        this.app = c.getApplicationContext();
        this.sink = sink;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::acceptLoop, "DuoSlate-accept");
        t.setDaemon(true);
        t.start();
        advertise();
    }

    void stop() {
        running = false;
        for (Socket c : clients.keySet()) closeQuietly(c);
        try { ServerSocket s = server; if (s != null) s.close(); } catch (Exception ignored) {}
        try { if (nsd != null && reg != null) nsd.unregisterService(reg); } catch (Exception ignored) {}
        reg = null;
    }

    String status() {
        StringBuilder who = new StringBuilder();
        for (Socket c : clients.keySet()) who.append(who.length() == 0 ? "" : ", ").append(c.getInetAddress().getHostAddress());
        return "タッチパッド " + (!clients.isEmpty()
                ? "接続中 " + clients.size() + "台(" + who + ") 受信" + linesTotal + "件"
                : running ? "待ち受け中 口=" + PORT : "停止");
    }

    private void advertise() {
        try {
            nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("Duo Cruise");
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            reg = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) { DuoLog.log("タッチパッド: 名乗り開始 " + i.getServiceName()); }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { DuoLog.error("タッチパッド: 名乗りに失敗 " + e); }
                @Override public void onServiceUnregistered(NsdServiceInfo i) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Throwable t) {
            DuoLog.error("タッチパッド: 名乗れません " + DuoLog.why(t));
        }
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true); // 作り直したときに、前の口が空くのを待たない
                s.bind(new InetSocketAddress(PORT));
                server = s;
                DuoLog.log("タッチパッド: 待ち受け開始 口=" + PORT);
                while (running) {
                    Socket c = s.accept();
                    Thread t = new Thread(() -> serve(c), "DuoSlate-client");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (Exception e) {
                if (!running) return;
                DuoLog.error("タッチパッド: 待ち受けで失敗 " + DuoLog.why(e) + "(3秒後にやり直し)");
                try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void serve(Socket c) {
        String who = c.getInetAddress().getHostAddress();
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(5000); // 1秒ごとの「生きているか」が5秒来なければ切れたとみなす
            OutputStream out = c.getOutputStream();
            clients.put(c, out);
            lowLatency(true);
            main.post(() -> sink.connected(true, who));
            int[] wh = sink.screen();
            send(out, "i " + wh[0] + " " + wh[1]);
            send(out, state);
            send(out, bt);
            send(out, modeMsg);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            PadPlayout po = new PadPlayout();
            playouts.add(po);
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    linesTotal++;
                    if (line.startsWith("q ")) { send(out, "a " + line.substring(2)); continue; } // 往復の時間を測る返事はすぐ返す
                    po.offer(line, android.os.SystemClock.uptimeMillis());
                    main.post(this::scheduleFrame);
                }
            } finally {
                playouts.remove(po);
                String[] rest = po.due(Long.MAX_VALUE); // 切れたら残りをすぐ再生(押したまま残らないように)
                if (rest != null) main.post(() -> { for (String l : rest) sink.line(l); });
            }
        } catch (Exception e) {
            if (running) DuoLog.log("タッチパッド: 切れました(" + who + " " + DuoLog.why(e) + ")");
        } finally {
            closeQuietly(c);
            if (clients.remove(c) != null) {
                if (clients.isEmpty()) lowLatency(false);
                boolean none = clients.isEmpty();
                main.post(() -> sink.connected(!none, who));
            }
        }
    }

    /** 画面の書き換え1コマごとに、届いた指示を送った間隔どおりに再生する(メインスレッド)。 */
    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        android.view.Choreographer.getInstance().postFrameCallback(frame);
    }

    private void playFrame() {
        frameScheduled = false;
        long now = android.os.SystemClock.uptimeMillis();
        boolean more = false;
        for (PadPlayout po : playouts) {
            String[] lines = po.due(now);
            if (lines != null) for (String l : lines) sink.line(l);
            more |= po.pending();
        }
        if (more) scheduleFrame();
    }

    /** 操作している側が変わった(メインスレッドから)。iPhone の縁の光と左右の印に使う。 */
    void setState(String s) {
        if (s.equals(state)) return;
        state = s;
        broadcast(s);
    }

    /** CarPlay の Bluetooth が iPhone とつながったか(メインスレッドから)。iPhone の縁を緑に灯す合図。 */
    void setBluetooth(boolean on) {
        String s = on ? "b 1" : "b 0";
        if (s.equals(bt)) return;
        bt = s;
        broadcast(s);
    }

    /** モードが変わった(メインスレッドから)。Pad の背景を切り替える合図。 */
    void setMode(String mode) {
        String s = "g " + mode;
        if (s.equals(modeMsg)) return;
        modeMsg = s;
        broadcast(s);
    }

    private void broadcast(String s) {
        for (OutputStream o : clients.values()) writer.execute(() -> { try { send(o, s); } catch (Exception ignored) {} });
    }

    private synchronized void lowLatency(boolean on) {
        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                int mode = android.os.Build.VERSION.SDK_INT >= 29
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "DuoSlate");
                wifiLock.setReferenceCounted(false);
            }
            if (on) wifiLock.acquire(); else wifiLock.release();
        } catch (Throwable t) {
            DuoLog.error("タッチパッド: Wi-Fi の低遅延にできません " + DuoLog.why(t));
        }
    }

    private static void send(OutputStream out, String s) throws java.io.IOException {
        synchronized (out) {
            out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
