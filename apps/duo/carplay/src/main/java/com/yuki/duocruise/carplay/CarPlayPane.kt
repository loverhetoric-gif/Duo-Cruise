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
class CarPlayPane(context: Context, private val listener: Listener?) {

    /** 持ち主への知らせ(どれもメインスレッドで呼ばれる)。 */
    interface Listener {
        /** 状態が変わった([status] と同じ文)。 */
        fun onStatus(status: String)

        /** 記録した内容(画面に出す用。更新アプリへは送信済み)。 */
        fun onRecord(kind: String, text: String)
    }

    companion object {
        /** 左右それぞれの枠の大きさ(アプリが使える高さは 416)。 */
        const val WIDTH = 740
        const val HEIGHT = 416
        private const val SCREEN_TYPE_MAIN = 110
        private const val FPS = 60

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

    init {
        root.setBackgroundColor(Color.BLACK)
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        label.setTextColor(Color.WHITE)
        label.textSize = 16f
        label.gravity = Gravity.CENTER
        label.setPadding(24, 24, 24, 24)
        label.text = statusText
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
        isRunning = true
        running.incrementAndGet()
        val myGeneration = ++generation
        // 回線(SIM・テザリング・Wi-Fi)と無線 CarPlay がぶつかりそうかを先に記録する
        record(DuoRecord.KIND_STATE, "CarPlay 開始前の回線: " + network.describe())
        network.conflictBeforeStart()?.let { record(DuoRecord.KIND_ERROR, "取り合いの可能性(Wi-Fi): $it") }
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
                    main.post { if (myGeneration == generation) label.visibility = if (active) View.GONE else View.VISIBLE }
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
                config = createRuntimeConfig(),
                airPlayConfig = airPlayConfig,
                identity = AirPlayPersistence.loadIdentity(app),
                pairings = pairings,
                listener = sessionListener(myGeneration),
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
        releaseAll()
        setStatus("停止中")
        record(DuoRecord.KIND_STATE, "CarPlay 停止(Wi-Fi・Bluetooth・チップを手放した)")
    }

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

    private fun createRuntimeConfig() = CarPlayRuntimeConfig(
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
        ),
        label = "Duo Cruise",
        hostName = "DuoCruise",
        transport = CarPlayTransport.WIRELESS,
        wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
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
            // 日本の右ハンドル車(MX-30)
            rightHandDrive = true,
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
                label.visibility = View.VISIBLE
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
        CarPlayStatus.StartingHotspot -> "Wi-Fi(P2P)を立てています"
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
        label.text = text
        listener?.onStatus(text)
    }

    private fun record(kind: String, text: String) {
        DuoRecord.send(app, kind, text)
        listener?.onRecord(kind, text)
    }

    private fun releaseAll() {
        session = null
        label.visibility = View.VISIBLE
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
