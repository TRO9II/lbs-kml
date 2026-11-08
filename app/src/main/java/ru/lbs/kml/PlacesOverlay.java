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

/** Подписи населённых пунктов на русском поверх карты, без наложений друг на друга. */
public class PlacesOverlay extends Overlay {

    /** С какого масштаба показывать: город, посёлок, село, хутор. */
    private static final double[] MIN_ZOOM = {6.5, 9, 11, 13};
    private static final float[] SIZE_DP = {15, 13, 12, 11};
    private static final int MAX_LABELS = 350;

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private Places places;
    private boolean[] occ = new boolean[0];

    public PlacesOverlay(float density) {
        this.density = density;
        fill.setColor(Color.WHITE);
        halo.setColor(Color.argb(220, 20, 20, 20));
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3.2f * density);
        halo.setStrokeJoin(Paint.Join.ROUND);
        dot.setColor(Color.WHITE);
    }

    public void setPlaces(Places p) {
        places = p;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow || places == null) return;
        double z = mv.getZoomLevelDouble();
        if (z < MIN_ZOOM[0]) return;

        int w = c.getWidth(), h = c.getHeight();
        int cell = (int) (12 * density);
        int cols = w / cell + 2, rows = h / cell + 2;
        if (occ.length < cols * rows) occ = new boolean[cols * rows];
        else java.util.Arrays.fill(occ, 0, cols * rows, false);

        BoundingBox bb = mv.getBoundingBox();
        double n = bb.getLatNorth(), s = bb.getLatSouth(), e = bb.getLonEast(), wst = bb.getLonWest();
        Projection pj = mv.getProjection();
        Places p = places;
        int drawn = 0;
        float pad = 2 * density;

        for (int i = 0; i < p.n && drawn < MAX_LABELS; i++) {
            int r = p.rank[i];
            if (z < MIN_ZOOM[r]) break; // список отсортирован по рангу
            double la = p.lat[i], lo = p.lon[i];
            if (la > n || la < s || lo > e || lo < wst) continue;

            gp.setCoords(la, lo);
            pj.toPixels(gp, pt);

            float size = SIZE_DP[r] * density;
            fill.setTextSize(size);
            halo.setTextSize(size);
            Typeface tf = r == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
            fill.setTypeface(tf);
            halo.setTypeface(tf);

            String label = p.name[i];
            float tw = fill.measureText(label);
            float dotR = (r == 0 ? 3.5f : r == 1 ? 3f : 2.2f) * density;
            float x0 = pt.x - dotR - pad, x1 = pt.x + dotR + 4 * density + tw + pad;
            float y0 = pt.y - size * 0.75f - pad, y1 = pt.y + size * 0.45f + pad;

            int cx0 = (int) Math.floor(x0 / cell) + 1, cx1 = (int) Math.floor(x1 / cell) + 1;
            int cy0 = (int) Math.floor(y0 / cell) + 1, cy1 = (int) Math.floor(y1 / cell) + 1;
            if (cx1 < 0 || cy1 < 0 || cx0 >= cols || cy0 >= rows) continue;
            cx0 = Math.max(0, cx0); cy0 = Math.max(0, cy0);
            cx1 = Math.min(cols - 1, cx1); cy1 = Math.min(rows - 1, cy1);

            boolean busy = false;
            for (int yy = cy0; yy <= cy1 && !busy; yy++)
                for (int xx = cx0; xx <= cx1; xx++)
                    if (occ[yy * cols + xx]) { busy = true; break; }
            if (busy) continue;
            for (int yy = cy0; yy <= cy1; yy++)
                for (int xx = cx0; xx <= cx1; xx++) occ[yy * cols + xx] = true;

            c.drawCircle(pt.x, pt.y, dotR + 1.2f * density, halo);
            c.drawCircle(pt.x, pt.y, dotR, dot);
            float tx = pt.x + dotR + 4 * density, ty = pt.y + size * 0.35f;
            c.drawText(label, tx, ty, halo);
            c.drawText(label, tx, ty, fill);
            drawn++;
        }
    }
}
