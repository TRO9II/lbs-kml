package ru.lbs.kml;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.util.ArrayList;
import java.util.List;

/** Отправка своей метки группе: общий для карты и фонового сервиса ограничитель частоты и «след». */
final class ShareLoc {

    private static long lastAt;
    private static double[] lastSent;
    private static final List<double[]> tail = new ArrayList<>(); // {lat, lon, time}

    private ShareLoc() {}

    static boolean enabled(Context c) {
        SharedPreferences p = LbsCore.prefs(c);
        return p.getBoolean("shareLoc", false) && !p.getBoolean("offline", false) && ChatClient.get(c).configured()
                && ChatClient.get(c).active();
    }

    static synchronized void reset() {
        lastAt = 0;
        lastSent = null;
    }

    static synchronized void clearTail() {
        tail.clear();
    }

    /** Новая координата GPS: накопить след и при необходимости отправить метку. */
    static synchronized void onLocation(Context c, Location l) {
        if (l.getAccuracy() <= 60) addTail(l);
        if (!enabled(c)) return;
        SharedPreferences p = LbsCore.prefs(c);
        long every = p.getInt("shareEvery", 60) * 1000L, now = System.currentTimeMillis();
        boolean moved = lastSent != null && Geo.inverse(lastSent[0], lastSent[1], l.getLatitude(), l.getLongitude())[0] > 100;
        if (now - lastAt < every && !(moved && now - lastAt > 10_000)) return;
        if (l.getAccuracy() > 150) return;
        lastAt = now;
        lastSent = new double[]{l.getLatitude(), l.getLongitude()};
        List<double[]> tr = p.getBoolean("shareTrail", false) ? trail() : null;
        ChatClient.get(c).sendLoc(l.getLatitude(), l.getLongitude(), l.getAccuracy(),
                l.hasBearing() && l.getSpeed() > 0.8f ? l.getBearing() : Float.NaN, false, tr);
    }

    private static void addTail(Location l) {
        long t = System.currentTimeMillis();
        if (!tail.isEmpty()) {
            double[] q = tail.get(tail.size() - 1);
            if (Geo.inverse(q[0], q[1], l.getLatitude(), l.getLongitude())[0] < 30) return;
        }
        tail.add(new double[]{l.getLatitude(), l.getLongitude(), t});
        while (!tail.isEmpty() && t - tail.get(0)[2] > 60 * 60_000) tail.remove(0);
        while (tail.size() > 200) tail.remove(0);
    }

    /** След за последний час, не больше 40 точек (равномерно прореженный). */
    private static List<double[]> trail() {
        List<double[]> out = new ArrayList<>();
        int n = tail.size();
        if (n < 2) return out;
        double step = Math.max(1, (n - 1) / 39.0);
        for (double i = 0; i < n; i += step) out.add(tail.get((int) Math.round(Math.min(n - 1, i))));
        if (out.get(out.size() - 1) != tail.get(n - 1)) out.add(tail.get(n - 1));
        return out;
    }
}
