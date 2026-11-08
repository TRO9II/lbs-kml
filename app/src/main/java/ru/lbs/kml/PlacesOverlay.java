package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Typeface;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Подписи населённых пунктов на русском. Какие подписи показывать, решается один раз
 * для каждого уровня масштаба в «мировых» координатах — поэтому при прокрутке они не мигают.
 */
public class PlacesOverlay extends Overlay {

    /** С какого масштаба показывать: город, город (town), село, хутор. */
    private static final double[] MIN_ZOOM = {6.5, 9, 11, 13};
    private static final float[] SIZE_DP = {14, 12.5f, 11.5f, 10.5f};

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private Places places;
    private float[] widths; // ширина подписи каждого пункта в его размере шрифта

    /** Выбранные подписи по уровню масштаба (целому). */
    private final Map<Integer, int[]> chosen = new HashMap<>();
    private double worldAtLevelCached = -1;

    public PlacesOverlay(float density) {
        this.density = density;
        fill.setColor(Color.WHITE);
        halo.setColor(Color.argb(220, 20, 20, 20));
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3f * density);
        halo.setStrokeJoin(Paint.Join.ROUND);
        dot.setColor(Color.WHITE);
    }

    public void setPlaces(Places p) {
        places = p;
        widths = null;
        chosen.clear();
    }

    /** Ширина мира в пикселях экрана при текущем масштабе (через проекцию: учитывает растягивание тайлов). */
    private static double worldPx(Projection pj, double lat) {
        Point a = pj.toPixels(new GeoPoint(lat, 0.0), null);
        Point b = pj.toPixels(new GeoPoint(lat, 1.0), null);
        return Math.hypot(b.x - a.x, b.y - a.y) * 360.0;
    }

    /** Жадный отбор подписей без наложений в мировых пикселях уровня level. */
    private int[] select(int level, double world) {
        Places p = places;
        if (widths == null) {
            widths = new float[p.n];
            for (int i = 0; i < p.n; i++) {
                int r = p.rank[i];
                measure.setTextSize(SIZE_DP[r] * density);
                measure.setTypeface(r == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                widths[i] = measure.measureText(p.name[i]);
            }
        }
        float cell = 14 * density;
        Set<Long> occ = new HashSet<>();
        int[] tmp = new int[p.n];
        int cnt = 0;
        float pad = 3 * density;
        for (int i = 0; i < p.n; i++) {
            int r = p.rank[i];
            if (level + 0.999 < MIN_ZOOM[r]) break; // отсортировано по рангу
            double x = (p.lon[i] + 180) / 360 * world;
            double sy = Math.sin(Math.toRadians(p.lat[i]));
            double y = (0.5 - Math.log((1 + sy) / (1 - sy)) / (4 * Math.PI)) * world;
            float size = SIZE_DP[r] * density;
            float tw = widths[i];
            double x0 = x - 4 * density - pad, x1 = x + 6 * density + tw + pad;
            double y0 = y - size * 0.8 - pad, y1 = y + size * 0.45 + pad;
            long cx0 = (long) Math.floor(x0 / cell), cx1 = (long) Math.floor(x1 / cell);
            long cy0 = (long) Math.floor(y0 / cell), cy1 = (long) Math.floor(y1 / cell);
            boolean busy = false;
            for (long yy = cy0; yy <= cy1 && !busy; yy++)
                for (long xx = cx0; xx <= cx1; xx++)
                    if (occ.contains((xx << 32) ^ (yy & 0xffffffffL))) { busy = true; break; }
            if (busy) continue;
            for (long yy = cy0; yy <= cy1; yy++)
                for (long xx = cx0; xx <= cx1; xx++) occ.add((xx << 32) ^ (yy & 0xffffffffL));
            tmp[cnt++] = i;
        }
        int[] out = new int[cnt];
        System.arraycopy(tmp, 0, out, 0, cnt);
        return out;
    }

    private android.graphics.RectF exclude;
    private final Point sp = new Point();

    /** Область экрана с кнопками — подписи туда не заходят (переносятся влево или не рисуются). */
    void setExclude(android.graphics.RectF r) {
        exclude = r;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow || places == null) return;
        double z = mv.getZoomLevelDouble();
        if (z < MIN_ZOOM[0]) return;
        int level = (int) Math.floor(z);
        Projection pj = mv.getProjection();
        BoundingBox bb = mv.getBoundingBox();

        // мир на целом уровне (при смене подложки/растягивания — пересчитать)
        double world = worldPx(pj, 0) / Math.pow(2, z - level);
        if (worldAtLevelCached < 0 || Math.abs(world / Math.pow(2, level) - worldAtLevelCached) / worldAtLevelCached > 0.02) {
            chosen.clear();
            worldAtLevelCached = world / Math.pow(2, level);
        }
        int[] sel = chosen.get(level);
        if (sel == null) {
            sel = select(level, world);
            chosen.put(level, sel);
        }

        double n = bb.getLatNorth(), s = bb.getLatSouth(), e = bb.getLonEast(), w = bb.getLonWest();
        float orient = mv.getMapOrientation();
        Places p = places;
        for (int k : sel) {
            double la = p.lat[k], lo = p.lon[k];
            if (la > n || la < s || lo > e || lo < w) continue;
            int r = p.rank[k];
            gp.setCoords(la, lo);
            pj.toPixels(gp, pt);
            float size = SIZE_DP[r] * density;
            Typeface tf = r == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
            fill.setTextSize(size);
            halo.setTextSize(size);
            fill.setTypeface(tf);
            halo.setTypeface(tf);
            float dotR = (r == 0 ? 3.2f : r == 1 ? 2.8f : 2f) * density;
            c.save();
            if (orient != 0f) c.rotate(-orient, pt.x, pt.y); // подпись всегда ровно
            c.drawCircle(pt.x, pt.y, dotR + 1.1f * density, halo);
            c.drawCircle(pt.x, pt.y, dotR, dot);
            float tw = fill.measureText(p.name[k]);
            if (orient != 0f) pj.rotateAndScalePoint(pt.x, pt.y, sp); else sp.set(pt.x, pt.y);
            boolean left = false;
            if (exclude != null) {
                if (exclude.contains(sp.x, sp.y)) { c.restore(); continue; }
                float rx0 = sp.x + dotR + 4 * density;
                boolean hitR = android.graphics.RectF.intersects(exclude, new android.graphics.RectF(rx0, sp.y - size, rx0 + tw, sp.y + size * 0.4f));
                left = hitR || rx0 + tw > mv.getWidth();
            } else {
                left = sp.x + dotR + 4 * density + tw > mv.getWidth();
            }
            float tx = left ? pt.x - dotR - 4 * density - tw : pt.x + dotR + 4 * density, ty = pt.y + size * 0.35f;
            c.drawText(p.name[k], tx, ty, halo);
            c.drawText(p.name[k], tx, ty, fill);
            c.restore();
        }
    }
}
