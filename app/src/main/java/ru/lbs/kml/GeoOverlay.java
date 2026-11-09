package ru.lbs.kml;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;

import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Секторы обзора/огня, круги дальности у точек и зона прямой видимости (viewshed).
 * Рисуется под точками: заливка полупрозрачная, края — цветом точки.
 */
public class GeoOverlay extends Overlay {

    /** Что рисовать у одной точки. */
    static final class Item {
        double lat, lon;
        int color;
        double secAz = Double.NaN, secW, secR; // азимут оси, ширина (°), дальность (м)
        double[] rings = new double[0];        // радиусы кругов, м
    }

    /** Разбор поля geo точки: {"sa":45,"sw":60,"sr":1500,"rg":"1,3,5"} (круги — в км). */
    static Item parse(String geo, double lat, double lon, int color) {
        if (geo == null || geo.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(geo);
            Item it = new Item();
            it.lat = lat;
            it.lon = lon;
            it.color = color;
            if (o.has("sa") && o.optDouble("sr", 0) > 0) {
                it.secAz = o.optDouble("sa");
                it.secW = Math.max(1, Math.min(360, o.optDouble("sw", 60)));
                it.secR = o.optDouble("sr");
            }
            it.rings = ringsKm(o.optString("rg", ""));
            return Double.isNaN(it.secAz) && it.rings.length == 0 ? null : it;
        } catch (Exception e) {
            return null;
        }
    }

    /** «1, 3, 5» или «0.5 2» → метры; мусор пропускается. */
    static double[] ringsKm(String s) {
        List<Double> out = new ArrayList<>();
        for (String part : s.replace(';', ' ').split("[\\s,]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                double km = Double.parseDouble(p);
                if (km > 0 && km <= 100 && out.size() < 8) out.add(km * 1000);
            } catch (Exception ignored) {
            }
        }
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        java.util.Arrays.sort(r);
        return r;
    }

    /** Точка на расстоянии d (м) по азимуту az (°) — сфера, на дистанциях до сотни км хватает. */
    static double[] dest(double lat, double lon, double az, double d) {
        double R = 6371008.8, br = Math.toRadians(az), dr = d / R;
        double la1 = Math.toRadians(lat), lo1 = Math.toRadians(lon);
        double la2 = Math.asin(Math.sin(la1) * Math.cos(dr) + Math.cos(la1) * Math.sin(dr) * Math.cos(br));
        double lo2 = lo1 + Math.atan2(Math.sin(br) * Math.sin(dr) * Math.cos(la1), Math.cos(dr) - Math.sin(la1) * Math.sin(la2));
        return new double[]{Math.toDegrees(la2), Math.toDegrees(lo2)};
    }

    // ---------- зона видимости ----------

    static final class Viewshed {
        double lat, lon, radius, height;
        String title = "";
        Bitmap bmp;          // квадрат 2R×2R с центром в точке: зелёный — видно, тёмный — не видно
        double north, south, east, west;
        int visiblePct;
    }

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            halo = new Paint(Paint.ANTI_ALIAS_FLAG), bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private List<Item> mine = new ArrayList<>(), group = new ArrayList<>();
    private Viewshed vs;

