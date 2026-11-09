package ru.lbs.kml;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Прямая видимость с высоты: из точки, поднятой на h метров над землёй, по рельефу (Terrarium, ~30 м)
 * пускаем лучи во все стороны и отмечаем, где цель высотой target над землёй видна.
 * Учтены кривизна Земли и обычная рефракция (k = 0,13). Лес и постройки — не учтены.
 */
final class ViewshedCalc {

    interface Progress {
        boolean step(int pct); // false — отменить
    }

    private ViewshedCalc() {}

    /** Параллельная подгрузка тайлов рельефа на весь круг (одним потоком — долго). */
    static void prefetch(Context c, double lat, double lon, double radius) {
        int z = 14, n = 1 << z;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        double[] nw = {lat + radius / ky, lon - radius / kx}, se = {lat - radius / ky, lon + radius / kx};
        int x0 = tx(nw[1], n), x1 = tx(se[1], n), y0 = ty(nw[0], n), y1 = ty(se[0], n);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(6);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++) {
                final double la = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 0.5) / n))));
                final double lo = (x + 0.5) / n * 360 - 180;
                ex.execute(() -> Elevation.sync(c, la, lo));
            }
        ex.shutdown();
        try {
            ex.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static int tx(double lon, int n) {
        return (int) Math.floor((lon + 180) / 360 * n);
    }

    private static int ty(double lat, int n) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n);
    }

    static GeoOverlay.Viewshed run(Context c, double lat, double lon, double h, double radius, double target, Progress pr) {
        double ground = Elevation.sync(c, lat, lon);
        if (Double.isNaN(ground)) return null;
        double eye = ground + h;
        int rays = 720;
        double step = Math.max(10, radius / 300);
        int n = (int) Math.ceil(radius / step);
        // vis[ray][i]: 1 — видно, 0 — не видно, -1 — нет данных о высоте
        byte[][] vis = new byte[rays][n];
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        double reff = 6371000.0 / (1 - 0.13);
        int visible = 0, total = 0;
        for (int r = 0; r < rays; r++) {
            double a = Math.toRadians(r * 360.0 / rays), sx = Math.sin(a), sy = Math.cos(a);
            double maxSlope = -1e9;
            for (int i = 0; i < n; i++) {
                double dist = (i + 1) * step;
                double la = lat + sy * dist / ky, lo = lon + sx * dist / kx;
                double g = Elevation.sync(c, la, lo);
                if (Double.isNaN(g)) { vis[r][i] = -1; continue; }
                double drop = dist * dist / (2 * reff);
                double slopeGround = (g - drop - eye) / dist;
                double slopeTarget = (g + target - drop - eye) / dist;
                boolean v = slopeTarget >= maxSlope;
                vis[r][i] = (byte) (v ? 1 : 0);
                if (slopeGround > maxSlope) maxSlope = slopeGround;
                total++;
                if (v) visible++;
            }
            if (pr != null && r % 24 == 0 && !pr.step(r * 100 / rays)) return null;
        }
        // раскладываем лучи в квадратную картинку
        int size = 360;
        int[] px = new int[size * size];
        double half = size / 2.0, mPerPx = radius / half;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = (x + 0.5 - half) * mPerPx, dy = (half - y - 0.5) * mPerPx;
                double dist = Math.hypot(dx, dy);
                if (dist > radius) continue;
                int i = (int) (dist / step) - 1;
                if (i < 0) { px[y * size + x] = 0x6600E676; continue; }
                if (i >= n) i = n - 1;
                double az = Math.toDegrees(Math.atan2(dx, dy));
                if (az < 0) az += 360;
                int r = (int) Math.round(az * rays / 360.0) % rays;
                byte v = vis[r][i];
                px[y * size + x] = v == 1 ? 0x6600E676 : v == 0 ? 0x70101418 : 0;
            }
        }
        GeoOverlay.Viewshed vs = new GeoOverlay.Viewshed();
        vs.lat = lat;
        vs.lon = lon;
        vs.radius = radius;
        vs.height = h;
        vs.bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        vs.north = lat + radius / ky;
        vs.south = lat - radius / ky;
        vs.east = lon + radius / kx;
        vs.west = lon - radius / kx;
        vs.visiblePct = total == 0 ? 0 : visible * 100 / total;
        return vs;
    }
}
