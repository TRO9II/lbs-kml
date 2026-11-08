package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Километровая сетка СК-42 (Гаусс-Крюгер, 6° зоны): тонкие линии, каждые 10 км — чуть плотнее,
 * подписи вдоль линий («5 332к», «5 330 000»).
 * Геометрия считается один раз с запасом вокруг экрана и переиспользуется при прокрутке — без подлагиваний.
 */
public class GridOverlay extends Overlay {

    private static final double[] STEPS = {1000, 2000, 5000, 10000, 20000, 50000, 100000};
    private static final int SAMPLES = 16;

    private static final class GLine {
        final double[] lat = new double[SAMPLES + 1], lon = new double[SAMPLES + 1];
        final boolean[] ok = new boolean[SAMPLES + 1];
        boolean major;
        String label;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
    }

    private final float density;
    private final Paint minor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint major = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Path path = new Path();
    private final float[] xs = new float[SAMPLES + 1], ys = new float[SAMPLES + 1];

    // кэш геометрии
    private List<GLine> cache;
    private double cN, cS, cE, cW, cStep;
    private double lastStep = 1000;

    public GridOverlay(float density) {
        this.density = density;
        minor.setStyle(Paint.Style.STROKE);
        minor.setStrokeWidth(0.7f * density);
        minor.setColor(Color.argb(115, 0, 0, 0));
        major.setStyle(Paint.Style.STROKE);
        major.setStrokeWidth(1.1f * density);
        major.setColor(Color.argb(175, 0, 0, 0));
        zoneLine.setStyle(Paint.Style.STROKE);
        zoneLine.setStrokeWidth(1.8f * density);
        zoneLine.setColor(Color.argb(200, 255, 64, 129));
        text.setColor(Color.argb(235, 15, 15, 15));
        text.setTextSize(7.5f * density);
        text.setTextAlign(Paint.Align.CENTER);
        halo.set(text);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(2f * density);
        halo.setColor(Color.argb(190, 255, 255, 255));
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

        // шаг: линии не чаще ~75 dp — без ряби
        double mPerPx = 156543.034 * Math.cos(Math.toRadians((n + s) / 2)) / Math.pow(2, z);
        double minGapM = 75 * density * mPerPx;
        double step = STEPS[STEPS.length - 1];
        for (double st : STEPS) if (st >= minGapM) { step = st; break; }
        lastStep = step;

        if (cache == null || step != cStep || n > cN || s < cS || e > cE || w < cW) rebuild(n, s, e, w, step, mPerPx);

        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        for (GLine l : cache) {
            // только линии, которые проходят через экран
            if (l.maxLat < s || l.minLat > n || l.maxLon < w || l.minLon > e) continue;
            drawLine(c, pj, orient, l);
        }

        // границы зон
        int zFrom = Sk42.zoneOf(w - 0.01), zTo = Sk42.zoneOf(e + 0.01);
        for (int zone = zFrom; zone <= zTo + 1; zone++) {
            double lonB = (zone - 1) * 6;
            if (lonB < w || lonB > e) continue;
            path.reset();
            for (int i = 0; i <= 30; i++) {
                gp.setCoords(s + (n - s) * i / 30.0, lonB);
                pj.toPixels(gp, pt);
                if (i == 0) path.moveTo(pt.x, pt.y); else path.lineTo(pt.x, pt.y);
            }
            c.drawPath(path, zoneLine);
        }
    }