    public GeoOverlay(float density) {
        d = density;
        fill.setStyle(Paint.Style.FILL);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f * d);
        edge.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.6f * d);
        ring.setPathEffect(new android.graphics.DashPathEffect(new float[]{9 * d, 5 * d}, 0));
        text.setTextSize(11.5f * d);
        text.setColor(Color.WHITE);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        halo.set(text);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3.2f * d);
        halo.setColor(0xCC000000);
        bmpPaint.setAlpha(255);
    }

    void setMine(List<Item> items) { mine = items; }

    void setGroup(List<Item> items) { group = items; }

    void setViewshed(Viewshed v) { vs = v; }

    // ---------- цель и поправки: линии ОП → цель и НП → цель ----------
    private double[] fOp, fNp, fT;

    void setFire(double[] op, double[] np, double[] t) {
        fOp = op;
        fNp = np;
        fT = t;
    }

    Viewshed viewshed() { return vs; }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        Projection pj = mv.getProjection();
        if (vs != null && vs.bmp != null) {
            gp.setCoords(vs.north, vs.west);
            pj.toPixels(gp, pt);
            float l = pt.x, t = pt.y;
            gp.setCoords(vs.south, vs.east);
            pj.toPixels(gp, pt);
            c.drawBitmap(vs.bmp, null, new RectF(l, t, pt.x, pt.y), bmpPaint);
            gp.setCoords(vs.lat, vs.lon);
            pj.toPixels(gp, pt);
            edge.setColor(0xFF00E676);
            c.drawCircle(pt.x, pt.y, 5 * d, edge);
        }
        for (Item it : group) drawItem(c, pj, it);
        for (Item it : mine) drawItem(c, pj, it);
        if (fT != null) {
            xy(pj, fT[0], fT[1]);
            float tx = pt.x, ty = pt.y;
            if (fNp != null) fireLine(c, pj, fNp, tx, ty, 0xFF64B5F6, "НП", true);
            if (fOp != null) fireLine(c, pj, fOp, tx, ty, 0xFFFF9100, "ОП", false);
        }
    }

    private void fireLine(Canvas c, Projection pj, double[] from, float tx, float ty, int color, String tag, boolean dashed) {
        xy(pj, from[0], from[1]);
        Paint p = dashed ? ring : edge;
        p.setColor(color);
        c.drawLine(pt.x, pt.y, tx, ty, p);
        fill.setColor(color);
        c.drawCircle(pt.x, pt.y, 4.5f * d, fill);
        label(c, pt.x, pt.y - 8 * d, tag);
        if (!dashed) {
            double[] inv = Geo.inverse(from[0], from[1], fT[0], fT[1]);
            label(c, (pt.x + tx) / 2, (pt.y + ty) / 2 - 6 * d, Geo.dist(inv[0]));
        }
    }

    private void drawItem(Canvas c, Projection pj, Item it) {
        double mpp = metersPerPixel(pj, it.lat);
        if (!Double.isNaN(it.secAz)) {
            path.reset();
            xy(pj, it.lat, it.lon);
            path.moveTo(pt.x, pt.y);
            int n = Math.max(6, (int) Math.ceil(it.secW / 3));
            for (int i = 0; i <= n; i++) {
                double a = it.secAz - it.secW / 2 + it.secW * i / n;
                double[] p = dest(it.lat, it.lon, a, it.secR);
                xy(pj, p[0], p[1]);
                path.lineTo(pt.x, pt.y);
            }
            if (it.secW < 360) path.close();
            fill.setColor((it.color & 0x00FFFFFF) | 0x38000000);
            edge.setColor(it.color | 0xFF000000);
            c.drawPath(path, fill);
            c.drawPath(path, edge);
            // ось сектора
            double[] tip = dest(it.lat, it.lon, it.secAz, it.secR);
            xy(pj, it.lat, it.lon);
            float x0 = pt.x, y0 = pt.y;
            xy(pj, tip[0], tip[1]);
            ring.setColor((it.color & 0x00FFFFFF) | 0xB0000000);
            c.drawLine(x0, y0, pt.x, pt.y, ring);
            if (it.secR / mpp > 60 * d) label(c, pt.x, pt.y - 6 * d, Geo.dist(it.secR));
        }
        for (double r : it.rings) {
            if (r / mpp < 6 * d) continue; // слишком мелко на этом масштабе
            path.reset();
            for (int i = 0; i <= 72; i++) {
                double[] p = dest(it.lat, it.lon, i * 5, r);
                xy(pj, p[0], p[1]);
                if (i == 0) path.moveTo(pt.x, pt.y); else path.lineTo(pt.x, pt.y);
            }
            ring.setColor(it.color | 0xFF000000);
            c.drawPath(path, ring);
            if (r / mpp > 28 * d) {
                double[] p = dest(it.lat, it.lon, 0, r);
                xy(pj, p[0], p[1]);
                label(c, pt.x, pt.y + 4 * d, r < 1000 ? Math.round(r) + " м"
                        : (r % 1000 == 0 ? (long) (r / 1000) + "" : String.format(Locale.US, "%.1f", r / 1000).replace('.', ',')) + " км");
            }
        }
    }

    private void label(Canvas c, float x, float y, String s) {
        c.drawText(s, x, y, halo);
        c.drawText(s, x, y, text);
    }

    private void xy(Projection pj, double lat, double lon) {
        gp.setCoords(lat, lon);
        pj.toPixels(gp, pt);
    }

    private double metersPerPixel(Projection pj, double lat) {
        double z = pj.getZoomLevel();
        return 40075016.686 * Math.cos(Math.toRadians(lat)) / (org.osmdroid.util.TileSystem.getTileSize() * Math.pow(2, z));
    }
}
