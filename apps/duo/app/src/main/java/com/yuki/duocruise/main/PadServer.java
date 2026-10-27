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
 * iPhone のタッチパッドアプリ(Duo Pad)の受け口。
 *
 * ・車の中の Wi-Fi(CarPlay と同じ)で、iPhone から直接つながる。外のサーバーは通らない
 * ・Bonjour(_duopad._tcp)で名乗るので、iPhone は住所を入れなくても見つけられる
 * ・1行1つの短い文字の指示。遅れを出さないため Nagle を切る(TCP_NODELAY)
 * ・つながるのは1台だけ。新しくつながったら古い方を切る
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
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile Socket client;
    private NsdManager nsd;
    private NsdManager.RegistrationListener reg;
    private volatile int linesTotal;
    private volatile OutputStream out;
    private volatile String state = "s L";
    private volatile String bt = "b 0";
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();

    PadServer(Context c, Sink sink) {
        this.app = c.getApplicationContext();
        this.sink = sink;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::acceptLoop, "DuoPad-accept");
        t.setDaemon(true);
        t.start();
        advertise();
    }

    void stop() {
        running = false;
        closeQuietly(client);
        try { ServerSocket s = server; if (s != null) s.close(); } catch (Exception ignored) {}
        try { if (nsd != null && reg != null) nsd.unregisterService(reg); } catch (Exception ignored) {}
        reg = null;
    }

    String status() {
        Socket c = client;
        return "タッチパッド " + (c != null && c.isConnected() && !c.isClosed()
                ? "接続中(" + c.getInetAddress().getHostAddress() + ") 受信" + linesTotal + "件"
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
                    closeQuietly(client); // つながるのは1台だけ
                    client = c;
                    Thread t = new Thread(() -> serve(c), "DuoPad-client");
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
            this.out = out;
            main.post(() -> sink.connected(true, who));
            int[] wh = sink.screen();
            send(out, "i " + wh[0] + " " + wh[1]);
            send(out, state);
            send(out, bt);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                linesTotal++;
                if (line.startsWith("q ")) { send(out, "a " + line.substring(2)); continue; } // 往復の時間を測る返事はすぐ返す
                final String l = line;
                main.post(() -> sink.line(l));
            }
        } catch (Exception e) {
            if (running && client == c) DuoLog.log("タッチパッド: 切れました(" + DuoLog.why(e) + ")");
        } finally {
            closeQuietly(c);
            if (client == c) {
                client = null;
                out = null;
                main.post(() -> sink.connected(false, who));
            }
        }
    }

    /** 操作している側が変わった(メインスレッドから)。iPhone の縁の光と左右の印に使う。 */
    void setState(String s) {
        if (s.equals(state)) return;
        state = s;
        OutputStream o = out;
        if (o == null) return;
        writer.execute(() -> { try { send(o, s); } catch (Exception ignored) {} });
    }

    /** CarPlay の Bluetooth が iPhone とつながったか(メインスレッドから)。iPhone の縁を緑に灯す合図。 */
    void setBluetooth(boolean on) {
        String s = on ? "b 1" : "b 0";
        if (s.equals(bt)) return;
        bt = s;
        OutputStream o = out;
        if (o == null) return;
        writer.execute(() -> { try { send(o, s); } catch (Exception ignored) {} });
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
