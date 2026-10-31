package com.yuki.duocruise.main;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 機器の位置から、走っているか・止まっているか・駐車しているかと、昼か夜かを知らせる。
 * 速さは GPS(と fused)の速さ。止まって STOP_MS で「停止」、駐車の時間(設定)で「駐車」、8km/h を超えたら「走行」。
 * 昼夜は位置と日付から日の出・日の入りを計算する(位置が無ければ大阪)。
 */
final class DriveWatch implements LocationListener {
    static final int MOVING = 0, STOPPED = 1, PARKED = 2;

    interface Listener {
        void driveState(int state);
        void daylight(boolean day);
    }

    private static final long STOP_MS = 3000, SUN_CHECK_MS = 5 * 60_000;
    private static final float STOP_KMH = 1.5f, MOVE_KMH = 8f;

    private final Context c;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private int state = MOVING;
    private long stopSince;
    private Location last;
    private double lat = 34.72, lon = 135.50;
    private Boolean day;
    private final Runnable sun = this::checkSun;

    DriveWatch(Context c, Listener l) { this.c = c.getApplicationContext(); this.l = l; }

    /** 位置の権限があれば見張りを始める。無ければ昼夜だけ(大阪の位置で)。 */
    void start() {
        checkSun();
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DuoLog.log("運転の見張り: 位置の権限が無いので、止まった・駐車の切り替えは使えません");
            return;
        }
        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, "fused"}) {
                if (lm.getAllProviders().contains(p)) lm.requestLocationUpdates(p, 1000, 0f, this, Looper.getMainLooper());
            }
            Location k = lm.getLastKnownLocation("fused");
            if (k != null) { lat = k.getLatitude(); lon = k.getLongitude(); checkSun(); }
            DuoLog.log("運転の見張りを始めました");
        } catch (Throwable t) { DuoLog.error("運転の見張りを始められません: " + DuoLog.why(t)); }
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        if (lm != null) try { lm.removeUpdates(this); } catch (Throwable ignored) {}
    }

    int state() { return state; }

    @Override public void onLocationChanged(Location loc) {
        Trace.gps(loc); // 経路の記録(走行の記録がオンのときだけ)
        lat = loc.getLatitude();
        lon = loc.getLongitude();
        float kmh;
        if (loc.hasSpeed()) kmh = loc.getSpeed() * 3.6f;
        else if (last != null && loc.getTime() > last.getTime()) kmh = loc.distanceTo(last) / ((loc.getTime() - last.getTime()) / 1000f) * 3.6f;
        else { last = loc; return; }
        last = loc;
        long now = android.os.SystemClock.elapsedRealtime();
        if (kmh > MOVE_KMH) { stopSince = 0; main.removeCallbacks(still); set(MOVING); return; }
        if (kmh >= STOP_KMH) return; // ゆっくり動いている間は変えない
        if (stopSince == 0) {
            stopSince = now;
            // 止まっていると位置の知らせが来なくなるので、時間で見直す
            main.postDelayed(still, STOP_MS);
            main.postDelayed(still, Store.parkMs(c));
        }
        checkStill();
    }

    private final Runnable still = this::checkStill;

    private void checkStill() {
        if (stopSince == 0) return;
        long t = android.os.SystemClock.elapsedRealtime() - stopSince;
        if (t >= Store.parkMs(c)) set(PARKED);
        else if (t >= STOP_MS) set(Math.max(state, STOPPED));
    }

    private void set(int s) {
        if (s == state) return;
        state = s;
        l.driveState(s);
    }

    private void checkSun() {
        main.removeCallbacks(sun);
        Calendar cal = Calendar.getInstance();
        double off = TimeZone.getDefault().getOffset(cal.getTimeInMillis()) / 3_600_000.0;
        double[] rs = sunRiseSet(lat, lon, cal.get(Calendar.DAY_OF_YEAR), off);
        double h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
        boolean d = h >= rs[0] && h < rs[1];
        if (day == null || day != d) { day = d; l.daylight(d); }
        main.postDelayed(sun, SUN_CHECK_MS);
    }

    /** 日の出・日の入り(その土地の時刻、時)。NOAA の簡略式。 */
    static double[] sunRiseSet(double lat, double lon, int doy, double tz) {
        double g = 2 * Math.PI / 365 * (doy - 1);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double la = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl))) - Math.tan(la) * Math.tan(decl);
        cosH = Math.max(-1, Math.min(1, cosH));
        double ha = Math.toDegrees(Math.acos(cosH));
        double noon = (720 - 4 * lon - eq) / 60.0 + tz;
        return new double[]{noon - ha * 4 / 60.0, noon + ha * 4 / 60.0};
    }
}
