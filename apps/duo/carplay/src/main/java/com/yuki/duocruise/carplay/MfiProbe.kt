package com.yuki.duocruise.carplay

import android.content.Context
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import java.security.MessageDigest

/**
 * 指示 `com.yuki.duocruise.main:mfi-probe` の中身。
 * /dev/i2c-0 の 0x10 と 0x11 を「読むだけ」で調べ、どちらが本物の MFi 認証チップかを判断する。
 *
 * 安全のための決まり(このクラスの中で強制している)：
 * - 書き込みは「読む場所の番号 1 バイト」だけ。番号の後ろにデータを付けない
 * - 番号は読み取り用のレジスタ(0x00〜0x05、0x30〜0x3A)だけ。0x10・0x20・0x21・0x40 は絶対に指定しない
 * - 0x10・0x11 以外の番地(電源・LED・USB の部品)には一切触らない
 * - 自作 CarPlay が動いている間は読まない(チップの取り合いを避ける)
 * - 全体を 2 回読んで比べる。値が変わったら「取り合いの疑い」(MultiPlay が同時に使った可能性)
 */
object MfiProbe {
    const val COMMAND = "mfi-probe"
    const val DEVICE_PATH = "/dev/i2c-0"

    private val ADDRESSES = listOf(0x10, 0x11)
    private const val REG_DEVICE_VERSION = 0x00
    private const val REG_FIRMWARE = 0x01
    private const val REG_PROTOCOL_MAJOR = 0x02
    private const val REG_PROTOCOL_MINOR = 0x03
    private const val REG_DEVICE_ID = 0x04
    private const val REG_ERROR = 0x05
    private const val REG_CERT_LENGTH = 0x30
    private const val REG_CERT_DATA = 0x31
    private const val CERT_PAGE = 128
    private const val MAX_CERT = 1280 // 0x31..0x3A の 10 ページまで
    private val FORBIDDEN = setOf(0x10, 0x20, 0x21, 0x40)
    private val ALLOWED = (0x00..0x05).toSet() + (0x30..0x3A).toSet()

    private const val RETRIES = 10
    private const val RETRY_MS = 20L

    fun interface Callback {
        fun onDone(text: String)
    }

    /** 別のスレッドで調べて、結果を記録(kind=result)で更新アプリへ送る。done は任意(別スレッドで呼ばれる)。 */
    @JvmStatic
    @JvmOverloads
    fun runAndReport(context: Context, done: Callback? = null) {
        val app = context.applicationContext
        Thread({
            val text = run()
            DuoRecord.send(app, DuoRecord.KIND_RESULT, text)
            done?.onDone(text)
        }, "duo-mfi-probe").start()
    }

    /** 調べて結果の文を返す(待つ。メインスレッドで呼ばないこと)。 */
    @JvmStatic
    fun run(): String {
        val out = StringBuilder("mfi-probe: ")
        if (CarPlayPane.isAnyRunning()) {
            return out.append("自作 CarPlay が動いているので読みませんでした(止めてからもう一度)").toString()
        }
        val transport = try {
            LinuxI2cTransport.open(DEVICE_PATH)
        } catch (error: Exception) {
            return out.append("$DEVICE_PATH を開けません: ${error.message}").toString()
        }
        try {
            val first = ADDRESSES.associateWith { readChip(transport, it) }
            Thread.sleep(200)
            val second = ADDRESSES.associateWith { readChip(transport, it) }
            out.append("\n")
            for (address in ADDRESSES) {
                out.append(first.getValue(address).describe(address)).append("\n")
            }
            val unstable = ADDRESSES.filter { first[it]?.fingerprint() != second[it]?.fingerprint() }
            if (unstable.isNotEmpty()) {
                out.append("注意: 2回読んで値が変わった番地 ")
                    .append(unstable.joinToString { hex(it) })
                    .append("(取り合いの疑い。MultiPlay が同時に使った可能性。iPhone を MultiPlay から外して再実行)\n")
            }
            out.append("判断: ").append(judge(first))
        } finally {
            try {
                transport.close()
            } catch (_: Exception) {
            }
        }
        return out.toString()
    }

    private class ChipRead(
        val responded: Boolean,
        val deviceVersion: Int? = null,
        val firmware: Int? = null,
        val protocolMajor: Int? = null,
        val protocolMinor: Int? = null,
        val deviceId: ByteArray? = null,
        val error: Int? = null,
        val certLength: Int? = null,
        val cert: ByteArray? = null,
        val failure: String? = null,
    ) {
        val certIsDer: Boolean
            get() = cert != null && cert.size >= 4 && (cert[0].toInt() and 0xff) == 0x30 &&
                (cert[1].toInt() and 0xff) in 0x81..0x83

        val certHash: String?
            get() = cert?.let { sha256(it).substring(0, 16) }

        fun fingerprint(): String =
            listOf(responded, deviceVersion, firmware, protocolMajor, protocolMinor, deviceId?.let(::hexBytes),
                certLength, certHash).joinToString("|")

        fun describe(address: Int): String {
            if (!responded) return "${hex(address)}: 応答なし ${failure ?: ""}".trim()
            val parts = mutableListOf(
                "版=${hexOrQ(deviceVersion)}",
                "FW=${hexOrQ(firmware)}",
                "プロトコル=${protocolMajor ?: "?"}.${protocolMinor ?: "?"}",
                "ID=${deviceId?.let(::hexBytes) ?: "?"}",
                "エラー=${hexOrQ(error)}",
                "証明書長=${certLength ?: "?"}",
            )
            if (cert != null) {
                parts += "証明書先頭=${hexBytes(cert.copyOf(minOf(4, cert.size)))}"
                parts += "DER=${if (certIsDer) "はい" else "いいえ"}"
                parts += "SHA256先頭=$certHash"
            }
            if (failure != null) parts += "途中で失敗: $failure"
            return "${hex(address)}: " + parts.joinToString(" ")
        }
    }

