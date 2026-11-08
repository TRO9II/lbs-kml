package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.location.Location;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/** Моё местоположение: круг точности, точка и стрелка направления. */
public class MyLocationOverlay extends Overlay {

    private final float density;
    private final Paint acc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pt = new Point(), pn = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Path tri = new Path();
    private Location loc;
    private float heading = Float.NaN;

    public MyLocationOverlay(float density) {
        this.density = density;
        acc.setColor(Color.argb(45, 33, 150, 243));
        accEdge.setStyle(Paint.Style.STROKE);
        accEdge.setStrokeWidth(1 * density);
        accEdge.setColor(Color.argb(140, 33, 150, 243));
        dot.setColor(Color.rgb(33, 150, 243));
        ring.setColor(Color.WHITE);
        arrow.setColor(Color.rgb(33, 150, 243));
    }

    void setLocation(Location l) {
        loc = l;
    }

    Location getLocation() {
        return loc;
    }

    /** Направление (истинный азимут, °) или NaN. */
    private double[] navTo;
    private final Paint navLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Point pnav = new android.graphics.Point();

    /** Пунктир от меня к точке навигации (null — нет). */
    void setNavTarget(double[] latLon) {
        navTo = latLon;
    }

    void setHeading(float h) {
        heading = h;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow || loc == null) return;
        Projection pj = mv.getProjection();
        gp.setCoords(loc.getLatitude(), loc.getLongitude());
        pj.toPixels(gp, pt);
        gp.setCoords(loc.getLatitude() + 100.0 / 111132.0, loc.getLongitude());
        pj.toPixels(gp, pn);
        double pxPerM = Math.hypot(pn.x - pt.x, pn.y - pt.y) / 100.0;
        float r = (float) (loc.getAccuracy() * pxPerM);
        if (r > 6 * density) {
            c.drawCircle(pt.x, pt.y, r, acc);
            c.drawCircle(pt.x, pt.y, r, accEdge);
        }
        if (navTo != null) {
            if (navLine.getPathEffect() == null) {
                navLine.setStyle(Paint.Style.STROKE);
                navLine.setStrokeWidth(3 * density);
                navLine.setColor(0xFF42A5F5);
                navLine.setStrokeCap(Paint.Cap.ROUND);
                navLine.setPathEffect(new android.graphics.DashPathEffect(new float[]{10 * density, 8 * density}, 0));
            }
            gp.setCoords(navTo[0], navTo[1]);
            pj.toPixels(gp, pnav);
            c.drawLine(pt.x, pt.y, pnav.x, pnav.y, navLine);
            c.drawCircle(pnav.x, pnav.y, 7 * density, navLine);
        }
        float h = !Float.isNaN(heading) ? heading : (loc.hasBearing() && loc.getSpeed() > 0.8f ? loc.getBearing() : Float.NaN);
        if (!Float.isNaN(h)) {
            // холст повёрнут вместе с картой: «вверх» = север
            c.save();
            c.rotate(h, pt.x, pt.y);
            tri.reset();
            tri.moveTo(pt.x, pt.y - 19 * density);
            tri.lineTo(pt.x - 7 * density, pt.y - 7 * density);
            tri.lineTo(pt.x + 7 * density, pt.y - 7 * density);
            tri.close();
            c.drawPath(tri, arrow);
            c.restore();
        }
        c.drawCircle(pt.x, pt.y, 9 * density, ring);
        c.drawCircle(pt.x, pt.y, 6.5f * density, dot);
    }
}
