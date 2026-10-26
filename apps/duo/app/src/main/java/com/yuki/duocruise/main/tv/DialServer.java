package com.yuki.duocruise.main.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * iPhone の YouTube の「送信」ボタンに「Duo Cruise」を出すための DIAL サーバー。
 *
 * <ul>
 *   <li>SSDP(UDP 1900)で M-SEARCH に返事をする(つながっている全ての回線で)</li>
 *   <li>HTTP で機器の説明(dd.xml)と /apps/YouTube を返す</li>
 *   <li>iPhone から POST /apps/YouTube(pairingCode など)が来たら {@link Listener#onLaunch} を呼ぶ</li>
 * </ul>
 * Android の部品は使わない(Mac 上の単体テストで動かすため)。マルチキャストの許可(MulticastLock)は呼ぶ側が持つ。
 */
public final class DialServer {
    public static final String DIAL_ST = "urn:dial-multiscreen-org:service:dial:1";
    static final String SSDP_GROUP = "239.255.255.250";
    static final int MAX_BODY = 8192;

    public interface Listener {
        /** iPhone から起動の依頼。query は youtube.com/tv に付ける形(エンコード済み)。from は送り主と、見つかった回線。 */
        void onLaunch(String query, String from);
        /** iPhone から停止の依頼。 */
        void onStop(String from);
        /** 記録(日本語の1行)。 */
        void log(String line);
    }

    private final String friendlyName;
    private final String uuid;
    private final Listener listener;
    private final int ssdpPort;
    private final int preferredHttpPort;

    private volatile boolean running;
    private MulticastSocket ssdp;
    private ServerSocket http;
    private Thread ssdpThread, httpThread;
    private final Set<String> joined = Collections.synchronizedSet(new HashSet<String>());
    private long lastScan, lastNotify;

    private volatile boolean appRunning;
    private final Map<String, String> additionalData = Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private volatile String lastFoundOn = "";
    private volatile int searches, launches;

    public DialServer(String friendlyName, String uuid, Listener listener) {
        this(friendlyName, uuid, listener, 1900, 56790);
    }

    /** テスト用: SSDP と HTTP の口を指定する(HTTP は 0 で空いている口)。 */
    DialServer(String friendlyName, String uuid, Listener listener, int ssdpPort, int httpPort) {
        this.friendlyName = friendlyName;
        this.uuid = uuid;
        this.listener = listener;
        this.ssdpPort = ssdpPort;
        this.preferredHttpPort = httpPort;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        try {
            http = new ServerSocket();
            http.setReuseAddress(true);
            http.bind(new InetSocketAddress(preferredHttpPort));
        } catch (IOException e) {
            http = new ServerSocket(0);
        }
        ssdp = new MulticastSocket(null);
        ssdp.setReuseAddress(true);
        ssdp.bind(new InetSocketAddress(ssdpPort));
        ssdp.setSoTimeout(2000);
        ssdp.setTimeToLive(4);
        running = true;
        joinAll();
        ssdpThread = new Thread(this::ssdpLoop, "duo-dial-ssdp");
        httpThread = new Thread(this::httpLoop, "duo-dial-http");
        ssdpThread.setDaemon(true);
        httpThread.setDaemon(true);
        ssdpThread.start();
        httpThread.start();
        log("DIAL 開始: HTTP " + http.getLocalPort() + " / SSDP " + ssdpPort + " / 回線 " + joined);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (ssdp != null) ssdp.close(); } catch (Exception ignored) { }
        try { if (http != null) http.close(); } catch (Exception ignored) { }
        joined.clear();
        log("DIAL 停止");
    }

    public boolean isRunning() { return running; }
    public int httpPort() { return http == null ? -1 : http.getLocalPort(); }

    /** 今の状態(1行)。 */
    public String status() {
        return "DIAL " + (running ? "動作中" : "停止") + " 口=" + httpPort() + " 回線=" + joined
                + " 探された=" + searches + " 起動=" + launches
                + (lastFoundOn.isEmpty() ? "" : " 最後に見つかった回線=" + lastFoundOn)
                + (additionalData.isEmpty() ? "" : " screenId=" + (additionalData.containsKey("screenId") ? "あり" : "なし"));
    }

    /** TV の画面を閉じた・ホームへ戻したなど、アプリ側で止めたとき。 */
    public void markStopped() { appRunning = false; additionalData.clear(); }

    // ---------------- SSDP ----------------

    private void ssdpLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastScan > 10_000) { lastScan = now; joinAll(); }
            if (now - lastNotify > 60_000) { lastNotify = now; notifyAlive(); }
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                ssdp.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running) log("SSDP 受信の失敗: " + e);
                continue;
            }
            String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
            try {
                handleSsdp(msg, p.getAddress(), p.getPort());
            } catch (Exception e) {
                log("SSDP 返事の失敗: " + e);
            }
        }
    }

    void handleSsdp(String msg, InetAddress from, int fromPort) throws IOException {
        if (!msg.startsWith("M-SEARCH")) return;
        Map<String, String> h = headers(msg);
        String st = h.get("st");
        if (st == null) return;
        st = st.trim();
        boolean all = st.equalsIgnoreCase("ssdp:all");
        if (!all && !st.equalsIgnoreCase(DIAL_ST)) return;
        InetAddress local = localAddressFor(from);
        if (local == null) return;
        String ifName = interfaceName(local);
        searches++;
        lastFoundOn = ifName + "(" + local.getHostAddress() + ")";
        String resp = ssdpResponse(local.getHostAddress());
        byte[] b = resp.getBytes(StandardCharsets.UTF_8);
        ssdp.send(new DatagramPacket(b, b.length, from, fromPort));
        if (searches <= 20 || searches % 50 == 0) {
            log("DIAL 探索に返事: 回線=" + ifName + " 自分=" + local.getHostAddress() + " 相手=" + from.getHostAddress() + " (" + searches + "回目)");
        }
    }

    String ssdpResponse(String host) {
        return "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "EXT:\r\n"
                + "LOCATION: http://" + host + ":" + httpPort() + "/dd.xml\r\n"
                + "SERVER: Linux/Android UPnP/1.1 DuoCruise/1.0\r\n"
                + "ST: " + DIAL_ST + "\r\n"
                + "USN: uuid:" + uuid + "::" + DIAL_ST + "\r\n"
                + "BOOTID.UPNP.ORG: 1\r\n"
                + "CONFIGID.UPNP.ORG: 1\r\n"
                + "\r\n";
    }

    private void notifyAlive() {
        for (NetworkInterface ni : usableInterfaces()) {
            InetAddress a = ipv4Of(ni);
            if (a == null) continue;
            String m = "NOTIFY * HTTP/1.1\r\n"
                    + "HOST: " + SSDP_GROUP + ":1900\r\n"
                    + "CACHE-CONTROL: max-age=1800\r\n"
                    + "LOCATION: http://" + a.getHostAddress() + ":" + httpPort() + "/dd.xml\r\n"
                    + "NT: " + DIAL_ST + "\r\n"
                    + "NTS: ssdp:alive\r\n"
                    + "SERVER: Linux/Android UPnP/1.1 DuoCruise/1.0\r\n"
                    + "USN: uuid:" + uuid + "::" + DIAL_ST + "\r\n"
                    + "BOOTID.UPNP.ORG: 1\r\n"
                    + "\r\n";
            byte[] b = m.getBytes(StandardCharsets.UTF_8);
            try {
                ssdp.setNetworkInterface(ni);
                ssdp.send(new DatagramPacket(b, b.length, InetAddress.getByName(SSDP_GROUP), 1900));
            } catch (IOException ignored) {
                // 送れない回線は飛ばす
            }
        }
    }

    /** つながっている全ての回線(IPv4・マルチキャスト可)で SSDP の集まりに入る。新しくできた回線(p2p 等)も拾う。 */
    private void joinAll() {
        MulticastSocket s = ssdp;
        if (s == null) return;
        Set<String> now = new HashSet<>();
        for (NetworkInterface ni : usableInterfaces()) {
            now.add(ni.getName());
            if (joined.contains(ni.getName())) continue;
            try {
                s.joinGroup(new InetSocketAddress(InetAddress.getByName(SSDP_GROUP), ssdpPort), ni);
                joined.add(ni.getName());
                InetAddress a = ipv4Of(ni);
                log("DIAL 回線を追加: " + ni.getName() + (a == null ? "" : "(" + a.getHostAddress() + ")"));
            } catch (IOException e) {
                log("DIAL 回線に入れない: " + ni.getName() + " " + e.getMessage());
            }
        }
        synchronized (joined) {
            for (String n : new ArrayList<>(joined)) {
                if (!now.contains(n)) { joined.remove(n); log("DIAL 回線が消えた: " + n); }
            }
        }
    }

    static List<NetworkInterface> usableInterfaces() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            if (e == null) return out;
            for (NetworkInterface ni : Collections.list(e)) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                } catch (SocketException ex) { continue; }
                if (ipv4Of(ni) == null) continue;
                out.add(ni);
            }
        } catch (SocketException ignored) { }
        return out;
    }

    static InetAddress ipv4Of(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
        }
        return null;
    }

    /** 相手へ届く道の、自分側の住所(パケットは送らない)。 */
    static InetAddress localAddressFor(InetAddress remote) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(remote, 9);
            InetAddress a = probe.getLocalAddress();
            if (a == null || a.isAnyLocalAddress()) return null;
            return a;
        } catch (IOException e) {
            return null;
        }
    }

    static String interfaceName(InetAddress local) {
        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(local);
            return ni == null ? "?" : ni.getName();
        } catch (SocketException e) {
            return "?";
        }
    }

    // ---------------- HTTP ----------------

    private void httpLoop() {
        while (running) {
            final Socket c;
            try {
                c = http.accept();
            } catch (IOException e) {
                if (running) log("HTTP 受付の失敗: " + e);
                continue;
            }
            Thread t = new Thread(() -> serve(c), "duo-dial-req");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            Map<String, String> h = headers(head);
            int len = 0;
            try { len = Integer.parseInt(h.getOrDefault("content-length", "0").trim()); } catch (NumberFormatException ignored) { }
            if (len > MAX_BODY) { write(s.getOutputStream(), 413, "Payload Too Large", null, null, ""); return; }
            byte[] body = readN(in, len);
            Response r = handle(req[0], req[1], h, new String(body, StandardCharsets.UTF_8),
                    s.getInetAddress(), s.getLocalAddress());
            write(s.getOutputStream(), r.code, r.reason, r.type, r.extra, r.body);
        } catch (IOException e) {
            // 相手が途中で切った等。記録しない
        }
    }

    static final class Response {
        final int code; final String reason; final String type; final Map<String, String> extra; final String body;
        Response(int code, String reason, String type, Map<String, String> extra, String body) {
            this.code = code; this.reason = reason; this.type = type; this.extra = extra; this.body = body;
        }
    }

    /** HTTP の要求を1つ処理する(テストからも呼ぶ)。 */
    Response handle(String method, String rawPath, Map<String, String> h, String body, InetAddress from, InetAddress local) {
        String path = rawPath;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        Map<String, String> cors = new LinkedHashMap<>();
        cors.put("Access-Control-Allow-Origin", "*");
        cors.put("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        cors.put("Access-Control-Allow-Headers", "Content-Type");
        cors.put("Access-Control-Allow-Private-Network", "true");
        cors.put("Access-Control-Expose-Headers", "Location, Application-URL");
        String host = local.getHostAddress();
        String base = "http://" + host + ":" + httpPort();
        String who = from.getHostAddress() + " 回線=" + interfaceName(local);

        if (method.equals("OPTIONS")) return new Response(204, "No Content", null, cors, "");

        if (method.equals("GET") && (path.equals("/dd.xml") || path.equals("/"))) {
            Map<String, String> x = new LinkedHashMap<>(cors);
            x.put("Application-URL", base + "/apps/");
            return new Response(200, "OK", "application/xml; charset=utf-8", x, deviceXml());
        }
        if (path.equals("/apps/YouTube") || path.equals("/apps/YouTube/")) {
            if (method.equals("GET")) {
                return new Response(200, "OK", "application/xml; charset=utf-8", cors, appXml());
            }
            if (method.equals("POST")) {
                String query = toQuery(body);
                appRunning = true;
                launches++;
                log("DIAL 起動の依頼: 相手=" + who + " 項目=" + keysOf(body));
                listener.onLaunch(query, who);
                Map<String, String> x = new LinkedHashMap<>(cors);
                x.put("Location", base + "/apps/YouTube/run");
                return new Response(201, "Created", "text/plain", x, "");
            }
        }
        if (path.equals("/apps/YouTube/run") && method.equals("DELETE")) {
            appRunning = false;
            additionalData.clear();
            log("DIAL 停止の依頼: 相手=" + who);
            listener.onStop(who);
            return new Response(200, "OK", "text/plain", cors, "");
        }
        if (path.equals("/apps/YouTube/dial_data") && method.equals("POST")) {
            // TV 版 YouTube が自分の screenId 等を知らせてくる(来れば、次から iPhone がすぐつながる)
            additionalData.clear();
            additionalData.putAll(parseForm(body));
            log("DIAL 追加情報を受け取り: " + additionalData.keySet());
            return new Response(200, "OK", "text/plain", cors, "");
        }
        if (path.startsWith("/apps/")) return new Response(404, "Not Found", "text/plain", cors, "");
        return new Response(404, "Not Found", "text/plain", cors, "");
    }

    String deviceXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n"
                + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                + "  <device>\n"
                + "    <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>\n"
                + "    <friendlyName>" + esc(friendlyName) + "</friendlyName>\n"
                + "    <manufacturer>Duo Cruise</manufacturer>\n"
                + "    <modelName>Duo Cruise TV</modelName>\n"
                + "    <UDN>uuid:" + esc(uuid) + "</UDN>\n"
                + "    <serviceList><service>\n"
                + "      <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>\n"
                + "      <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>\n"
                + "      <controlURL>/ssdp/notfound</controlURL>\n"
                + "      <eventSubURL>/ssdp/notfound</eventSubURL>\n"
                + "      <SCPDURL>/ssdp/notfound</SCPDURL>\n"
                + "    </service></serviceList>\n"
                + "  </device>\n"
                + "</root>\n";
    }

    String appXml() {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\" dialVer=\"2.1\">\n")
                .append("  <name>YouTube</name>\n")
                .append("  <options allowStop=\"true\"/>\n")
                .append("  <state>").append(appRunning ? "running" : "stopped").append("</state>\n");
        if (appRunning) b.append("  <link rel=\"run\" href=\"run\"/>\n");
        synchronized (additionalData) {
            if (!additionalData.isEmpty()) {
                b.append("  <additionalData>\n");
                for (Map.Entry<String, String> e : additionalData.entrySet()) {
                    String k = e.getKey().replaceAll("[^A-Za-z0-9_]", "");
                    if (k.isEmpty()) continue;
                    b.append("    <").append(k).append('>').append(esc(e.getValue())).append("</").append(k).append(">\n");
                }
                b.append("  </additionalData>\n");
            }
        }
        b.append("</service>\n");
        return b.toString();
    }

    // ---------------- 小道具 ----------------

    /** POST の中身(form 形式)を、youtube.com/tv に付ける query に直す(キー・値をエンコードし直す)。 */
    static String toQuery(String body) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : parseForm(body).entrySet()) {
            if (e.getKey().isEmpty()) continue;
            if (b.length() > 0) b.append('&');
            b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return b.toString();
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null) return m;
        for (String part : body.trim().split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            m.put(dec(k), dec(v));
        }
        return m;
    }

    private static String keysOf(String body) { return parseForm(body).keySet().toString(); }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return ""; }
    }

    static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static Map<String, String> headers(String msg) {
        Map<String, String> h = new LinkedHashMap<>();
        String[] lines = msg.split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        return h;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 16384) {
            int ch = in.read();
            if (ch < 0) return b.size() == 0 ? null : b.toString("UTF-8");
            b.write(ch);
            // \r\n\r\n を探す
            if ((state == 0 || state == 2) && ch == '\r') state++;
            else if ((state == 1 || state == 3) && ch == '\n') state++;
            else state = ch == '\r' ? 1 : 0;
            if (state == 4) return b.toString("UTF-8");
        }
        return null;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[Math.max(0, n)];
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        return b;
    }

    private static void write(OutputStream out, int code, String reason, String type, Map<String, String> extra, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder s = new StringBuilder();
        s.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (type != null) s.append("Content-Type: ").append(type).append("\r\n");
        s.append("Content-Length: ").append(b.length).append("\r\n");
        s.append("Connection: close\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) s.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        s.append("\r\n");
        out.write(s.toString().getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private void log(String line) {
        try { listener.log(line); } catch (Exception ignored) { }
    }
}