    private fun readChip(transport: I2cTransport, address: Int): ChipRead {
        val version = try {
            readRegister(transport, address, REG_DEVICE_VERSION, 1)[0].toInt() and 0xff
        } catch (error: Exception) {
            return ChipRead(responded = false, failure = error.javaClass.simpleName)
        }
        return try {
            val firmware = readRegister(transport, address, REG_FIRMWARE, 1)[0].toInt() and 0xff
            val major = readRegister(transport, address, REG_PROTOCOL_MAJOR, 1)[0].toInt() and 0xff
            val minor = readRegister(transport, address, REG_PROTOCOL_MINOR, 1)[0].toInt() and 0xff
            val id = readRegister(transport, address, REG_DEVICE_ID, 4)
            val error = readRegister(transport, address, REG_ERROR, 1)[0].toInt() and 0xff
            val lengthBytes = readRegister(transport, address, REG_CERT_LENGTH, 2)
            val length = ((lengthBytes[0].toInt() and 0xff) shl 8) or (lengthBytes[1].toInt() and 0xff)
            val cert = if (length in 1..MAX_CERT) readCertificate(transport, address, length) else null
            ChipRead(true, version, firmware, major, minor, id, error, length, cert)
        } catch (error: Exception) {
            ChipRead(true, version, failure = "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun readCertificate(transport: I2cTransport, address: Int, length: Int): ByteArray {
        val cert = ByteArray(length)
        var offset = 0
        var register = REG_CERT_DATA
        while (offset < length) {
            val count = minOf(CERT_PAGE, length - offset)
            readRegister(transport, address, register, count).copyInto(cert, offset)
            offset += count
            register++
        }
        return cert
    }

    /** 番号 1 バイトの書き込み(STOP)→ 読むだけ。眠っているチップのために数回やり直す。 */
    private fun readRegister(transport: I2cTransport, address: Int, register: Int, length: Int): ByteArray {
        check(address in ADDRESSES) { "番地 ${hex(address)} は触らない" }
        check(register !in FORBIDDEN && register in ALLOWED) { "レジスタ ${hex(register)} は指定しない" }
        val select = byteArrayOf(register.toByte())
        check(select.size == 1)
        var last: Exception? = null
        repeat(RETRIES) {
            try {
                transport.transaction(address, select, 0)
                Thread.sleep(10) // 指定から読み取りまで待たないと OTTO のチップは答えない
                val data = transport.transaction(address, ByteArray(0), length)
                if (data.size == length) return data
            } catch (error: Exception) {
                last = error
            }
            Thread.sleep(RETRY_MS)
        }
        throw last ?: IllegalStateException("読めませんでした")
    }

    private fun judge(reads: Map<Int, ChipRead>): String {
        val withCert = reads.filterValues { it.certIsDer }
        val responded = reads.filterValues { it.responded }
        return when {
            withCert.size == 1 -> {
                val address = withCert.keys.first()
                "本物は ${hex(address)}(証明書が正しい形で読めた方)。" +
                    "CarPlay はこの番地を使う(自動で選ぶ。固定したい時は mfiI2cAddress=${hex(address)})"
            }
            withCert.size == 2 -> {
                val hashes = withCert.values.map { it.certHash }.toSet()
                if (hashes.size == 1) {
                    "両方とも同じ証明書。1つのチップが2つの番地に見えている可能性。どちらでも動く見込み(0x11 を優先)"
                } else {
                    "両方とも別の証明書。チップが2個ある可能性。MultiPlay がどちらを使うか要確認"
                }
            }
            responded.isEmpty() -> "どちらも応答なし(チップが眠っている/使用中/番地が違う可能性)"
            else -> "応答はあるが証明書が正しい形で読めない(${responded.keys.joinToString { hex(it) }})。" +
                "読み方か、チップの状態の確認が必要"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        hexBytes(MessageDigest.getInstance("SHA-256").digest(bytes)).replace(" ", "")

    private fun hexBytes(bytes: ByteArray): String =
        bytes.joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun hex(value: Int): String = "0x" + value.toString(16).padStart(2, '0')

    private fun hexOrQ(value: Int?): String = value?.let(::hex) ?: "?"
}
