package com.yuki.duocruise.carplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.shared.R
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Duo Cruise の左枠に出す自作 CarPlay(無線のみ)。持ち主の画面(DuoActivity、C の持ち場)が
 * [view] を枠に置き、[start] / [stop] を呼び、キーを [onKey] に渡す。
 *
 * - 映像：CarPlay の画面を [WIDTH]×[HEIGHT](左右それぞれの枠の大きさ)で iPhone に頼み、SurfaceView に描く
 * - 接続：Bluetooth(ペアリング済みの iPhone)→ Wi-Fi P2P(5GHz)。有線は使わない
 * - MFi チップ：/dev/i2c-0。0x10/0x11 のうち証明書を持つ方を自動で選ぶ
 * - 記録：状態・取り合いの検出・失敗は、自分で更新アプリへ送る([DuoRecord])
 *   [Listener.onRecord] は画面に出したい時のためのもの。同じ内容を更新アプリへ送り直さないこと
 *
 * MultiPlay とは止めずに「使う時間を分ける」。[stop] で Wi-Fi・Bluetooth・チップを全部手放す。
 * メインスレッドから呼ぶこと。
 */
class CarPlayPane @JvmOverloads constructor(
    context: Context,
    private val listener: Listener?,
    /** Duo Cruise Unlimited Edition: ナビの案内(曲がり角・距離・到着)を iPhone から受け取る。false なら元のまま。 */
    private val routeGuidance: Boolean = false,
    /** 右ハンドルか(false＝左ハンドル)。iPhone はこれで、運転席の側に CarPlay の操作(ドック)を寄せる。 */
    private val rightHandDrive: Boolean = true,
) {

    /** 持ち主への知らせ(どれもメインスレッドで呼ばれる)。 */
    interface Listener {
        /** 状態が変わった([status] と同じ文)。 */
        fun onStatus(status: String)

        /** 記録した内容(画面に出す用。更新アプリへは送信済み)。 */
        fun onRecord(kind: String, text: String)

        /** CarPlay の音の流れが始まった/終わった(audioType: media・default(ナビ)・alert・telephony・speechrecognition)。 */
        fun onAudio(audioType: String, active: Boolean)

        /** ナビの案内が変わった(Duo Cruise Unlimited Edition)。CarPlay が止まったら null。 */
        fun onRoute(route: Route?)

        /** CarPlay の映像が届き始めた/止まった(持ち主が、つながるまでの演出を出し入れする)。 */
        fun onStream(active: Boolean)
    }

    /** CarPlay の映像が届いているか。 */
    var streaming = false
        private set

    /** 枠に出している、利用者向けの今の様子の文(持ち主が、つながるまでの演出に添える)。 */
    fun displayText(): String = forPeople(statusText)

    private fun setStreaming(active: Boolean) {
        label.visibility = if (active) View.GONE else View.VISIBLE
        if (streaming == active) return
        streaming = active
        listener?.onStream(active)
    }

    companion object {
        /** 左右それぞれの枠の大きさ(アプリが使える高さは 416)。 */
        const val WIDTH = 740
        const val HEIGHT = 416
        private const val SCREEN_TYPE_MAIN = 110
        private const val FPS = 60
        /**
         * CarPlay の映像の口。iPhone へは iAP2 と Bonjour で番号を伝えるので、何番でもよい。
         * 既定の 7000 は AirPlay 受信アプリ(com.yuki.duocruise.airplay、YouTube を iPhone から受ける)も使うため、
         * 両方が動いていると CarPlay が「bind failed: EADDRINUSE」で止まっていた(2026-10-28 実機)。ずらして両立させる。
         */
        private const val AIRPLAY_PORT = 7200

        /**
         * CarPlay に使う Wi-Fi = OTTO 本体のホットスポット(名前と機器番号が変わらない、WPA2)。
         * アプリ専用のアクセスポイント(LocalOnlyHotspot)は起動のたびに名前が変わり、iPhone は最初に入った名前で
         * 車を覚えるため、ペアリング直後の1回しかつながらなかった(2026-10-27・28 実機)。
         * パスワードは Duo Slate のキーボードで一度だけ入れてもらい、この端末の中にだけ保存する(記録には出さない)。
         */
        /**
         * 名前の設定が無かった頃の版で使っていた、作者の車の機器のアクセスポイントの名前。
         * パスワードだけが保存されている(＝その頃から使っている)ときに限り、これを名前として引き継ぐ。
         */
        private const val LEGACY_SSID = "OttoAibox-P3 Pro-99F0"
        private const val KEY_SSID = "ssid"
        private const val WIFI_PREFS = "carplay_wifi"
        private const val KEY_PASS = "pass"

        private val running = AtomicInteger(0)

        /** どれかの CarPlayPane が動いているか(MFi 読み取り確認が、取り合いを避けるために見る)。 */
        @JvmStatic
        fun isAnyRunning(): Boolean = running.get() > 0

        /** 無線 CarPlay に要る実行時の権限。持ち主の画面で requestPermissions すること。 */
        @JvmStatic
        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.RECORD_AUDIO,
            )
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        }
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val network = CarPlayNetworkCheck(app)

    private val root = FrameLayout(context)
    private val video = SurfaceView(context)
    private val label = TextView(context)

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var surface: android.view.Surface? = null
    @Volatile private var session: AirPlaySession? = null
    private var statusText = "停止中"
    private var lastStatus: CarPlayStatus? = null
    private var generation = 0
    private var isRunning = false
    /** パスワードを入力中(未保存)。null なら入力中ではない。 */
    private var typing: StringBuilder? = null

    init {
        root.setBackgroundColor(Color.BLACK)
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        label.setTextColor(Color.WHITE)
        label.textSize = 18f
        label.setLineSpacing(0f, 1.5f)
        label.gravity = Gravity.CENTER
        label.setPadding(24, 24, 24, 24)
        label.text = forPeople(statusText)
        root.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                sink?.let {
                    it.setSurface(SCREEN_TYPE_MAIN, holder.surface)
                    // 枠を入れ替えて描き直す時は、次の完全な1枚を頼む
                    hidExecutor.execute { it.requestKeyFrame(SCREEN_TYPE_MAIN) }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface?.let { s -> sink?.clearSurface(SCREEN_TYPE_MAIN, s) }
                surface = null
            }
        })
        video.setOnTouchListener { v, event -> onTouch(v, event) }
    }

    /** 枠に置く View。大きさは持ち主が決める(740×416 を想定。違っても縦横比を保って描く)。 */
    fun view(): View = root

    /** 今の状態(短い日本語)。 */
    fun status(): String = statusText

    /** 接続を始める。権限が足りない時は始めずに記録だけする。 */
    fun start() {
        if (isRunning) return
        val missing = requiredPermissions().filter {
            app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED &&
                it != Manifest.permission.RECORD_AUDIO
        }
        if (missing.isNotEmpty()) {
            setStatus("権限が足りません: " + missing.joinToString { it.substringAfterLast('.') })
            record(DuoRecord.KIND_ERROR, "CarPlay を始められません。権限なし: ${missing.joinToString()}")
            return
        }
        val ssid = wifiSsid()
        if (ssid == null) {
            askWifi(ssid = true)
            return
        }
        val wifiPass = savedWifiPass()
        if (wifiPass == null) {
            askWifi(ssid = false)
            return
        }
        isRunning = true
        running.incrementAndGet()
        val myGeneration = ++generation
        // 回線(SIM・テザリング・Wi-Fi)と無線 CarPlay がぶつかりそうかを先に記録する
        record(DuoRecord.KIND_STATE, "CarPlay 開始前の回線: " + network.describe())
        network.conflictBeforeStart()?.let { record(DuoRecord.KIND_ERROR, "取り合いの可能性(Wi-Fi): $it") }
        if (network.portInUse(AIRPLAY_PORT)) {
            record(DuoRecord.KIND_ERROR, "取り合い: CarPlay の映像の口 $AIRPLAY_PORT 番を、ほかのアプリが使っています")
        }
        if (network.ottoTetheringOn() == false) {
            record(
                DuoRecord.KIND_ERROR,
                "この機器の Wi-Fi アクセスポイント「$ssid」が切れています。CarPlay はこの Wi-Fi を使います。" +
                    "機器の設定でアクセスポイントをオンにし、「自動的にオフ」をオフにしてください",
            )
        }
        record(DuoRecord.KIND_LOG, "キー対応表: " + CarPlayKeyMap.describe())

        try {
            val microphone = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val airPlayConfig = createAirPlayConfig(microphone)
            val renderer = AndroidMediaSink(
                context = app,
                videoWidth = WIDTH,
                videoHeight = HEIGHT,
                videoSizes = mapOf(SCREEN_TYPE_MAIN to (WIDTH to HEIGHT)),
                onScreenStreamActiveChanged = { _, active ->
                    main.post { if (myGeneration == generation) setStreaming(active) }
                },
                onAudioActive = { audioType, active ->
                    main.post {
                        if (myGeneration == generation) {
                            record(DuoRecord.KIND_STATE, "CarPlay の音: $audioType " + if (active) "開始" else "終了")
                            listener?.onAudio(audioType.lowercase(), active)
                        }
                    }
                },
            )
            sink = renderer
            surface?.let { renderer.setSurface(SCREEN_TYPE_MAIN, it) }
            val media = CarPlayMediaEngine(sink = renderer, microphoneEnabled = microphone)
            val pairings = AirPlayPersistence.loadPairings(app) { id, key ->
                AirPlayPersistence.savePairing(app, id, key)
            }
            val next = CarPlayController(
                context = app,
                config = createRuntimeConfig(ssid, wifiPass),
                airPlayConfig = airPlayConfig,
                identity = AirPlayPersistence.loadIdentity(app),
                pairings = pairings,
                listener = sessionListener(myGeneration),
                onRouteGuidance = { frame -> onRouteFrame(frame, myGeneration) },
                media = media,
                reportStatus = { status -> if (myGeneration == generation) onControllerStatus(status) },
                loadPairRecord = { AirPlayPersistence.loadLockdownRecord(app) },
                savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(app, record) },
                clearPairRecord = { AirPlayPersistence.clearLockdownRecord(app) },
            )
            controller = next
            setStatus("開始しています")
            record(DuoRecord.KIND_STATE, "CarPlay 開始 ${WIDTH}x$HEIGHT 無線 MFi=${MfiProbe.DEVICE_PATH} " +
                "MultiPlay の様子: " + network.multiPlayHints())
            next.start()
        } catch (error: Throwable) {
            record(DuoRecord.KIND_ERROR, "CarPlay を始められません: ${error.javaClass.simpleName}: ${error.message}")
            setStatus("始められません: ${error.message}")
            releaseAll()
        }
    }

    /** 止める。Wi-Fi・Bluetooth・チップを手放す(MultiPlay が使えるように)。 */
    fun stop() {
        if (!isRunning) return
        generation++
        clearRoute()
        releaseAll()
        setStatus("停止中")
        record(DuoRecord.KIND_STATE, "CarPlay 停止(Wi-Fi・Bluetooth・チップを手放した)")
    }

    /**
     * Duo Slate のキーボードの文字。OTTO の Wi-Fi のパスワードを入力中のときだけ受ける。
     * 文字は画面に出さず、数だけ「●」で見せる。
     */
    fun typeText(text: String) {
        val buf = typing ?: return
        buf.append(text)
        showTyping()
    }

    /** Duo Slate のキー。入力中のとき、削除で1文字消し、決定で保存してつなぎに行く。 */
    fun typeKey(keyCode: Int) {
        val buf = typing ?: return
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> if (buf.isNotEmpty()) buf.setLength(buf.length - 1)
            KeyEvent.KEYCODE_ENTER -> if (typingSsid) {
                val name = buf.toString().trim()
                if (name.toByteArray(Charsets.UTF_8).size !in 1..32) {
                    typingText = app.getString(R.string.cp_ssid_len, shown(buf))
                    setStatus("Wi-Fi の名前は 1〜32 バイトです。もう一度入れてください")
                    return
                }
                prefs().edit().putString(KEY_SSID, name).apply()
                record(DuoRecord.KIND_STATE, "CarPlay 用の Wi-Fi の名前を保存しました: $name")
                buf.setLength(0)
                typingSsid = false
                if (savedWifiPass() == null) { showTyping(); return } // 続けてパスワード
                typing = null
                start()
                return
            } else {
                if (buf.length !in 8..63) {
                    typingText = app.getString(R.string.cp_wifi_len, dots(buf.length))
                    setStatus("パスワードは 8〜63 文字です。もう一度入れてください")
                    return
                }
                prefs().edit().putString(KEY_PASS, buf.toString()).apply()
                buf.setLength(0)
                typing = null
                record(DuoRecord.KIND_STATE, "CarPlay 用の Wi-Fi のパスワードを保存しました(値は記録しません)")
                start()
                return
            }
            else -> return
        }
        showTyping()
    }

    /** 指示 car-wifi-reset: 保存したパスワードを消し、入力を待つ(パスワードを変えたとき)。 */
    fun resetWifiPass(): String {
        stop()
        prefs().edit().remove(KEY_PASS).apply()
        askWifi(ssid = false)
        return "CarPlay 用の Wi-Fi のパスワードを消しました。Duo Slate のキーボードで入れ直してください"
    }

    /**
     * 設定から: CarPlay 用の Wi-Fi(この機器のアクセスポイント)の名前(ssid=true)かパスワードを入れ直す。
     * CarPlay をいったん止め、Duo Slate のキーボードの入力を待つ。決定すると保存して、CarPlay をつなぎ直す。
     */
    fun editWifi(ssid: Boolean) {
        stop()
        typing = null
        askWifi(ssid)
    }

    /** CarPlay 用の Wi-Fi の名前(SSID)。まだ決まっていなければ null。 */
    fun wifiSsid(): String? {
        prefs().getString(KEY_SSID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        if (prefs().getString(KEY_PASS, null) == null) return null
        prefs().edit().putString(KEY_SSID, LEGACY_SSID).apply() // 名前の設定が無かった頃から使っている
        return LEGACY_SSID
    }

    /** パスワードが保存されているか。 */
    fun hasWifiPass(): Boolean = savedWifiPass() != null

    private fun prefs() = app.getSharedPreferences(WIFI_PREFS, Context.MODE_PRIVATE)

    private fun savedWifiPass(): String? = prefs().getString(KEY_PASS, null)?.takeIf { it.length in 8..63 }

    /** 入力中の欄。true＝名前(SSID)、false＝パスワード。 */
    private var typingSsid = false

    private fun askWifi(ssid: Boolean) {
        if (typing == null) {
            typing = StringBuilder()
            typingSsid = ssid
            record(DuoRecord.KIND_STATE, "CarPlay 用の Wi-Fi の" + (if (ssid) "名前" else "パスワード") + "待ち(Duo Slate のキーボードで入力)")
        }
        showTyping()
    }

    private fun showTyping() {
        val buf = typing ?: StringBuilder()
        if (typingSsid) {
            typingText = app.getString(R.string.cp_ssid_ask, shown(buf))
            setStatus("Wi-Fi の名前の入力待ち(${buf.length} 文字)")
        } else {
            val ssid = wifiSsid() ?: ""
            typingText = app.getString(R.string.cp_wifi_ask, ssid, dots(buf.length))
            setStatus("Wi-Fi「$ssid」のパスワード待ち(${buf.length} 文字)")
        }
    }

    /** 名前は伏せずに見せる(空なら「まだ何も…」)。 */
    private fun shown(buf: CharSequence) = if (buf.isEmpty()) app.getString(R.string.cp_empty) else buf.toString()

    private fun dots(n: Int) = if (n == 0) app.getString(R.string.cp_empty) else "●".repeat(n) + app.getString(R.string.cp_count, n)

    /** パスワード入力中に枠へ出す文(端末の言語)。 */
    private var typingText: String? = null

    /**
     * コマンダーのキーを CarPlay へ渡す。渡した(このキーを使った)時は true。
     * CarPlay がつながっていない時、対応表に無いキーの時は false(持ち主が別の使い道に回せる)。
     */
    fun onKey(event: KeyEvent): Boolean {
        val action = CarPlayKeyMap.actionFor(event.keyCode, event.isShiftPressed) ?: return false
        val current = session ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (action) {
            CarPlayKeyMap.Action.SELECT -> {
                if (event.action == KeyEvent.ACTION_UP || first) {
                    hid { current.sendKnobSelect(down) }
                }
            }
            CarPlayKeyMap.Action.ROTATE_RIGHT -> if (down) hid { current.sendKnob(AirPlayKnobState(wheel = CarPlayKeyMap.ROTATE_STEP)) }
            CarPlayKeyMap.Action.ROTATE_LEFT -> if (down) hid { current.sendKnob(AirPlayKnobState(wheel = -CarPlayKeyMap.ROTATE_STEP)) }
            CarPlayKeyMap.Action.BACK -> if (first) hid { current.sendKnob(AirPlayKnobState(back = true)) }
            CarPlayKeyMap.Action.HOME -> if (first) hid { current.sendKnob(AirPlayKnobState(home = true)) }
            CarPlayKeyMap.Action.TILT_UP -> if (first) hid { current.sendKnob(AirPlayKnobState(y = -CarPlayKeyMap.TILT_AMOUNT)) }
            CarPlayKeyMap.Action.TILT_DOWN -> if (first) hid { current.sendKnob(AirPlayKnobState(y = CarPlayKeyMap.TILT_AMOUNT)) }
            CarPlayKeyMap.Action.TILT_LEFT -> if (first) hid { current.sendKnob(AirPlayKnobState(x = -CarPlayKeyMap.TILT_AMOUNT)) }
            CarPlayKeyMap.Action.TILT_RIGHT -> if (first) hid { current.sendKnob(AirPlayKnobState(x = CarPlayKeyMap.TILT_AMOUNT)) }
            // 番号は xcertplay の再生系 HID の並び(1 再生, 2 一時停止, 3 再生/一時停止, 4 次, 5 前)
            CarPlayKeyMap.Action.MEDIA_PLAY -> if (first) hid { current.sendMedia(1) }
            CarPlayKeyMap.Action.MEDIA_PAUSE -> if (first) hid { current.sendMedia(2) }
            CarPlayKeyMap.Action.MEDIA_PLAY_PAUSE -> if (first) hid { current.sendMedia(3) }
            CarPlayKeyMap.Action.MEDIA_NEXT -> if (first) hid { current.sendMedia(4) }
            CarPlayKeyMap.Action.MEDIA_PREVIOUS -> if (first) hid { current.sendMedia(5) }
        }
        return true
    }

    /** ダイヤルの回転がキーでなく「スクロール」として届く機種のための受け口(任意)。 */
    fun onGenericMotion(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        if (!event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) &&
            !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) return false
        val current = session ?: return false
        val amount = event.getAxisValue(MotionEvent.AXIS_SCROLL).let {
            if (it == 0f) -event.getAxisValue(MotionEvent.AXIS_VSCROLL) else it
        }
        if (amount == 0f) return false
        val step = if (amount > 0) CarPlayKeyMap.ROTATE_STEP else -CarPlayKeyMap.ROTATE_STEP
        hid { current.sendKnob(AirPlayKnobState(wheel = step)) }
        return true
    }

    // 2本指スクロール: CarPlay はダイヤル送り(1目盛りずつ確実に)。指でのなぞりより判定がやさしい
    private var scrollAccY = 0f
    private val scrollNotch = 36f

    fun scroll(dx: Float, dy: Float) {
        val s = session ?: return
        scrollAccY += dy
        while (scrollAccY >= scrollNotch) { hid { s.sendKnob(AirPlayKnobState(wheel = CarPlayKeyMap.ROTATE_STEP)) }; scrollAccY -= scrollNotch }
        while (scrollAccY <= -scrollNotch) { hid { s.sendKnob(AirPlayKnobState(wheel = -CarPlayKeyMap.ROTATE_STEP)) }; scrollAccY += scrollNotch }
    }

    fun scrollEnd() { scrollAccY = 0f }

    private fun onTouch(v: View, event: MotionEvent): Boolean {
        val c = controller ?: return false
        if (session == null) return false
        val contacts = CarPlayTouchMapper.contacts(event, v.width, v.height, WIDTH, HEIGHT)
        if (contacts.isEmpty() && event.actionMasked == MotionEvent.ACTION_DOWN) return false
        c.sendTouch(contacts)
        return true
    }

    private fun hid(block: () -> Unit) {
        try {
            hidExecutor.execute {
                try {
                    block()
                } catch (error: Throwable) {
                    android.util.Log.w("DuoCarPlay", "ダイヤルを送れませんでした", error)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun createRuntimeConfig(ssid: String, wifiPass: String) = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.I2C,
        linuxI2cPath = MfiProbe.DEVICE_PATH,
        identification = Iap2IdentificationConfig(
            name = "Duo Cruise",
            modelIdentifier = "DuoCruise",
            manufacturer = "Duo Cruise",
            serialNumber = "duocruise",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            routeGuidanceEnabled = routeGuidance,
        ),
        label = "Duo Cruise",
        hostName = "DuoCruise",
        transport = CarPlayTransport.WIRELESS,
        // OTTO では Wi-Fi Direct のグループが作れない(実機：家の Wi-Fi あり・なし とも createGroup が generic error)。
        // 機器のアクセスポイント(名前が変わらない。名前は設定で入れる)で iPhone を受ける
        wirelessHotspotMode = WirelessHotspotMode.MANUAL,
        manualHotspotSsid = ssid,
        manualHotspotPassphrase = wifiPass,
        manualHotspotBand = ManualHotspotBand.AUTO,
        manualHotspotSecurity = ManualHotspotSecurity.WPA2,
        wifiHighPerformanceEnabled = true,
    )

    private fun createAirPlayConfig(microphone: Boolean): AirPlayConfig {
        val metrics = app.resources.displayMetrics
        val widthMm = Math.round(WIDTH / metrics.xdpi * 25.4f).coerceAtLeast(1)
        val heightMm = Math.round(HEIGHT / metrics.ydpi * 25.4f).coerceAtLeast(1)
        return AirPlayConfig(
            deviceName = "Duo Cruise",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(
                widthPixels = WIDTH,
                heightPixels = HEIGHT,
                widthPhysicalMm = widthMm,
                heightPhysicalMm = heightMm,
                fps = FPS,
            ),
            // ハンドルの位置(設定。初期値は右ハンドル)
            rightHandDrive = rightHandDrive,
            port = AIRPLAY_PORT,
            hevc = false,
            microphone = microphone,
            manufacturer = "Duo Cruise",
            model = "Duo Cruise",
            oemLabel = "Duo Cruise",
        )
    }

    private fun sessionListener(myGeneration: Int) = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            main.post {
                if (myGeneration != generation) return@post
                this@CarPlayPane.session = session
                setStatus("CarPlay 表示中")
                record(DuoRecord.KIND_STATE, "CarPlay の映像がつながった")
            }
        }

        override fun onSessionEnded(session: AirPlaySession) {
            main.post {
                if (this@CarPlayPane.session === session) this@CarPlayPane.session = null
                if (myGeneration != generation) return@post
                setStreaming(false)
                setStatus("CarPlay が切れました(待機中)")
                record(DuoRecord.KIND_STATE, "CarPlay の映像が切れた")
            }
        }

        override fun onTransportError(message: String) {
            main.post { if (myGeneration == generation) record(DuoRecord.KIND_ERROR, "CarPlay 通信の失敗: $message") }
        }
    }

    private fun onControllerStatus(status: CarPlayStatus) {
        val previous = lastStatus
        lastStatus = status
        val text = describe(status)
        setStatus(text)
        if (previous == status) return
        record(if (status is CarPlayStatus.Failed) DuoRecord.KIND_ERROR else DuoRecord.KIND_STATE, "CarPlay 状態: $text")
        // 取り合いの検出(推測を含む。記録に出して知らせる)
        when (status) {
            is CarPlayStatus.HotspotReady -> network.conflictAfterHotspot(status.band, status.channel)
                ?.let { record(DuoRecord.KIND_ERROR, "取り合いの可能性(Wi-Fi): $it") }
            is CarPlayStatus.Failed -> conflictHint(status.message)?.let {
                record(DuoRecord.KIND_ERROR, "取り合いの疑い: $it")
            }
            CarPlayStatus.WaitingForMfi -> if (previous == CarPlayStatus.DiscoveringMfi) {
                record(DuoRecord.KIND_ERROR,
                    "取り合いの疑い(チップ): MFi チップが答えません。MultiPlay が使用中の可能性。" +
                        "iPhone を MultiPlay から外すと空く見込み")
            }
            else -> Unit
        }
    }

    private fun conflictHint(message: String): String? {
        val m = message.lowercase()
        return when {
            "p2p" in m || "busy" in m || "hotspot" in m || "wifi" in m || "wi-fi" in m ->
                "Wi-Fi(MultiPlay のアクセスポイント、または Wi-Fi 子機の ch と合わない可能性)。" + network.describe()
            "bluetooth" in m || "rfcomm" in m ->
                "Bluetooth(iPhone が MultiPlay とつながっている、またはペアリングが無い可能性)"
            "mfi" in m || "i2c" in m || "authentication" in m ->
                "MFi チップ(MultiPlay が同時に使った可能性)"
            else -> null
        }
    }

    private fun describe(status: CarPlayStatus): String = when (status) {
        CarPlayStatus.DiscoveringMfi -> "MFi チップを探しています"
        CarPlayStatus.WaitingForMfi -> "MFi チップの応答待ち"
        CarPlayStatus.RequestingMfiPermission -> "MFi の許可待ち"
        CarPlayStatus.MfiReady -> "MFi チップ OK"
        CarPlayStatus.StartingHotspot -> "Wi-Fi(アクセスポイント)を立てています"
        is CarPlayStatus.HotspotReady -> "Wi-Fi 準備完了 ${status.band} ch${status.channel}"
        CarPlayStatus.WaitingForPairedIphone -> "ペアリング済みの iPhone を待っています"
        CarPlayStatus.ConnectingBluetooth -> "Bluetooth で iPhone に接続中"
        CarPlayStatus.RunningWireless -> "iPhone と準備中"
        CarPlayStatus.WirelessActive -> "無線 CarPlay 接続中"
        CarPlayStatus.ControlEnded -> "iPhone との接続が終わりました"
        is CarPlayStatus.Failed -> "失敗: ${status.message}"
        else -> status.javaClass.simpleName
    }

    private fun setStatus(text: String) {
        statusText = text
        label.text = forPeople(text)
        listener?.onStatus(text)
    }

    /**
     * 枠に出す文。記録と判定には [statusText](技術的な文)をそのまま使い、画面には利用者向けの言葉だけを出す。
     * 失敗・切断は Duo Cruise が自動でつなぎ直すので、そのことを伝える(英語の内部メッセージは出さない)。
     */
    private fun forPeople(s: String): String = when {
        s.startsWith("権限が足りません") -> app.getString(R.string.cp_perm)
        s.startsWith("パスワード") || s.startsWith("Wi-Fi") -> typingText ?: s
        s.startsWith("失敗") || s.startsWith("始められません") -> app.getString(R.string.cp_failed)
        s.startsWith("CarPlay が切れました") || s.startsWith("iPhone との接続が終わりました") -> app.getString(R.string.cp_ended)
        s.startsWith("ペアリング済みの iPhone") -> app.getString(R.string.cp_waiting)
        s.startsWith("Bluetooth で iPhone") -> app.getString(R.string.cp_connecting)
        s.startsWith("iPhone と準備中") || s.startsWith("無線 CarPlay") || s.startsWith("CarPlay 表示中") -> app.getString(R.string.cp_starting)
        s == "停止中" -> app.getString(R.string.cp_stopped)
        else -> app.getString(R.string.cp_preparing)
    }

    // ───────── ナビの案内(Duo Cruise Unlimited Edition) ─────────

    private val route = RouteGuidanceAccumulator()
    private var lastRouteLog = ""

    /** iAP2 の受信スレッドから。積み重ねて、メインスレッドで持ち主へ渡す。 */
    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame, myGeneration: Int) {
        val now = route.update(frame) ?: return
        main.post {
            if (myGeneration != generation) return@post
            // 記録は、状態か曲がり角が変わったときだけ(距離が縮むたびには残さない)
            val key = "${now.state}/${now.turnIndex}/${now.maneuverType}/${now.onto}"
            if (key != lastRouteLog) {
                lastRouteLog = key
                record(DuoRecord.KIND_STATE, "ナビの案内: $now")
            }
            listener?.onRoute(now)
        }
    }

    private fun clearRoute() {
        route.reset()
        lastRouteLog = ""
        listener?.onRoute(null)
    }

    private fun record(kind: String, text: String) {
        DuoRecord.send(app, kind, text)
        listener?.onRecord(kind, text)
    }

    private fun releaseAll() {
        session = null
        setStreaming(false)
        val c = controller
        val s = sink
        controller = null
        sink = null
        val wasRunning = isRunning
        isRunning = false
        // チップを手放し終えるまで「動いている」扱いにする(MFi 読み取り確認と重ならないように)
        Thread({
            try {
                c?.close()
                c?.awaitClosed(5_000)
            } catch (_: Throwable) {
            }
            try {
                s?.close()
            } catch (_: Throwable) {
            }
            if (wasRunning) running.decrementAndGet()
        }, "duo-carplay-stop").start()
    }
}
