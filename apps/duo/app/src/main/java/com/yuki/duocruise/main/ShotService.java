package com.yuki.duocruise.main;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 全画面(CarPlay も YouTube も、重なった札も)を撮って、Trace に残す前面サービス。
 * MediaProjection は、本人が機器の画面で一度「開始」を押して許す必要がある(DuoActivity が許可画面を出す)。
 * 撮るのは切り替えの瞬間だけ(Trace.shot)。画面を映し続けるが、画像は撮るときだけ取り出す。
 */
public final class ShotService extends Service {
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    static final String EXTRA_W = "w", EXTRA_H = "h", EXTRA_DPI = "dpi";
    private static final int NID = 0x0D0C;
    private static final String CH = "duo-shot";

    private static volatile ShotService instance;

    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private ImageReader reader;
    private int w, h, dpi;
    private HandlerThread th;
    private Handler bg;

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

    /** いまの画面を1枚撮る(Trace.shot から。サービスが動いていなければ何もしない)。 */
    static void grab(String reason) {
        ShotService s = instance;
        if (s != null) s.capture(reason);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        startForegroundNotice();
        if (i == null || i.getIntExtra(EXTRA_CODE, 0) == 0) return START_NOT_STICKY;
        w = i.getIntExtra(EXTRA_W, 1480);
        h = i.getIntExtra(EXTRA_H, 480);
        dpi = i.getIntExtra(EXTRA_DPI, 160);
        Intent data = i.getParcelableExtra(EXTRA_DATA);
        int code = i.getIntExtra(EXTRA_CODE, 0);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { teardown(); }
            }, bg);
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vdisplay = projection.createVirtualDisplay("duo-shot", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
            instance = this;
            DuoLog.log("走行の記録: 画面の撮影を始めました(" + w + "×" + h + ")");
        } catch (Throwable t) {
            DuoLog.error("画面の撮影を始められません: " + DuoLog.why(t));
            teardown();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void capture(String reason) {
        Handler b = bg;
        if (b == null) return;
        b.post(() -> {
            Image img = null;
            try {
                if (reader == null) return;
                img = reader.acquireLatestImage();
                if (img == null) return;
                Image.Plane p = img.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                int pixelStride = p.getPixelStride();
                int rowStride = p.getRowStride();
                int rowPad = rowStride - pixelStride * w;
                Bitmap full = Bitmap.createBitmap(w + rowPad / pixelStride, h, Bitmap.Config.ARGB_8888);
                full.copyPixelsFromBuffer(buf);
                Bitmap bmp = (rowPad == 0) ? full : Bitmap.createBitmap(full, 0, 0, w, h);
                File dir = Trace.shotDir();
                if (dir == null) { bmp.recycle(); return; }
                String name = SystemClock.elapsedRealtime() + ".jpg";
                try (FileOutputStream fo = new FileOutputStream(new File(dir, name))) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, fo);
                }
                if (bmp != full) full.recycle();
                bmp.recycle();
                Trace.shotSaved("shots/" + name, reason);
            } catch (Throwable t) {
                DuoLog.error("画面の撮影に失敗: " + DuoLog.why(t));
            } finally {
                if (img != null) img.close();
            }
        });
    }

    private void teardown() {
        if (instance == this) instance = null;
        try { if (vdisplay != null) vdisplay.release(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        vdisplay = null;
        reader = null;
        projection = null;
    }

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
                .setContentText("切り替えのたびに画面を記録しています")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else
            startForeground(NID, n);
    }
}
