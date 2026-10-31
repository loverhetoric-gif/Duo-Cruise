package com.yuki.duocruise.carplay

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * ナビの案内のいまの様子(Duo Cruise Unlimited Edition だけが使う)。
 *
 * iPhone は案内を 2 種類の知らせで送ってくる(項目の番号は LIVI の iap2-csm と同じ):
 * - 0x5201 案内全体: 状態・次の曲がり角までの距離・いま向かっている曲がり角の番号の並び・目的地・残りの距離
 * - 0x5202 曲がり角 1 つ: 番号・曲がり方(0〜53)・曲がった先の道路の名前
 * どちらも「変わった所だけ」が届くので、[RouteGuidanceAccumulator] で積み重ねて今の姿にする。
 * 数が分からない項目は -1、文字が分からない項目は null。
 */
class Route(
    /** 0=ルートなし 1=案内中 2=到着 3=読み込み中 4=現在地の確認中 5=ルートの再検索中 6=ルートへ向かう途中。-1=まだ知らせがない。 */
    @JvmField val state: Int,
    /** 次の曲がり角までの距離(m)。 */
    @JvmField val distanceToTurnM: Long,
    /** 次の曲がり角までの距離を、iPhone の表示どおりに(例「300 m」)。 */
    @JvmField val distanceText: String?,
    /** 曲がり方(iAP2 の 0〜53)。 */
    @JvmField val maneuverType: Int,
    /** 曲がる先の道路の名前。 */
    @JvmField val onto: String?,
    /** 曲がり方の説明(例「右折」)。 */
    @JvmField val description: String?,
    /** 曲がる角度(左がマイナス、度)。分からなければ null。 */
    @JvmField val exitAngle: Int?,
    /** 0=右側通行 1=左側通行。-1=知らせがない。 */
    @JvmField val drivingSide: Int,
    /** いま向かっている曲がり角の番号(変わったら別の曲がり角)。 */
    @JvmField val turnIndex: Int,
    @JvmField val destination: String?,
    /** 目的地までの残りの距離(m)。 */
    @JvmField val distanceRemainingM: Long,
    /** 案内しているアプリの名前(例「マップ」)。 */
    @JvmField val source: String?,
) {
    /** 案内の途中か(ルートがあって、到着していない)。 */
    val guiding: Boolean get() = state == 1 || state == 5 || state == 6

    val arrived: Boolean get() = state == 2

    /** 曲がる向き([Turn] の定数)。 */
    val turn: Int get() = Turn.of(maneuverType, exitAngle)

    /** 縁の光を灯す側。"L"・"R"、どちらでもなければ null。U ターンは通行する側の反対(日本は右)。 */
    val side: String?
        get() = when (turn) {
            Turn.SLIGHT_LEFT, Turn.LEFT, Turn.SHARP_LEFT -> "L"
            Turn.SLIGHT_RIGHT, Turn.RIGHT, Turn.SHARP_RIGHT -> "R"
            Turn.U_TURN -> if (drivingSide == 0) "L" else "R"
            Turn.ARRIVE -> when (maneuverType) { 24 -> "L"; 25 -> "R"; else -> null }
            else -> null
        }

    override fun toString(): String =
        "状態=$state 曲がり角#$turnIndex 種類=$maneuverType(${Turn.name(turn)}) " +
            "あと${if (distanceToTurnM >= 0) "${distanceToTurnM}m" else "?"} 先=${onto ?: "-"} " +
            "目的地=${destination ?: "-"} 残り=${if (distanceRemainingM >= 0) "${distanceRemainingM}m" else "?"} アプリ=${source ?: "-"}"
}

/** 曲がる向き。iAP2 の曲がり方(0〜53)を、絵と光に要る粒度にまとめたもの。 */
object Turn {
    const val NONE = 0
    const val STRAIGHT = 1
    const val SLIGHT_LEFT = 2
    const val LEFT = 3
    const val SHARP_LEFT = 4
    const val SLIGHT_RIGHT = 5
    const val RIGHT = 6
    const val SHARP_RIGHT = 7
    const val U_TURN = 8
    const val ROUNDABOUT = 9
    const val ARRIVE = 10
    const val FERRY = 11

    @JvmStatic
    fun of(type: Int, exitAngle: Int?): Int = when (type) {
        0, 3, 5 -> STRAIGHT
        1, 20 -> LEFT
        2, 21 -> RIGHT
        47 -> SHARP_LEFT
        48 -> SHARP_RIGHT
        13, 22, 49, 52 -> SLIGHT_LEFT       // 左寄り・左の出口・やや左・左の高速へ
        14, 23, 50, 53 -> SLIGHT_RIGHT
        4, 18, 19, 26 -> U_TURN
        6, 7, in 28..46 -> ROUNDABOUT
        10, 12, 24, 25, 27 -> ARRIVE
        15, 16, 17 -> FERRY
        8, 9, 51 -> byAngle(exitAngle)      // 出口・入口・高速の乗り換え: 向きは角度で決める
        else -> NONE                        // 11(ルートの始まり)など
    }

