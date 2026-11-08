package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Typeface;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/** Километровая сетка СК-42 (Гаусс-Крюгер, 6° зоны) с подписями и границами зон. */
public class GridOverlay extends Overlay {

    private static final double[] STEPS = {1000, 2000, 5000, 10000, 20000, 50000, 100000};

    private final float density;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pt = new Point();
    private final Point sp = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Path path = new Path();
    private double lastStep = 1000;

    public GridOverlay(float density) {
        this.density = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.1f * density);
        line.setColor(Color.argb(190, 0, 229, 255));
        zoneLine.setStyle(Paint.Style.STROKE);
        zoneLine.setStrokeWidth(2.2f * density);
        zoneLine.setColor(Color.argb(220, 255, 64, 129));
        text.setColor(Color.WHITE);
        text.setTextSize(12 * density);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        halo.set(text);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3f * density);
        halo.setColor(Color.argb(220, 0, 0, 0));
    }

    /** Текущий шаг сетки, м. */
    public double step() {
        return lastStep;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        double z = mv.getZoomLevelDouble();
        if (z < 7) return;

        BoundingBox bb = mv.getBoundingBox();
        double n = bb.getLatNorth(), s = bb.getLatSouth(), e = bb.getLonEast(), w = bb.getLonWest();
        if (n - s > 12 || e - w > 18) return;

        // шаг: линии не чаще ~70 px
        double minGapM = 70 * density * (156543.034 * Math.cos(Math.toRadians((n + s) / 2)) / Math.pow(2, z));
        double step = STEPS[STEPS.length - 1];
        for (double st : STEPS) if (st >= minGapM) { step = st; break; }
        lastStep = step;

        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        int w0 = c.getWidth(), h0 = c.getHeight();

        // зоны в кадре (по долготе СК-42 ≈ WGS-84 с точностью ~0.002°)
        int zFrom = Sk42.zoneOf(w - 0.01), zTo = Sk42.zoneOf(e + 0.01);
        for (int zone = zFrom; zone <= zTo; zone++) {
            double zW = (zone - 1) * 6, zE = zone * 6;   // границы зоны (долгота)
            double lw = Math.max(w, zW), le = Math.min(e, zE);
            if (le <= lw) continue;
            // прямоугольник кадра в координатах зоны
            double[][] corners = {
                    Sk42.fromWgsInZone(n, lw, zone), Sk42.fromWgsInZone(n, le, zone),
                    Sk42.fromWgsInZone(s, lw, zone), Sk42.fromWgsInZone(s, le, zone)};
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE, yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
            for (double[] k : corners) {
                xMin = Math.min(xMin, k[0]); xMax = Math.max(xMax, k[0]);
                yMin = Math.min(yMin, k[1]); yMax = Math.max(yMax, k[1]);
            }
            xMin -= step; xMax += step; yMin -= step; yMax += step;
            if ((xMax - xMin) / step > 120 || (yMax - yMin) / step > 120) continue;

            int samples = 24;
            // линии X = const (идут с запада на восток)
            for (double x = Math.ceil(xMin / step) * step; x <= xMax; x += step) {
                drawLine(c, pj, x, yMin, x, yMax, samples, zW, zE, line);
                labelAlong(c, pj, x, yMin, x, yMax, samples, zW, zE, orient, w0, h0, km(x), true);
            }
            // линии Y = const (идут с юга на север)
            for (double y = Math.ceil(yMin / step) * step; y <= yMax; y += step) {
                drawLine(c, pj, xMin, y, xMax, y, samples, zW, zE, line);
                labelAlong(c, pj, xMin, y, xMax, y, samples, zW, zE, orient, w0, h0, km(y), false);
            }
        }
        // границы зон
        for (int zone = zFrom; zone <= zTo + 1; zone++) {
            double lonB = (zone - 1) * 6;
            if (lonB < w || lonB > e) continue;
            path.reset();
            boolean first = true;
            for (int i = 0; i <= 30; i++) {
                double la = s + (n - s) * i / 30.0;
                gp.setCoords(la, lonB);
                pj.toPixels(gp, pt);
                if (first) { path.moveTo(pt.x, pt.y); first = false; } else path.lineTo(pt.x, pt.y);
            }
            c.drawPath(path, zoneLine);
        }
    }

    /** Две последние цифры километра, как на топокартах: 5 354 000 → "54". */
    private static String km(double v) {
        long k = Math.round(v / 1000.0);
        return String.format(java.util.Locale.US, "%02d", Math.abs(k % 100));
    }

    private void drawLine(Canvas c, Projection pj, double x1, double y1, double x2, double y2,
                          int samples, double zW, double zE, Paint p) {
        path.reset();
        boolean pen = false;
        for (int i = 0; i <= samples; i++) {
            double x = x1 + (x2 - x1) * i / samples, y = y1 + (y2 - y1) * i / samples;
            double[] ll = Sk42.toWgs(x, y);
            if (ll[1] < zW - 0.02 || ll[1] > zE + 0.02) { pen = false; continue; }
            gp.setCoords(ll[0], ll[1]);
            pj.toPixels(gp, pt);
            if (!pen) { path.moveTo(pt.x, pt.y); pen = true; } else path.lineTo(pt.x, pt.y);
        }
        c.drawPath(path, p);
    }

    /** Подпись линии у края экрана (сверху для вертикальных, слева для горизонтальных). */
    private void labelAlong(Canvas c, Projection pj, double x1, double y1, double x2, double y2, int samples,
                            double zW, double zE, float orient, int w0, int h0, String label, boolean horizontal) {
        float margin = 22 * density;
        float bestScore = Float.MAX_VALUE;
        int bx = 0, by = 0;
        boolean found = false;
        int fine = samples * 2;
        for (int i = 0; i <= fine; i++) {
            double x = x1 + (x2 - x1) * i / fine, y = y1 + (y2 - y1) * i / fine;
            double[] ll = Sk42.toWgs(x, y);
            if (ll[1] < zW || ll[1] > zE) continue;
            gp.setCoords(ll[0], ll[1]);
            pj.toPixels(gp, pt);
            if (orient != 0f) pj.rotateAndScalePoint(pt.x, pt.y, sp); else sp.set(pt.x, pt.y);
            if (sp.x < margin || sp.y < margin || sp.x > w0 - margin * 2.5f || sp.y > h0 - margin) continue;
            if (!horizontal && sp.y > h0 - 80 * density) continue; // снизу — панель координат
            // горизонтальные линии подписываем у левого края, вертикальные — у нижнего
            float score = horizontal ? sp.x : (h0 - sp.y);
            if (score < bestScore) { bestScore = score; bx = pt.x; by = pt.y; found = true; }
        }
        if (!found) return;
        c.save();
        if (orient != 0f) c.rotate(-orient, bx, by);
        float tx = bx + 3 * density, ty = by - 3 * density;
        c.drawText(label, tx, ty, halo);
        c.drawText(label, tx, ty, text);
        c.restore();
    }
}
