package com.yuki.duocruise.main.tv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DialServer の単体テスト(Mac の Java だけで動く。JUnit は使わない:app/build.gradle は B の持ち場のため)。
 * 実行方法は apps/duo/app/src/main/java/com/yuki/duocruise/main/tv/README.md を参照。
 */
public final class DialServerTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        final List<String> launched = new ArrayList<>();
        final List<String> stopped = new ArrayList<>();
        DialServer.Listener l = new DialServer.Listener() {
            @Override public void onLaunch(String query, String from) { launched.add(query); }
            @Override public void onStop(String from) { stopped.add(from); }
            @Override public void log(String line) { System.out.println("  記録: " + line); }
        };
        int ssdpPort = freeUdpPort();
        DialServer d = new DialServer("Duo Cruise", "11111111-2222-3333-4444-555555555555", l, ssdpPort, 0);
        d.start();
        try {
            InetAddress lo = InetAddress.getByName("127.0.0.1");

            // 1. SSDP: DIAL の M-SEARCH には返事がある
            String resp = mSearch(lo, ssdpPort, DialServer.DIAL_ST);
            check("SSDP 返事がある", resp != null);
            check("SSDP 200 OK", resp != null && resp.startsWith("HTTP/1.1 200 OK"));
            check("SSDP ST", resp != null && resp.contains("ST: " + DialServer.DIAL_ST));
            check("SSDP USN", resp != null && resp.contains("USN: uuid:11111111-2222-3333-4444-555555555555::" + DialServer.DIAL_ST));
            String location = header(resp, "LOCATION");
            check("SSDP LOCATION", location != null && location.equals("http://127.0.0.1:" + d.httpPort() + "/dd.xml"));

            // 2. 関係ない ST には返事しない
            check("関係ない ST は無視", mSearch(lo, ssdpPort, "urn:schemas-upnp-org:device:MediaRenderer:1") == null);
            // ssdp:all には返事する
            check("ssdp:all に返事", mSearch(lo, ssdpPort, "ssdp:all") != null);

            // 3. 機器の説明 XML と Application-URL
            HttpURLConnection c = (HttpURLConnection) new URL(location).openConnection();
            check("dd.xml 200", c.getResponseCode() == 200);
            check("Application-URL", ("http://127.0.0.1:" + d.httpPort() + "/apps/").equals(c.getHeaderField("Application-URL")));
            String xml = read(c.getInputStream());
            check("friendlyName", xml.contains("<friendlyName>Duo Cruise</friendlyName>"));
            check("deviceType", xml.contains("urn:dial-multiscreen-org:device:dial:1"));
            check("UDN", xml.contains("<UDN>uuid:11111111-2222-3333-4444-555555555555</UDN>"));

            String apps = "http://127.0.0.1:" + d.httpPort() + "/apps/YouTube";
            // 4. 起動前は stopped
            String s1 = get(apps);
            check("最初は stopped", s1.contains("<state>stopped</state>") && s1.contains("<name>YouTube</name>"));
            check("allowStop", s1.contains("allowStop=\"true\""));

            // 5. POST(iPhone の YouTube が送る形)で起動 → 201 と LOCATION、query が渡る
            HttpURLConnection p = (HttpURLConnection) new URL(apps).openConnection();
            p.setRequestMethod("POST");
            p.setDoOutput(true);
            p.setRequestProperty("Content-Type", "text/plain; charset=\"utf-8\"");
            try (OutputStream o = p.getOutputStream()) {
                o.write("pairingCode=abc-123%20x&v=dQw4w9WgXcQ&t=0&theme=cl".getBytes(StandardCharsets.UTF_8));
            }
            check("POST 201", p.getResponseCode() == 201);
            check("POST LOCATION", (apps + "/run").equals(p.getHeaderField("Location")));
            check("起動が1回届く", launched.size() == 1);
            check("query がエンコードし直されている", launched.size() == 1
                    && launched.get(0).equals("pairingCode=abc-123+x&v=dQw4w9WgXcQ&t=0&theme=cl"));

            // 6. 起動後は running と run の link
            String s2 = get(apps);
            check("起動後は running", s2.contains("<state>running</state>") && s2.contains("<link rel=\"run\" href=\"run\"/>"));

            // 7. TV 版からの追加情報(screenId)が XML に出る。悪い文字はエスケープ
            HttpURLConnection a = (HttpURLConnection) new URL(apps + "/dial_data").openConnection();
            a.setRequestMethod("POST");
            a.setDoOutput(true);
            try (OutputStream o = a.getOutputStream()) {
                o.write("screenId=sc%3Cr%3E1&the%3Cme=x".getBytes(StandardCharsets.UTF_8));
            }
            check("dial_data 200", a.getResponseCode() == 200);
            String s3 = get(apps);
            check("screenId が出る(エスケープ済み)", s3.contains("<screenId>sc&lt;r&gt;1</screenId>"));
            check("要素名の悪い文字は消す", s3.contains("<theme>x</theme>") && !s3.contains("<the<me>"));

            // 8. CORS の事前確認(OPTIONS)
            HttpURLConnection op = (HttpURLConnection) new URL(apps + "/dial_data").openConnection();
            op.setRequestMethod("OPTIONS");
            check("OPTIONS 204", op.getResponseCode() == 204);
            check("Private Network 許可", "true".equals(op.getHeaderField("Access-Control-Allow-Private-Network")));

            // 9. DELETE で停止
            HttpURLConnection del = (HttpURLConnection) new URL(apps + "/run").openConnection();
            del.setRequestMethod("DELETE");
            check("DELETE 200", del.getResponseCode() == 200);
            check("停止が届く", stopped.size() == 1);
            check("停止後は stopped", get(apps).contains("<state>stopped</state>"));

            // 10. 知らないアプリは 404、大きすぎる中身は 413
            HttpURLConnection nf = (HttpURLConnection) new URL("http://127.0.0.1:" + d.httpPort() + "/apps/Netflix").openConnection();
            check("知らないアプリ 404", nf.getResponseCode() == 404);
            check("大きすぎる中身 413", rawStatus(d.httpPort(), "POST /apps/YouTube HTTP/1.1\r\nContent-Length: 999999\r\n\r\n") == 413);

            check("状態の1行", d.status().contains("探された=") && d.status().contains("起動=1"));
        } finally {
            d.stop();
        }
        check("停止後は動いていない", !d.isRunning());

        if (failures == 0) {
            System.out.println("すべて合格");
        } else {
            System.out.println("不合格: " + failures);
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "OK   " : "NG   ") + name);
        if (!ok) failures++;
    }

    private static String mSearch(InetAddress to, int port, String st) throws Exception {
        String m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: " + st + "\r\n\r\n";
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1500);
            byte[] b = m.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, to, port));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                return null;
            }
            return new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
        }
    }

    private static String header(String msg, String name) {
        if (msg == null) return null;
        for (String line : msg.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        return read(c.getInputStream());
    }

    private static int rawStatus(int port, String req) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            String first = read(s.getInputStream()).split("\r\n")[0];
            return Integer.parseInt(first.split(" ")[1]);
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) { return s.getLocalPort(); }
    }
}