    private fun byAngle(a: Int?): Int = when {
        a == null -> STRAIGHT
        a <= -120 -> SHARP_LEFT
        a <= -60 -> LEFT
        a <= -20 -> SLIGHT_LEFT
        a >= 120 -> SHARP_RIGHT
        a >= 60 -> RIGHT
        a >= 20 -> SLIGHT_RIGHT
        else -> STRAIGHT
    }

    @JvmStatic
    fun name(t: Int): String = when (t) {
        STRAIGHT -> "直進"; SLIGHT_LEFT -> "やや左"; LEFT -> "左"; SHARP_LEFT -> "大きく左"
        SLIGHT_RIGHT -> "やや右"; RIGHT -> "右"; SHARP_RIGHT -> "大きく右"; U_TURN -> "Uターン"
        ROUNDABOUT -> "ロータリー"; ARRIVE -> "到着"; FERRY -> "フェリー"; else -> "-"
    }
}

/** 届いた知らせを積み重ねて、いまの案内の姿([Route])にする。 */
class RouteGuidanceAccumulator {
    private class Maneuver(var type: Int = -1, var onto: String? = null, var description: String? = null,
                           var exitAngle: Int? = null, var drivingSide: Int = -1)

    private var state = -1
    private var distanceToTurn = -1L
    private var distanceText: String? = null
    private var order: List<Int> = emptyList()
    private var destination: String? = null
    private var remaining = -1L
    private var source: String? = null
    private val maneuvers = LinkedHashMap<Int, Maneuver>()

    /** 1 つの知らせを取り込み、今の姿を返す。読めない知らせなら null。 */
    @Synchronized
    fun update(frame: Iap2Frame): Route? {
        val body = try { Iap2Messages.reader(frame) } catch (_: RuntimeException) { return null }
        when (frame.messageId) {
            0x5201 -> {
                read { body.optionalU8(1) }?.let { state = it }
                read { body.optionalU32(10) }?.let { distanceToTurn = it }
                val shown = read { body.optionalString(11) }
                val unit = read { body.optionalU8(12) }
                if (shown != null) distanceText = shown + unitText(unit)
                else if (body.has(10)) distanceText = null
                read { body.optionalBytes(13) }?.let { bytes ->
                    order = (0 until bytes.size / 2).map {
                        ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff)
                    }
                }
                read { body.optionalString(4) }?.let { destination = it }
                read { body.optionalU32(7) }?.let { remaining = it }
                read { body.optionalString(19) }?.let { source = it }
                if (state == 0) { order = emptyList(); maneuvers.clear() } // ルートが消えたら曲がり角も忘れる
            }
            0x5202 -> {
                val index = read { body.optionalU16(1) } ?: return null
                val m = maneuvers.getOrPut(index) { Maneuver() }
                read { body.optionalString(2) }?.let { m.description = it }
                read { body.optionalU8(3) }?.let { m.type = it }
                read { body.optionalString(4) }?.let { m.onto = it }
                read { body.optionalU8(8) }?.let { m.drivingSide = it }
                read { body.optionalI16(11) }?.let { m.exitAngle = it }
                while (maneuvers.size > MAX_MANEUVERS) {
                    val stale = maneuvers.keys.firstOrNull { it !in order } ?: break
                    maneuvers.remove(stale)
                }
            }
            else -> return null
        }
        return snapshot()
    }

    @Synchronized
    fun reset() {
        state = -1; distanceToTurn = -1; distanceText = null; order = emptyList()
        destination = null; remaining = -1; source = null; maneuvers.clear()
    }

    private fun snapshot(): Route {
        // 並びの先頭が「ルートの始まり」(11)のときは、その次が本当の曲がり角
        val index = order.firstOrNull { maneuvers[it]?.type != START_ROUTE } ?: order.firstOrNull() ?: -1
        val m = maneuvers[index]
        return Route(
            state = state,
            distanceToTurnM = distanceToTurn,
            distanceText = distanceText ?: distanceToTurn.takeIf { it >= 0 }?.let(::meters),
            maneuverType = m?.type ?: -1,
            onto = m?.onto,
            description = m?.description,
            exitAngle = m?.exitAngle,
            drivingSide = m?.drivingSide ?: -1,
            turnIndex = index,
            destination = destination,
            distanceRemainingM = remaining,
            source = source,
        )
    }

    private inline fun <T> read(block: () -> T?): T? = try { block() } catch (_: RuntimeException) { null }

    private companion object {
        const val START_ROUTE = 11
        const val MAX_MANEUVERS = 64

        /** 0=km 1=マイル 2=m 3=ヤード 4=フィート。 */
        fun unitText(u: Int?): String = when (u) {
            0 -> " km"; 1 -> " mi"; 2 -> " m"; 3 -> " yd"; 4 -> " ft"; else -> ""
        }

        fun meters(m: Long): String = when {
            m >= 10_000 -> "${m / 1000} km"
            m >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1f km", m / 1000.0)
            m >= 100 -> "${(m + 25) / 50 * 50} m"
            else -> "${(m + 5) / 10 * 10} m"
        }
    }
}