    /** Строит линии сетки для области вокруг экрана (с запасом 60% с каждой стороны). */
    private void rebuild(double n, double s, double e, double w, double step, double mPerPx) {
        double dLat = (n - s) * 0.6, dLon = (e - w) * 0.6;
        cN = n + dLat; cS = s - dLat; cE = e + dLon; cW = w - dLon;
        cStep = step;
        List<GLine> out = new ArrayList<>();
        double majorStep = step < 10000 ? 10000 : step * 5;
        boolean dense = step / mPerPx < 110 * density;

        int zFrom = Sk42.zoneOf(cW - 0.01), zTo = Sk42.zoneOf(cE + 0.01);
        for (int zone = zFrom; zone <= zTo; zone++) {
            double zW = (zone - 1) * 6, zE = zone * 6;
            double lw = Math.max(cW, zW), le = Math.min(cE, zE);
            if (le <= lw) continue;
            double[][] corners = {
                    Sk42.fromWgsInZone(cN, lw, zone), Sk42.fromWgsInZone(cN, le, zone),
                    Sk42.fromWgsInZone(cS, lw, zone), Sk42.fromWgsInZone(cS, le, zone)};
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE, yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
            for (double[] k : corners) {
                xMin = Math.min(xMin, k[0]); xMax = Math.max(xMax, k[0]);
                yMin = Math.min(yMin, k[1]); yMax = Math.max(yMax, k[1]);
            }
            xMin -= step; xMax += step; yMin -= step; yMax += step;
            if ((xMax - xMin) / step > 220 || (yMax - yMin) / step > 220) continue;

            for (double x = Math.ceil(xMin / step) * step; x <= xMax; x += step) {
                boolean isMajor = Math.abs(Math.IEEEremainder(x, majorStep)) < 1;
                boolean lab = isMajor || !dense || Math.round(x / step) % 2 == 0;
                out.add(make(x, yMin, x, yMax, zW, zE, isMajor, lab ? label(x, isMajor) : null));
            }
            for (double y = Math.ceil(yMin / step) * step; y <= yMax; y += step) {
                boolean isMajor = Math.abs(Math.IEEEremainder(y, majorStep)) < 1;
                boolean lab = isMajor || !dense || Math.round(y / step) % 2 == 0;
                out.add(make(xMin, y, xMax, y, zW, zE, isMajor, lab ? label(y, isMajor) : null));
            }
        }
        cache = out;
    }

    private GLine make(double x1, double y1, double x2, double y2, double zW, double zE, boolean isMajor, String label) {
        GLine l = new GLine();
        l.major = isMajor;
        l.label = label;
        for (int i = 0; i <= SAMPLES; i++) {
            double x = x1 + (x2 - x1) * i / SAMPLES, y = y1 + (y2 - y1) * i / SAMPLES;
            double[] ll = Sk42.toWgs(x, y);
            l.lat[i] = ll[0];
            l.lon[i] = ll[1];
            l.ok[i] = ll[1] >= zW - 0.02 && ll[1] <= zE + 0.02;
            if (l.ok[i]) {
                l.minLat = Math.min(l.minLat, ll[0]); l.maxLat = Math.max(l.maxLat, ll[0]);
                l.minLon = Math.min(l.minLon, ll[1]); l.maxLon = Math.max(l.maxLon, ll[1]);
            }
        }
        return l;
    }

    /** Полная подпись для каждых 10 км («5 330 000»), иначе — в километрах («5 332к»). */
    private static String label(double v, boolean isMajor) {
        return isMajor ? Geo.group(Math.round(v)) : Geo.group(Math.round(v / 1000.0)) + "к";
    }

    private void drawLine(Canvas c, Projection pj, float orient, GLine l) {
        path.reset();
        boolean pen = false;
        for (int i = 0; i <= SAMPLES; i++) {
            if (!l.ok[i]) { pen = false; continue; }
            gp.setCoords(l.lat[i], l.lon[i]);
            pj.toPixels(gp, pt);
            xs[i] = pt.x;
            ys[i] = pt.y;
            if (!pen) { path.moveTo(pt.x, pt.y); pen = true; } else path.lineTo(pt.x, pt.y);
        }
        c.drawPath(path, l.major ? major : minor);
        if (l.label == null) return;

        // подписи вдоль линии через каждые ~420 dp
        float gap = 420 * density, acc = 0, next = 210 * density;
        text.setFakeBoldText(l.major);
        halo.setFakeBoldText(l.major);
        for (int i = 1; i <= SAMPLES; i++) {
            if (!l.ok[i] || !l.ok[i - 1]) continue;
            float dx = xs[i] - xs[i - 1], dy = ys[i] - ys[i - 1];
            float segLen = (float) Math.hypot(dx, dy);
            while (segLen > 0 && acc + segLen >= next) {
                float t = (next - acc) / segLen;
                float lx = xs[i - 1] + dx * t, ly = ys[i - 1] + dy * t;
                float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
                float screen = ang + orient;
                while (screen > 180) screen -= 360;
                while (screen <= -180) screen += 360;
                if (screen > 90 || screen <= -90) ang += 180; // не вверх ногами
                c.save();
                c.rotate(ang, lx, ly);
                float ty = ly - 3 * density;
                c.drawText(l.label, lx, ty, halo);
                c.drawText(l.label, lx, ty, text);
                c.restore();
                next += gap;
            }
            acc += segLen;
        }
    }
}
