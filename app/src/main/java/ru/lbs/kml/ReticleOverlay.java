package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Typeface;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * Круг-компас в центре карты (как в AlpineQuest): кольца дальности, риски через 10°,
 * стороны света, север — красный треугольник. Поворачивается вместе с картой.
 * Если задана цель — линия от центра к ней с расстоянием.
 */
public class ReticleOverlay extends Overlay {

    private static final double[] RINGS = {50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 20000, 25000, 50000, 100000};
    private static final String[] DIRS = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
    private static final int TARGET_COLOR = Color.rgb(214, 51, 170);

    private final float density;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint north = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tLineHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pc = new Point(), pn = new Point(), pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Path tri = new Path();

    private boolean reticle = true;
    private double tLat, tLon;
    private boolean hasTarget;
    private double ringM = 1000;

    public ReticleOverlay(float density) {
        this.density = density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(0.9f * density);
        ring.setColor(Color.WHITE);
        ringHalo.set(ring);
        ringHalo.setStrokeWidth(2.2f * density);
        ringHalo.setColor(Color.argb(150, 0, 0, 0));
        text.setColor(Color.WHITE);
        text.setTextSize(10.5f * density);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        halo.set(text);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(2.4f * density);
        halo.setColor(Color.argb(210, 0, 0, 0));
        north.setColor(Color.rgb(229, 57, 53));
        tLine.setStyle(Paint.Style.STROKE);
        tLine.setStrokeWidth(2.6f * density);
        tLine.setColor(TARGET_COLOR);
        tLine.setStrokeCap(Paint.Cap.ROUND);
        tLineHalo.set(tLine);
        tLineHalo.setStrokeWidth(4.6f * density);
        tLineHalo.setColor(Color.argb(140, 255, 255, 255));
        tText.setColor(TARGET_COLOR);
        tText.setTextSize(15 * density);
        tText.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setColor(Color.WHITE);
    }

    void setReticle(boolean on) {
        reticle = on;
    }

    void setTarget(double lat, double lon) {
        tLat = lat;
        tLon = lon;
        hasTarget = true;
    }

    void clearTarget() {
        hasTarget = false;
    }

    /** Радиус внутреннего кольца, м. */
    double ringMeters() {
        return ringM;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow || (!reticle && !hasTarget)) return;
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        IGeoPoint center = mv.getMapCenter();
        gp.setCoords(center.getLatitude(), center.getLongitude());
        pj.toPixels(gp, pc);
        float cx = pc.x, cy = pc.y;

        // пикселей на метр: точка в 1 км к северу
        gp.setCoords(center.getLatitude() + 1000.0 / 111132.0, center.getLongitude());
        pj.toPixels(gp, pn);
        double pxPerM = Math.hypot(pn.x - cx, pn.y - cy) / 1000.0;
        if (pxPerM <= 0) return;

        if (hasTarget) drawTarget(c, pj, orient, cx, cy, center);

        if (!reticle) return;
        // внутреннее кольцо 55..130 dp
        ringM = RINGS[RINGS.length - 1];
        for (double r : RINGS) if (r * pxPerM >= 55 * density) { ringM = r; break; }
        float r1 = (float) (ringM * pxPerM), r2 = r1 * 2;
        if (r2 > 2.5f * Math.max(c.getWidth(), c.getHeight())) return;

        c.drawCircle(cx, cy, r1, ringHalo);
        c.drawCircle(cx, cy, r1, ring);
        c.drawCircle(cx, cy, r2, ringHalo);
        c.drawCircle(cx, cy, r2, ring);

