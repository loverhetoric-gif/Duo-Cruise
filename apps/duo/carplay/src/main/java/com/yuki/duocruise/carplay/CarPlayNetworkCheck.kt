package com.yuki.duocruise.carplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

/**
 * 「回線を選ばない」ための確認。ネットが SIM・テザリング・Wi-Fi のどれでも CarPlay は動かすが、
 * 無線 CarPlay の Wi-Fi(OTTO が 5GHz の P2P を立てる)とぶつかりそうな時は、記録に出して知らせる。
 *
 * OTTO の Wi-Fi は電波が1系統なので、Wi-Fi 子機(STA)がつながっていると P2P も同じ ch になる。
 * そのため ch によっては P2P が立たない・2.4GHz に落ちる。SSID などの個人の情報は記録しない。
 */
internal class CarPlayNetworkCheck(private val context: Context) {

    private data class Snapshot(
        val cellular: Boolean,
        val wifiStaMHz: Int?,
        val iphoneTethering: Boolean,
        val ethernetOrOther: Boolean,
        val defaultTransport: String,
    )

    fun describe(): String {
        val s = snapshot() ?: return "回線の情報を読めません"
        val parts = mutableListOf<String>()
        parts += "既定の回線=${s.defaultTransport}"
        parts += "SIM=${if (s.cellular) "あり" else "なし"}"
        parts += if (s.wifiStaMHz != null) {
            "Wi-Fi子機=接続中 ${s.wifiStaMHz}MHz(${band(s.wifiStaMHz)})" +
                if (s.iphoneTethering) " iPhoneテザリングらしい" else ""
        } else {
            "Wi-Fi子機=なし"
        }
        if (s.ethernetOrOther) parts += "ほかの回線あり"
        return parts.joinToString(" ")
    }

    /** CarPlay を始める前に分かる取り合い。無ければ null。 */
    fun conflictBeforeStart(): String? {
        val s = snapshot() ?: return null
        val mhz = s.wifiStaMHz ?: return null
        val notes = mutableListOf<String>()
        if (s.iphoneTethering) {
            notes += "iPhone のテザリングに Wi-Fi でつながっています。無線 CarPlay が始まると iPhone の Wi-Fi は " +
                "CarPlay 側に移るので、テザリングが切れる/CarPlay が立たない可能性。車内は SIM 回線がおすすめ"
        }
        when {
            mhz < 3000 -> notes += "Wi-Fi 子機が 2.4GHz(${mhz}MHz)。P2P が 5GHz で立たず、CarPlay が不安定になる可能性"
            isDfs(mhz) -> notes += "Wi-Fi 子機が DFS の ch(${mhz}MHz)。この ch では P2P が立たないことが多い。ch36〜48 が望ましい"
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    }

    /** P2P が立った後に分かる取り合い。無ければ null。 */
    fun conflictAfterHotspot(bandLabel: String, channel: Int): String? = when {
        bandLabel.startsWith("2.4") ->
            "CarPlay の Wi-Fi が 2.4GHz(ch$channel)で立ちました。無線 CarPlay は 5GHz が必要なので、つながらない可能性。" + describe()
        channel in 52..144 ->
            "CarPlay の Wi-Fi が DFS の ch$channel。途中で切れる可能性。" + describe()
        else -> null
    }

    /**
     * CarPlay の映像の口([port])を、ほかのアプリが先に使っていないか。
     * 使われていると、iPhone が Wi-Fi に移った後に「bind failed: EADDRINUSE」で止まる
     * (2026-10-28 実機：AirPlay 受信アプリ com.yuki.duocruise.airplay が 7000 番を使っていた)。
     */
    fun portInUse(port: Int): Boolean = try {
        java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(port)) }
        false
    } catch (_: java.io.IOException) {
        true
    } catch (_: Exception) {
        false
    }

    /**
     * OTTO 本体のテザリング(SSID「OttoAibox-…」)が開いているか。分からなければ null。
     * 本体の操作パネル(com.cartizan.ctzpanel)が「本体の CarPlay 中でなければ」起動のたびに自動で開く
     * (2026-10-28 実機の記録)。iPhone の一覧に別の Wi-Fi が出て、選ぶとパスワードを聞かれる原因。
     */
    fun ottoTetheringOn(): Boolean? = try {
        val wm = context.getSystemService(WifiManager::class.java)
        wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as? Boolean
    } catch (_: Throwable) {
        null
    }

    /** MultiPlay(本体の CarPlay)の様子の手がかり。値の意味は未確認(推測)。 */
    fun multiPlayHints(): String {
        val keys = listOf("persist.ctz.hotspot_status", "ly.bt.connected")
        return keys.joinToString(" ") { "$it=${systemProperty(it).ifEmpty { "?" }}" }
    }

    private fun snapshot(): Snapshot? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var cellular = false
        var wifi = false
        var other = false
        var tethering = false
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular = true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    wifi = true
                    if (looksLikeIphoneHotspot(cm.getLinkProperties(network))) tethering = true
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Unit
                else -> other = true
            }
        }
        val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val defaultTransport = when {
            active == null -> "なし"
            active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "SIM"
            active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            active.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "その他"
        }
        val mhz = if (wifi) staFrequency() else null
        Snapshot(cellular, mhz, tethering, other, defaultTransport)
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun staFrequency(): Int? = try {
        val wm = context.getSystemService(WifiManager::class.java)
        wm.connectionInfo?.frequency?.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    /** iPhone の「インターネット共有」は 172.20.10.0/28 を使う。 */
    private fun looksLikeIphoneHotspot(lp: LinkProperties?): Boolean =
        lp?.linkAddresses?.any { link ->
            val a = link.address
            a is Inet4Address && a.address.let {
                (it[0].toInt() and 0xff) == 172 && (it[1].toInt() and 0xff) == 20 &&
                    (it[2].toInt() and 0xff) == 10 && (it[3].toInt() and 0xff) < 16
            }
        } == true

    private fun isDfs(mhz: Int) = mhz in 5250..5730

    private fun band(mhz: Int) = when {
        mhz < 3000 -> "2.4GHz"
        mhz < 5925 -> "5GHz"
        else -> "6GHz"
    }

    private fun systemProperty(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }
}
