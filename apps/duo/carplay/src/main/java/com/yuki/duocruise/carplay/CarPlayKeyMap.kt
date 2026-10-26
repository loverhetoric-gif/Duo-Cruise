package com.yuki.duocruise.carplay

import android.view.KeyEvent

/**
 * コマンダー(ダイヤル)の Android キーコード → CarPlay 操作 の対応表。
 *
 * ここ1か所だけで決める。車での実測(調査A)の結果が出たら、[DEFAULTS] の行を差し替える。
 * 実測前の今の値は「よくある割り当て」の推測。
 * 動かしながら試すときは [set] / [clear] で一時的に上書きできる(アプリを閉じると元に戻る)。
 */
object CarPlayKeyMap {

    /** CarPlay に渡す操作。 */
    enum class Action {
        /** ダイヤルを右に回す(1目盛り)。 */
        ROTATE_RIGHT,
        /** ダイヤルを左に回す(1目盛り)。 */
        ROTATE_LEFT,
        /** ダイヤルを押す(決定)。押す・離すをそのまま渡す。 */
        SELECT,
        /** 戻る。 */
        BACK,
        /** ホーム(CarPlay のホーム画面)。 */
        HOME,
        /** ダイヤルを上・下・左・右に倒す。 */
        TILT_UP,
        TILT_DOWN,
        TILT_LEFT,
        TILT_RIGHT,
        /** 再生系。 */
        MEDIA_PLAY_PAUSE,
        MEDIA_PLAY,
        MEDIA_PAUSE,
        MEDIA_NEXT,
        MEDIA_PREVIOUS,
    }

    /** 倒したときに送る量(HID の X/Y、-127..127)。実機で効き方を見て調整する。 */
    const val TILT_AMOUNT = 127

    /** 回したときに送る量(HID の wheel)。1目盛り = 1。 */
    const val ROTATE_STEP = 1

    /** 実測前の既定の対応表。 */
    private val DEFAULTS: Map<Int, Action> = mapOf(
        // 回す(ロータリー入力の代表的な出方)
        KeyEvent.KEYCODE_NAVIGATE_NEXT to Action.ROTATE_RIGHT,
        KeyEvent.KEYCODE_NAVIGATE_PREVIOUS to Action.ROTATE_LEFT,
        // 押す
        KeyEvent.KEYCODE_DPAD_CENTER to Action.SELECT,
        KeyEvent.KEYCODE_ENTER to Action.SELECT,
        KeyEvent.KEYCODE_NUMPAD_ENTER to Action.SELECT,
        // 戻る
        KeyEvent.KEYCODE_BACK to Action.BACK,
        KeyEvent.KEYCODE_ESCAPE to Action.BACK,
        // ホーム(ユーザー補助で横取りできた時だけ届く)
        KeyEvent.KEYCODE_HOME to Action.HOME,
        KeyEvent.KEYCODE_MOVE_HOME to Action.HOME,
        // 倒す
        KeyEvent.KEYCODE_DPAD_UP to Action.TILT_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to Action.TILT_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to Action.TILT_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to Action.TILT_RIGHT,
        // 再生系
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Action.MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK to Action.MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY to Action.MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE to Action.MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT to Action.MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to Action.MEDIA_PREVIOUS,
    )

    private val overrides = HashMap<Int, Action?>()

    /** キーコードに対応する操作。対応が無ければ null。TAB は Shift 付きなら左回し。 */
    @JvmStatic
    fun actionFor(keyCode: Int, shift: Boolean = false): Action? {
        synchronized(overrides) {
            if (overrides.containsKey(keyCode)) return overrides[keyCode]
        }
        if (keyCode == KeyEvent.KEYCODE_TAB) return if (shift) Action.ROTATE_LEFT else Action.ROTATE_RIGHT
        return DEFAULTS[keyCode]
    }

    /** 一時的な上書き。action に null を渡すと「そのキーは CarPlay に渡さない」。 */
    @JvmStatic
    fun set(keyCode: Int, action: Action?) {
        synchronized(overrides) { overrides[keyCode] = action }
    }

    /** 上書きを全部消して既定に戻す。 */
    @JvmStatic
    fun clear() {
        synchronized(overrides) { overrides.clear() }
    }

    /** 記録用に、今の対応表を文字にする。 */
    @JvmStatic
    fun describe(): String {
        val merged = LinkedHashMap<Int, Action?>(DEFAULTS)
        synchronized(overrides) { merged.putAll(overrides) }
        return merged.entries.joinToString(", ") { (code, action) ->
            KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_") + "=" + (action?.name ?: "なし")
        } + ", TAB=ROTATE_RIGHT(Shift で LEFT)"
    }
}