        // риски по внутреннему кольцу (холст уже повёрнут вместе с картой: «вверх» = север)
        for (int deg = 0; deg < 360; deg += 10) {
            double a = Math.toRadians(deg);
            float len = (deg % 30 == 0 ? 6.5f : 3.5f) * density;
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            float x0 = cx + sx * r1, y0 = cy + sy * r1, x1 = cx + sx * (r1 - len), y1 = cy + sy * (r1 - len);
            c.drawLine(x0, y0, x1, y1, ringHalo);
            c.drawLine(x0, y0, x1, y1, ring);
        }
        // риски по внешнему кольцу через 90°
        for (int deg = 0; deg < 360; deg += 90) {
            double a = Math.toRadians(deg);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            float x0 = cx + sx * (r2 - 7 * density), y0 = cy + sy * (r2 - 7 * density);
            float x1 = cx + sx * (r2 + 7 * density), y1 = cy + sy * (r2 + 7 * density);
            c.drawLine(x0, y0, x1, y1, ringHalo);
            c.drawLine(x0, y0, x1, y1, ring);
        }

        // север — красный треугольник
        tri.reset();
        tri.moveTo(cx, cy - r1 - 7 * density);
        tri.lineTo(cx - 4.5f * density, cy - r1 + 1.5f * density);
        tri.lineTo(cx + 4.5f * density, cy - r1 + 1.5f * density);
        tri.close();
        c.drawPath(tri, north);

        // стороны света (буквы ровно, как бы ни была повёрнута карта)
        float rl = r1 - 15 * density;
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 1 && r1 < 80 * density) continue; // на маленьком круге — только С/В/Ю/З
            double a = Math.toRadians(i * 45);
            float lx = cx + (float) Math.sin(a) * rl, ly = cy - (float) Math.cos(a) * rl;
            uprightText(c, DIRS[i], lx, ly + 4 * density, orient, lx, ly);
        }

        // подписи колец — снизу справа
        double a = Math.toRadians(150);
        uprightText(c, Geo.dist(ringM), cx + (float) Math.sin(a) * r1, cy - (float) Math.cos(a) * r1 + 14 * density, orient, cx, cy);
        uprightText(c, Geo.dist(ringM * 2), cx + (float) Math.sin(a) * r2, cy - (float) Math.cos(a) * r2 + 14 * density, orient, cx, cy);

        // центр
        c.drawCircle(cx, cy, 3.2f * density, halo);
        c.drawCircle(cx, cy, 2.4f * density, dot);
    }

    private void drawTarget(Canvas c, Projection pj, float orient, float cx, float cy, IGeoPoint center) {
        gp.setCoords(tLat, tLon);
        pj.toPixels(gp, pt);
        float dx = pt.x - cx, dy = pt.y - cy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1) return;
        float ux = dx / len, uy = dy / len;
        float start = 9 * density;
        c.drawLine(cx + ux * start, cy + uy * start, pt.x, pt.y, tLineHalo);
        c.drawLine(cx + ux * start, cy + uy * start, pt.x, pt.y, tLine);

        // стрелка у центра, смотрит на цель
        float ax = cx + ux * 26 * density, ay = cy + uy * 26 * density;
        tri.reset();
        tri.moveTo(ax + ux * 9 * density, ay + uy * 9 * density);
        tri.lineTo(ax - uy * 7 * density, ay + ux * 7 * density);
        tri.lineTo(ax + uy * 7 * density, ay - ux * 7 * density);
        tri.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(TARGET_COLOR);
        c.drawPath(tri, fill);

        // расстояние у центра (ровно)
        double[] inv = Geo.inverse(center.getLatitude(), center.getLongitude(), tLat, tLon);
        String d = Geo.dist(inv[0]);
        float lx = cx + 34 * density, ly = cy - 22 * density;
        c.save();
        if (orient != 0f) c.rotate(-orient, cx, cy);
        Paint h = new Paint(tText);
        h.setStyle(Paint.Style.STROKE);
        h.setStrokeWidth(3.4f * density);
        h.setColor(Color.argb(220, 255, 255, 255));
        c.drawText(d, lx, ly, h);
        c.drawText(d, lx, ly, tText);
        c.restore();
    }

    private void uprightText(Canvas c, String s, float x, float y, float orient, float px, float py) {
        c.save();
        if (orient != 0f) c.rotate(-orient, px, py);
        c.drawText(s, x, y, halo);
        c.drawText(s, x, y, text);
        c.restore();
    }
}
