package com.yuki.duocruise.main;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接続の記録の常駐サービス(前面サービス)。機器の起動(BOOT_COMPLETED)・本体の更新・画面を開いたときに始まり、
 * Android に止められても START_STICKY で戻る。見張るのは:
 *   ・Wi-Fi(本体のつながり先 SSID・BSSID・周波数・電波・IP、iPhone のテザリングか)と、通信の道ごとの validated
 *   ・OTTO 本体のホットスポット(オン/オフ・口の IP・つながっている機器)、Wi-Fi Direct(P2P)の群れ
 *   ・Bluetooth(電源・ペアリング・つながり・A2DP/HFP などの音声と通話のつながり)
 *   ・起動・終了(SHUTDOWN)・起動し直しの理由(sys.boot.reason)・前回アプリが終わった理由(Android の記録)
 * 加えて 30 秒ごとに今の状態をまとめて 1 行(snap)。CarPlay・YouTube の状態も画面の側から受けてここに載せる。
 */
public final class ConnLogService extends Service {
    private static final int NID = 0x0C0C;
    private static final String CH = "duo-connlog";
    private static final long SNAP_MS = 30_000;
    private static final String PREFS = "connlog";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private final Map<Network, String> nets = new ConcurrentHashMap<>();
    private final Map<Network, NetworkCapabilities> caps = new ConcurrentHashMap<>();
    private final Map<Network, LinkProperties> links = new ConcurrentHashMap<>();
    private final Map<Integer, BluetoothProfile> proxies = new ConcurrentHashMap<>();
    private String lastWifiKey = "", lastIfaces = "", lastBonded = "";
    private boolean started;

    private final Runnable snap = new Runnable() {
        @Override public void run() {
            main.postDelayed(this, SNAP_MS);
            snapshot();
        }
    };

    /** どこからでも呼んでよい(何度でも)。 */
    public static void start(Context c, String why) {
        try {
            Intent i = new Intent(c, ConnLogService.class).putExtra("why", why);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            // 裏から始められない時(Android の制限)でも、記録だけは残す
            ConnLog.init(c);
            ConnLog.event("sys", "service-start-failed", "why", why, "err", DuoLog.why(t));
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        ConnLog.init(this);
        installCrash();
        foreground();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        String why = intent == null ? "Android が戻した(START_STICKY)" : intent.getStringExtra("why");
        if (!started) {
            started = true;
            bootAndExit(why);
            watchNetworks();
            watchBroadcasts();
            watchBluetoothProfiles();
            main.postDelayed(snap, 2_000);
        } else {
            ConnLog.event("sys", "service-poke", "why", why);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        ConnLog.event("sys", "service-stop");
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(rx); } catch (Throwable ignored) { }
        try { if (netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Throwable ignored) { }
        BluetoothAdapter a = adapter();
        for (Map.Entry<Integer, BluetoothProfile> e : proxies.entrySet())
            try { a.closeProfileProxy(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        ConnLog.event("sys", "task-removed");
        super.onTaskRemoved(rootIntent);
    }

    private void foreground() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, "接続の記録", NotificationManager.IMPORTANCE_MIN));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            Notification n = b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Duo Cruise")
                    .setContentText("CarPlay・YouTube・Wi-Fi・Bluetooth の接続を記録しています")
                    .setOngoing(true).build();
            startForeground(NID, n);
        } catch (Throwable t) {
            ConnLog.event("sys", "foreground-failed", "err", DuoLog.why(t));
        }
    }

    // ───────── 起動・終了 ─────────

