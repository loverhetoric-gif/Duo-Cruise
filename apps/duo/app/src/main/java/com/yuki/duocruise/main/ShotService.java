package com.yuki.duocruise.main;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;

/**
 * 全画面(CarPlay も YouTube も、重なった札も)を動画で収録して、Trace に残す前面サービス。
 * 本人が機器の画面で一度「開始」を押して許す(DuoActivity が MediaProjection の許可画面を出す)。
 *
 * 1 クリップ = 1 本の MP4。切り替え(モードの帯など)で録り始め、落ち着いて数秒で止める。
 * 途中で別の切り替えが起きたら同じクリップを延長する(分岐路の連続した入れ替わりを 1 本に)。
 * コーナーは「通過して戻る」まで保持。暴走防止に最大 MAX_MS。解像度は半分に落として軽くする。
 */
public final class ShotService extends Service {
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    static final String EXTRA_W = "w", EXTRA_H = "h", EXTRA_DPI = "dpi";
    static final long SETTLE_MS = 5000;        // 切り替えが落ち着いたとみなすまで
    private static final long MAX_MS = 60_000;  // 1 クリップの最大の長さ
    private static final int FPS = 15, BPS = 3_000_000;

    private static final int NID = 0x0D0C;
    private static final String CH = "duo-shot";

    private static volatile ShotService instance;

    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private MediaRecorder recorder;
    private int w, h, dpi, cw, ch;              // 画面の実寸と、クリップの寸法(半分)
    private boolean recording;
    private String clipName, firstReason;
    private long clipStartUp;
    private HandlerThread th;
    private Handler bg;

    private final Runnable stopRun = this::stopClip;
    private final Runnable maxRun = this::stopClip;

    /** 許可の結果を受けて前面サービスを始める(DuoActivity.onActivityResult から)。 */
    static void enable(Context c, int code, Intent data, int w, int h, int dpi) {
        Intent i = new Intent(c, ShotService.class);
        i.putExtra(EXTRA_CODE, code);
        i.putExtra(EXTRA_DATA, data);
        i.putExtra(EXTRA_W, w);
        i.putExtra(EXTRA_H, h);
        i.putExtra(EXTRA_DPI, dpi);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void disable(Context c) {
        try { c.stopService(new Intent(c, ShotService.class)); } catch (Throwable ignored) {}
    }

    /** 切り替えの印。録っていなければ録り始める。autoStopMs>0 ならそのあと自動で止める(0=保持)。 */
    static void clip(String reason, long autoStopMs) {
        ShotService s = instance;
        if (s != null && s.bg != null) s.bg.post(() -> s.mark(reason, autoStopMs));
    }

    /** いま録っているクリップの自動停止を取り消す(コーナーの保持中など)。 */
    static void clipHold() {
        ShotService s = instance;
        if (s != null && s.bg != null) s.bg.post(() -> { if (s.bg != null) s.bg.removeCallbacks(s.stopRun); });
    }

    /** 数秒後に止める(コーナーを通過して戻ったときなど)。 */
    static void clipStop(long delayMs) {
        ShotService s = instance;
        if (s != null && s.bg != null) s.bg.post(() -> {
            if (s.bg == null || !s.recording) return;
            s.bg.removeCallbacks(s.stopRun);
            s.bg.postDelayed(s.stopRun, Math.max(0, delayMs));
        });
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        startForegroundNotice();
        if (i == null || i.getIntExtra(EXTRA_CODE, 0) == 0) return START_NOT_STICKY;
        w = i.getIntExtra(EXTRA_W, 1480);
        h = i.getIntExtra(EXTRA_H, 480);
        dpi = i.getIntExtra(EXTRA_DPI, 160);
        cw = even(w / 2);
        ch = even(h / 2);
        Intent data = i.getParcelableExtra(EXTRA_DATA);
        int code = i.getIntExtra(EXTRA_CODE, 0);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { teardown(); }
            }, bg);
            instance = this;
            DuoLog.log("走行の記録: 画面の録画を始められます(" + cw + "×" + ch + ")");
        } catch (Throwable t) {
            DuoLog.error("画面の録画を始められません: " + DuoLog.why(t));
            teardown();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    // ───────── bg スレッドでだけ ─────────

    private void mark(String reason, long autoStopMs) {
        if (projection == null) return;
        if (!recording) startClip(reason);
        else Trace.clipMark(reason);     // 同じクリップに印だけ足す
        bg.removeCallbacks(stopRun);
        if (autoStopMs > 0) bg.postDelayed(stopRun, autoStopMs);
    }

    private void startClip(String reason) {
        try {
            File dir = Trace.clipDir();
            if (dir == null) return;
            clipName = "clips/" + SystemClock.elapsedRealtime() + ".mp4";
            File out = new File(dir.getParentFile(), clipName);
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoEncodingBitRate(BPS);
            recorder.setVideoFrameRate(FPS);
            recorder.setVideoSize(cw, ch);
            recorder.setOutputFile(out.getAbsolutePath());
            recorder.prepare();
            Surface surface = recorder.getSurface();
            vdisplay = projection.createVirtualDisplay("duo-clip", cw, ch, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, bg);
            recorder.start();
            recording = true;
            firstReason = reason;
            clipStartUp = SystemClock.elapsedRealtime();
            bg.postDelayed(maxRun, MAX_MS);
            Trace.clipMark(reason);
        } catch (Throwable t) {
            DuoLog.error("録画を始められません: " + DuoLog.why(t));
            releaseRecorder();
        }
    }

    private void stopClip() {
        if (!recording) return;
        recording = false;
        bg.removeCallbacks(stopRun);
        bg.removeCallbacks(maxRun);
        long dur = SystemClock.elapsedRealtime() - clipStartUp;
        boolean ok = true;
        try { recorder.stop(); } catch (Throwable t) { ok = false; }
        releaseRecorder();
        if (ok) Trace.clipSaved(clipName, firstReason, dur);
        else { // 短すぎてフレームが無い等。半端なファイルは消す
            try { File f = new File(Trace.clipDir().getParentFile(), clipName); if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete(); } catch (Throwable ignored) {}
        }
    }

    private void releaseRecorder() {
        try { if (vdisplay != null) vdisplay.release(); } catch (Throwable ignored) {}
        try { if (recorder != null) { recorder.reset(); recorder.release(); } } catch (Throwable ignored) {}
        vdisplay = null;
        recorder = null;
    }

    private void teardown() {
        if (instance == this) instance = null;
        if (bg != null) { bg.removeCallbacks(stopRun); bg.removeCallbacks(maxRun); }
        if (recording) { recording = false; try { recorder.stop(); } catch (Throwable ignored) {} }
        releaseRecorder();
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        projection = null;
    }

    private static int even(int v) { return v % 2 == 0 ? v : v - 1; }

    @Override public void onCreate() {
        super.onCreate();
        th = new HandlerThread("duo-shot");
        th.start();
        bg = new Handler(th.getLooper());
    }

    @Override public void onDestroy() {
        teardown();
        if (th != null) th.quitSafely();
        super.onDestroy();
    }

    private void startForegroundNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "走行の記録", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("走行の記録")
                .setContentText("切り替えのときに画面を録画しています")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else
            startForeground(NID, n);
    }
}
