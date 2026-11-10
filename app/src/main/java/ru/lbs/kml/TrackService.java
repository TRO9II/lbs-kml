package ru.lbs.kml;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Запись трека в фоне: служба с постоянным уведомлением держит GPS, когда приложение свёрнуто
 * или экран заблокирован. Точки дописываются в файл — после возврата в приложение трек собирается из него.
 */
public class TrackService extends Service {

    interface Listener {
        void onPoint(double lat, double lon);
    }

    static final String CH = "track_run", ACTION_STOP = "ru.lbs.kml.TRACK_STOP";
    private static final int ID = 2101;
    static volatile Listener listener;
    static volatile boolean running;

    private LocationManager lm;
    private PowerManager.WakeLock wake;
    private double lastLat = Double.NaN, lastLon, dist;
    private int count;
    private double speed; // км/ч
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LocationListener ll = new LocationListener() {
        @Override public void onLocationChanged(Location l) { point(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    static File file(Context c) {
        return new File(c.getFilesDir(), "track_live.txt");
    }

    /** Все точки текущей записи: {lat, lon}. */
    static List<double[]> points(Context c) {
        List<double[]> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) {
                String[] p = s.split(",");
                if (p.length < 2) continue;
                try {
                    out.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void start(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(new Intent(c, TrackService.class));
            else c.startService(new Intent(c, TrackService.class));
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, TrackService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // «Остановить» из уведомления: приложение доделает трек, когда откроется
            LbsCore.prefs(this).edit().putBoolean("trackActive", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!LbsCore.prefs(this).getBoolean("trackActive", false)
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CH, "Запись трека", NotificationManager.IMPORTANCE_LOW));
        // продолжение после перезапуска службы — считаем уже записанное
        List<double[]> pts = points(this);
        count = pts.size();
        dist = 0;
        for (int i = 1; i < pts.size(); i++)
            dist += Geo.inverse(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1])[0];
        if (!pts.isEmpty()) {
            lastLat = pts.get(pts.size() - 1)[0];
            lastLon = pts.get(pts.size() - 1)[1];
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(ID, notif());
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (lm == null) {
            lm = getSystemService(LocationManager.class);
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 3f, ll, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        if (wake == null) {
            // частичная блокировка сна — иначе часть прошивок (MIUI/HyperOS) усыпляет GPS при выключенном экране
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rubezh:track");
                wake.setReferenceCounted(false);
                wake.acquire(12 * 60 * 60 * 1000L);
            }
        }
        return START_STICKY;
    }

    private void point(Location l) {
        if (l.hasSpeed()) {
            boolean was = speed >= 8;
            speed = l.getSpeed() * 3.6;
            if (was != (speed >= 8)) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(ID, notif());
            }
        }
        if (l.getAccuracy() > 40) return;
        double la = l.getLatitude(), lo = l.getLongitude();
        if (!Double.isNaN(lastLat)) {
            double d = Geo.inverse(lastLat, lastLon, la, lo)[0];
            if (d < 8) return;
            dist += d;
        }
        lastLat = la;
        lastLon = lo;
        count++;
        try (FileWriter w = new FileWriter(file(this), true)) {
            w.write(String.format(Locale.US, "%.7f,%.7f,%d\n", la, lo, l.getTime()));
        } catch (Exception ignored) {
        }
        Listener li = listener;
        if (li != null) main.post(() -> li.onPoint(la, lo));
        if (count % 5 == 0 || speed >= 8) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(ID, notif());
        }
    }

    private Notification notif() {
        Intent open = new Intent(this, MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 8, new Intent(this, TrackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_track)
                .setContentTitle("Рубеж · идёт запись трека")
                .setContentText((speed >= 8 ? Math.round(speed) + " км/ч · " : "") + Geo.dist(dist) + " · точек: " + count)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (lm != null) lm.removeUpdates(ll);
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