    /** 起動し直しの理由と、前回の電源の切れ方(SHUTDOWN を受けたか・最後に生きていた時刻)、前回アプリが終わった理由。 */
    private void bootAndExit(String why) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String bootId = readLine("/proc/sys/kernel/random/boot_id");
        String prevBoot = p.getString("bootId", null);
        boolean newBoot = bootId != null && !bootId.equals(prevBoot);
        ConnLog.event("sys", "service-start", "why", why, "ver", appVersion(), "pid", android.os.Process.myPid(),
                "bootId", bootId, "uptimeS", SystemClock.elapsedRealtime() / 1000);
        if (newBoot) {
            ConnLog.event("sys", "boot",
                    "bootReason", prop("sys.boot.reason"), "bootReasonLast", prop("sys.boot.reason.last"),
                    "roBootReason", prop("ro.boot.bootreason"), "persistReason", prop("persist.sys.boot.reason"),
                    "prevLastAlive", p.getString("lastAlive", null),
                    "prevShutdown", p.getString("shutdownAt", null),            // null なら前回は SHUTDOWN を受けずに切れた(電源が突然切れた等)
                    "uptimeS", SystemClock.elapsedRealtime() / 1000);
            p.edit().putString("bootId", bootId).remove("shutdownAt").apply();
        }
        exitReasons(p);
    }

    /** Android が覚えている、このアプリの前回までの終わり方(落ちた・止められた・メモリ不足など)。まだ書いていない分だけ。 */
    private void exitReasons(SharedPreferences p) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(getPackageName(), 0, 10);
            long seen = p.getLong("exitSeen", 0), newest = seen;
            List<ApplicationExitInfo> fresh = new ArrayList<>();
            for (ApplicationExitInfo e : list) if (e.getTimestamp() > seen) fresh.add(e);
            Collections.reverse(fresh);                                              // 古い順に書く
            for (ApplicationExitInfo e : fresh) {
                newest = Math.max(newest, e.getTimestamp());
                ConnLog.event("sys", "app-exit", "at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ",
                                java.util.Locale.ROOT).format(new java.util.Date(e.getTimestamp())),
                        "reason", exitReason(e.getReason()), "sub", e.getStatus(), "desc", e.getDescription(),
                        "process", e.getProcessName(), "pid", e.getPid(), "importance", e.getImportance());
            }
            p.edit().putLong("exitSeen", newest).apply();
        } catch (Throwable t) {
            ConnLog.event("sys", "app-exit-unreadable", "err", DuoLog.why(t));
        }
    }

    private static String exitReason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit-self";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low-memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "crash-native";
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "init-failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission-change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive-resource";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user-requested(force-stop など)";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user-stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency-died";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            default: return "unknown(" + r + ")";
        }
    }

    /** アプリが落ちる瞬間の例外を、その場で書き切る(前の受け口へもつなぐ)。 */
    private static boolean crashHooked;
    static synchronized void installCrash() {
        if (crashHooked) return;
        crashHooked = true;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String s = sw.toString();
                ConnLog.eventNow("sys", "crash", "thread", thread.getName(), "ex", String.valueOf(e),
                        "stack", s.length() > 4000 ? s.substring(0, 4000) : s);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(thread, e);
        });
    }

    // ───────── 知らせ(Wi-Fi・ホットスポット・Bluetooth・電源) ─────────

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try { onBroadcast(i); } catch (Throwable t) { ConnLog.event("sys", "rx-error", "action", i.getAction(), "err", DuoLog.why(t)); }
        }
    };

    private static final String AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final String[] BT_PROFILE_ACTIONS = {
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.mapmce.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED",
    };

    private void watchBroadcasts() {
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(AP_STATE);
        f.addAction(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        for (String a : BT_PROFILE_ACTIONS) f.addAction(a);
        f.addAction(Intent.ACTION_SHUTDOWN);
        f.addAction("android.intent.action.REBOOT");
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);
    }

    @SuppressLint("MissingPermission")
    private void onBroadcast(Intent i) {
        String a = i.getAction();
        if (a == null) return;
        switch (a) {
            case WifiManager.WIFI_STATE_CHANGED_ACTION: {
                int s = i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                ConnLog.event("wifi", "power", "state", wifiPower(s));
                return;
            }
            case WifiManager.NETWORK_STATE_CHANGED_ACTION: {
                android.net.NetworkInfo ni = i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                String st = ni == null ? "?" : String.valueOf(ni.getDetailedState());
                JSONObject w = wifi();
                String key = st + "|" + w.optString("ssid") + "|" + w.optString("bssid") + "|" + w.optString("ip");
                if (key.equals(lastWifiKey)) return;                       // 同じ状態の知らせは何度も来るので 1 回だけ
                lastWifiKey = key;
                ConnLog.event("wifi", "sta", "state", st, "wifi", w);
                return;
            }
            case AP_STATE: {
                int s = i.getIntExtra("wifi_state", -1), prev = i.getIntExtra("previous_wifi_state", -1);
                main.postDelayed(() -> ConnLog.event("ap", "state", "state", apState(s), "prev", apState(prev),
                        "iface", i.getStringExtra("wifi_ap_interface_name"), "ap", ap()), 1500); // 口に IP が付くのを少し待つ
                return;
            }
            case android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                ConnLog.event("ap", "p2p-power", "on", i.getIntExtra(android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_STATE, 0)
                        == android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                return;
            case android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                android.net.wifi.p2p.WifiP2pInfo info = i.getParcelableExtra(android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                android.net.wifi.p2p.WifiP2pGroup g = i.getParcelableExtra(android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                ConnLog.event("ap", "p2p", "formed", info != null && info.groupFormed, "owner", info != null && info.isGroupOwner,
                        "ownerIp", info == null || info.groupOwnerAddress == null ? null : info.groupOwnerAddress.getHostAddress(),
                        "group", g == null ? null : g.getNetworkName(), "iface", g == null ? null : g.getInterface(),
                        "clients", g == null ? null : g.getClientList().size(), "freq", g == null || Build.VERSION.SDK_INT < 29 ? null : g.getFrequency());
                return;
            }
            case BluetoothAdapter.ACTION_STATE_CHANGED: {
                int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                ConnLog.event("bt", "power", "state", btPower(s), "prev", btPower(i.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1)));
                if (s == BluetoothAdapter.STATE_ON) main.postDelayed(this::watchBluetoothProfiles, 2000);
                return;
            }
            case BluetoothDevice.ACTION_ACL_CONNECTED:
            case BluetoothDevice.ACTION_ACL_DISCONNECTED: {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                ConnLog.event("bt", a.equals(BluetoothDevice.ACTION_ACL_CONNECTED) ? "link-up" : "link-down", "dev", dev(d));
                return;
            }
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                ConnLog.event("bt", "bond", "dev", dev(d), "state", bond(i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)),
                        "prev", bond(i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)));
                return;
            }
            case Intent.ACTION_SHUTDOWN:
            case "android.intent.action.REBOOT":
                shutdown(this, a);
                return;
            case Intent.ACTION_SCREEN_ON: ConnLog.event("sys", "screen-on"); return;
            case Intent.ACTION_SCREEN_OFF: ConnLog.event("sys", "screen-off"); return;
            case Intent.ACTION_POWER_CONNECTED: ConnLog.event("sys", "power-connected"); return;
            case Intent.ACTION_POWER_DISCONNECTED: ConnLog.event("sys", "power-disconnected"); return;
            default:
                if (a.contains(".profile.action.CONNECTION_STATE_CHANGED") || a.contains("LE_AUDIO_CONNECTION_STATE_CHANGED")) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    String profile = a.replace("android.bluetooth.", "").replace(".profile.action.CONNECTION_STATE_CHANGED", "")
                            .replace("action.LE_AUDIO_CONNECTION_STATE_CHANGED", "le-audio");
                    ConnLog.event("bt", "profile", "profile", profile, "dev", dev(d),
                            "state", conn(i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                            "prev", conn(i.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)));
                }
        }
    }

    /** 電源が切れる直前(BootReceiver・このサービスの両方から)。その場で書き切る。 */
    static void shutdown(Context c, String action) {
        ConnLog.init(c);
        ConnLog.eventNow("sys", "shutdown", "action", action, "uptimeS", SystemClock.elapsedRealtime() / 1000);
        try {
            c.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("shutdownAt", new java.util.Date().toString()).commit();
        } catch (Throwable ignored) { }
    }

    // ───────── 通信の道(ConnectivityManager) ─────────

    private void watchNetworks() {
        cm = getSystemService(ConnectivityManager.class);
        NetworkRequest.Builder b = new NetworkRequest.Builder();
        if (Build.VERSION.SDK_INT >= 31) b.clearCapabilities();          // Wi-Fi・VPN・制限つき(CarPlay 用など)も全部見る
        else b.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { ConnLog.event("net", "available", "net", n.toString()); }
            @Override public void onLost(Network n) {
                String was = nets.remove(n);
                caps.remove(n); links.remove(n);
                ConnLog.event("net", "lost", "net", n.toString(), "was", was);
            }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { caps.put(n, c); netChanged(n); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { links.put(n, lp); netChanged(n); }
        };
        try { cm.registerNetworkCallback(b.build(), netCb); }
        catch (Throwable t) { ConnLog.event("net", "watch-failed", "err", DuoLog.why(t)); }
        try {
            Network d = cm.getActiveNetwork();
            ConnLog.event("net", "default", "net", d == null ? null : d.toString());
        } catch (Throwable ignored) { }
    }

    /** 道の中身(種類・validated・口・IP)が変わったときだけ 1 行(電波の強さだけの変化では書かない)。 */
    private void netChanged(Network n) {
        String now = netText(n);
        String was = nets.put(n, now);
        if (now.equals(was)) return;
        Network d = null;
        try { d = cm.getActiveNetwork(); } catch (Throwable ignored) { }
        ConnLog.event("net", was == null ? "up" : "changed", "net", n.toString(), "now", now, "was", was,
                "default", n.equals(d));
    }

    private String netText(Network n) {
        NetworkCapabilities c = caps.get(n);
        LinkProperties lp = links.get(n);
        StringBuilder b = new StringBuilder();
        if (c != null) {
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) b.append("wifi ");
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) b.append("cell ");
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) b.append("bt ");
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) b.append("eth ");
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) b.append("vpn ");
            if (Build.VERSION.SDK_INT >= 26 && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) b.append("aware ");
            b.append(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? "internet " : "no-internet ");
            b.append(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "validated " : "not-validated ");
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) b.append("restricted ");
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) b.append("metered ");
        }
        if (lp != null) {
            b.append("iface=").append(lp.getInterfaceName());
            for (LinkAddress la : lp.getLinkAddresses())
                if (la.getAddress() instanceof Inet4Address) b.append(" ip=").append(la.getAddress().getHostAddress());
            for (android.net.RouteInfo r : lp.getRoutes())
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) b.append(" gw=").append(r.getGateway().getHostAddress());
        }
        return b.toString().trim();
    }

    // ───────── Bluetooth のつながり(プロファイルごと) ─────────

    private static final int[] PROFILES = {
            BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 11 /* A2DP_SINK */, 16 /* HEADSET_CLIENT */,
            4 /* HID_HOST */, 5 /* PAN */, 12 /* AVRCP_CONTROLLER */, 18 /* MAP_CLIENT */, 17 /* PBAP_CLIENT */};

    private static String profileName(int p) {
        switch (p) {
            case BluetoothProfile.A2DP: return "a2dp";
            case BluetoothProfile.HEADSET: return "hfp(headset)";
            case 11: return "a2dp-sink";
            case 16: return "hfp-client";
            case 4: return "hid";
            case 5: return "pan";
            case 12: return "avrcp-ct";
            case 18: return "map-client";
            case 17: return "pbap-client";
            default: return "p" + p;
        }
    }

    private void watchBluetoothProfiles() {
        BluetoothAdapter a = adapter();
        if (a == null) return;
        for (int p : PROFILES) {
            if (proxies.containsKey(p)) continue;
            try {
                a.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) { proxies.put(profile, proxy); }
                    @Override public void onServiceDisconnected(int profile) { proxies.remove(profile); }
                }, p);
            } catch (Throwable ignored) { /* その機器に無いプロファイル */ }
        }
    }

    private BluetoothAdapter adapter() {
        try {
            BluetoothManager m = getSystemService(BluetoothManager.class);
            return m == null ? null : m.getAdapter();
        } catch (Throwable t) { return null; }
    }

    // ───────── 30 秒ごとのまとめ ─────────

    private void snapshot() {
        try {
            JSONObject bt = bt();
            JSONObject w = wifi();
            JSONObject ap = ap();
            JSONArray nl = new JSONArray();
            Network d = null;
            try { d = cm.getActiveNetwork(); } catch (Throwable ignored) { }
            for (String v : byId(nets).values()) nl.put(v);
            String defText = d == null ? null : nets.get(d);
            // 口(インターフェース)の顔ぶれが変わったら、それも 1 行(ホットスポット・P2P・VPN の出入り)
            String ifs = ap.optJSONArray("ifaces") == null ? "" : ap.optJSONArray("ifaces").toString();
            if (!ifs.equals(lastIfaces)) { if (!lastIfaces.isEmpty()) ConnLog.event("net", "ifaces", "ifaces", ap.optJSONArray("ifaces")); lastIfaces = ifs; }
            ConnLog.event("snap", "state", "car", ConnLog.carSnap(), "yt", ConnLog.ytSnap(), "wifi", w, "ap", ap,
                    "nets", nl, "default", defText, "bt", bt, "screen", DuoActivity.live != null);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("lastAlive", new java.util.Date().toString()).apply();
        } catch (Throwable t) {
            ConnLog.event("sys", "snap-error", "err", DuoLog.why(t));
        }
    }

    private static Map<String, String> byId(Map<Network, String> m) {
        Map<String, String> r = new TreeMap<>();
        for (Map.Entry<Network, String> e : m.entrySet()) r.put(e.getKey().toString(), e.getValue());
        return r;
    }

    /** 本体の Wi-Fi(子機)のつながり先。iPhone のテザリング(172.20.10.x)かも見分ける。 */
    @SuppressWarnings("deprecation")
    private JSONObject wifi() {
        JSONObject o = new JSONObject();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            o.put("on", wm.isWifiEnabled());
            WifiInfo i = wm.getConnectionInfo();
            if (i != null) {
                o.put("state", String.valueOf(i.getSupplicantState()));
                o.put("ssid", i.getSSID());
                o.put("bssid", i.getBSSID());
                o.put("freq", i.getFrequency());
                o.put("rssi", i.getRssi());
                o.put("linkMbps", i.getLinkSpeed());
                int ip = i.getIpAddress();
                String ips = ip == 0 ? null : (ip & 0xff) + "." + (ip >> 8 & 0xff) + "." + (ip >> 16 & 0xff) + "." + (ip >> 24 & 0xff);
                if (ips != null) o.put("ip", ips);
                String ssid = i.getSSID() == null ? "" : i.getSSID();
                boolean tether = (ips != null && ips.startsWith("172.20.10.")) || ssid.toLowerCase(java.util.Locale.ROOT).contains("iphone");
                o.put("iphoneTether", tether);
            }
            // この Wi-Fi の道が通信できるか(validated)
            if (cm != null) for (Map.Entry<Network, NetworkCapabilities> e : caps.entrySet()) {
                NetworkCapabilities c = e.getValue();
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    o.put("validated", c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                    break;
                }
            }
        } catch (Throwable t) {
            try { o.put("err", DuoLog.why(t)); } catch (Throwable ignored) { }
        }
        return o;
    }

    /** OTTO 本体のホットスポット・P2P の口と、つながっている機器(読める範囲で)。 */
    private JSONObject ap() {
        JSONObject o = new JSONObject();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            try {
                java.lang.reflect.Method m = WifiManager.class.getMethod("getWifiApState");
                o.put("state", apState((Integer) m.invoke(wm)));
            } catch (Throwable t) { o.put("state", "unreadable"); } // Android の制限で読めないときは下の口から推定する
            JSONArray ifaces = new JSONArray();
            List<String> apIfaces = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                StringBuilder b = new StringBuilder(ni.getName());
                for (InetAddress ad : Collections.list(ni.getInetAddresses()))
                    if (ad instanceof Inet4Address) b.append(' ').append(ad.getHostAddress());
                ifaces.put(b.toString());
                String n = ni.getName();
                if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("wlan1") || n.startsWith("p2p")) apIfaces.add(n);
            }
            o.put("ifaces", ifaces);
            if ("unreadable".equals(o.optString("state")))
                o.put("state", apIfaces.isEmpty() ? "off(口から推定)" : "on(口から推定 " + apIfaces + ")");
            // つながっている機器: ARP の表(読めるかは機器しだい。Android 10 以降の普通のアプリは読めないことが多い)
            JSONArray clients = new JSONArray();
            boolean arpOk = false;
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
                arpOk = true;
                String line = r.readLine(); // 見出し
                while ((line = r.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length >= 6 && !"0x0".equals(f[2]) && apIfaces.contains(f[5])) clients.put(f[0] + " " + f[3] + " " + f[5]);
                }
            } catch (Throwable ignored) { }
            if (!arpOk) clients = neigh(apIfaces);
            o.put("clients", clients == null ? "unreadable" : clients);
            if (clients != null) o.put("count", clients.length());
        } catch (Throwable t) {
            try { o.put("err", DuoLog.why(t)); } catch (Throwable ignored) { }
        }
        return o;
    }

    /** ip neigh(読めれば)。読めなければ null。 */
    private static JSONArray neigh(List<String> apIfaces) {
        try {
            Process p = new ProcessBuilder("ip", "neigh").redirectErrorStream(true).start();
            JSONArray a = new JSONArray();
            boolean any = false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 3 || !"dev".equals(f[1])) continue;
                    any = true;
                    if (!apIfaces.contains(f[2]) || line.contains("FAILED") || line.contains("INCOMPLETE")) continue;
                    if (!f[0].contains(".")) continue; // IPv4 だけ
                    a.put(f[0] + " " + (f.length > 4 ? f[4] : "") + " " + f[2] + " " + f[f.length - 1]);
                }
            }
            p.waitFor();
            return any || p.exitValue() == 0 ? a : null;
        } catch (Throwable t) { return null; }
    }

    @SuppressLint("MissingPermission")
    private JSONObject bt() {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = adapter();
            if (a == null) { o.put("state", "none"); return o; }
            o.put("state", btPower(a.getState()));
            JSONArray bonded = new JSONArray();
            try { for (BluetoothDevice d : a.getBondedDevices()) bonded.put(dev(d)); }
            catch (SecurityException e) { bonded.put("権限なし(BLUETOOTH_CONNECT)"); }
            // ペアリング済みの一覧は変わったときだけ 1 行(まとめには数だけ。記録を軽くする)
            String bs = bonded.toString();
            if (!bs.equals(lastBonded)) { lastBonded = bs; ConnLog.event("bt", "bonded", "devices", bonded); }
            o.put("bonded", bonded.length());
            JSONObject conn = new JSONObject();
            for (Map.Entry<Integer, BluetoothProfile> e : proxies.entrySet()) {
                try {
                    List<BluetoothDevice> ds = e.getValue().getConnectedDevices();
                    if (ds.isEmpty()) continue;
                    JSONArray arr = new JSONArray();
                    for (BluetoothDevice d : ds) arr.put(dev(d));
                    conn.put(profileName(e.getKey()), arr);
                } catch (Throwable ignored) { }
            }
            o.put("connected", conn);
        } catch (Throwable t) {
            try { o.put("err", DuoLog.why(t)); } catch (Throwable ignored) { }
        }
        return o;
    }

    @SuppressLint("MissingPermission")
    private static String dev(BluetoothDevice d) {
        if (d == null) return null;
        String name;
        try { name = d.getName(); } catch (Throwable t) { name = "?"; }
        return name + " " + d.getAddress();
    }

    // ───────── 小物 ─────────

    private static String wifiPower(int s) {
        switch (s) {
            case WifiManager.WIFI_STATE_DISABLED: return "off";
            case WifiManager.WIFI_STATE_DISABLING: return "turning-off";
            case WifiManager.WIFI_STATE_ENABLED: return "on";
            case WifiManager.WIFI_STATE_ENABLING: return "turning-on";
            default: return "unknown";
        }
    }

    private static String apState(int s) {
        switch (s) {
            case 10: return "turning-off";
            case 11: return "off";
            case 12: return "turning-on";
            case 13: return "on";
            case 14: return "failed";
            default: return "unknown(" + s + ")";
        }
    }

    private static String btPower(int s) {
        switch (s) {
            case BluetoothAdapter.STATE_OFF: return "off";
            case BluetoothAdapter.STATE_TURNING_ON: return "turning-on";
            case BluetoothAdapter.STATE_ON: return "on";
            case BluetoothAdapter.STATE_TURNING_OFF: return "turning-off";
            default: return "unknown(" + s + ")";
        }
    }

    private static String bond(int s) {
        switch (s) {
            case BluetoothDevice.BOND_NONE: return "none";
            case BluetoothDevice.BOND_BONDING: return "bonding";
            case BluetoothDevice.BOND_BONDED: return "bonded";
            default: return "unknown(" + s + ")";
        }
    }

    private static String conn(int s) {
        switch (s) {
            case BluetoothProfile.STATE_DISCONNECTED: return "disconnected";
            case BluetoothProfile.STATE_CONNECTING: return "connecting";
            case BluetoothProfile.STATE_CONNECTED: return "connected";
            case BluetoothProfile.STATE_DISCONNECTING: return "disconnecting";
            default: return "unknown(" + s + ")";
        }
    }

    private static String prop(String k) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = (String) c.getMethod("get", String.class).invoke(null, k);
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) { }
        try {
            Process p = new ProcessBuilder("getprop", k).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String v = r.readLine();
                return v == null || v.isEmpty() ? null : v.trim();
            }
        } catch (Throwable t) { return null; }
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(path)))) { return r.readLine(); }
        catch (Throwable t) { return null; }
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable t) { return "?"; }
    }
}
