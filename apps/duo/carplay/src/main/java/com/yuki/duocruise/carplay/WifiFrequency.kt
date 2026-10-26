package com.yuki.duocruise.carplay

/**
 * Wi-Fi の口(例 wlan2)がいま使っている周波数とチャンネルを、Linux から読む(読むだけ)。
 * LocalOnlyHotspot は普通のアプリにチャンネルを教えないため、iPhone に渡すチャンネルが 0 になっていた。
 */
object WifiFrequency {
    init { System.loadLibrary("xcertplay_i2c") }

    @JvmStatic private external fun nativeFrequencyMHz(interfaceName: String): Int

    /** 周波数(MHz)。読めなければ null。 */
    @JvmStatic
    fun frequencyMHz(interfaceName: String): Int? =
        try { nativeFrequencyMHz(interfaceName).takeIf { it > 0 } } catch (_: Throwable) { null }

    /** 周波数からチャンネル番号。分からなければ null。 */
    @JvmStatic
    fun channelOf(frequencyMHz: Int): Int? = when (frequencyMHz) {
        2484 -> 14
        in 2412..2472 -> (frequencyMHz - 2407) / 5
        in 5160..5885 -> (frequencyMHz - 5000) / 5
        else -> null
    }
}
