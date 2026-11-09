package ru.lbs.kml;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Typeface;
import android.view.MotionEvent;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/** Свои точки: цветные булавки с названиями (всегда ровно), нажатие — открыть точку. */
public class PointsOverlay extends Overlay {

    interface OnPointTap {
        /** hits — все метки под пальцем, ближняя первой. */
        void onTap(List<UserPoints.Point> hits, MotionEvent e, MapView mv);
    }

    private final float density;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Point pt = new Point();
    private final Point sp = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private List<UserPoints.Point> points = new ArrayList<>();
    private UserPoints.Point extra; // временная метка (результат поиска)
    private OnPointTap listener;
    private boolean group; // общая карта группы: синее кольцо вокруг метки
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    void setGroup(boolean g) {
        group = g;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.2f * density);
        ring.setColor(0xFF42A5F5);
    }

    public PointsOverlay(float density) {
        this.density = density;
        text.setColor(Color.WHITE);
        text.setTextSize(12 * density);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        halo.set(text);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3.2f * density);
        halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setColor(Color.argb(230, 20, 20, 20));
    }

    void setPoints(List<UserPoints.Point> pts) {
        points = pts;
    }

    void setExtra(UserPoints.Point p) {
        extra = p;
    }

    UserPoints.Point getExtra() {
        return extra;
    }

    void setListener(OnPointTap l) {
        listener = l;
    }

    private final Point sp2 = new Point();

    /** Где на экране метки с подписями — чтобы названия сёл их не закрывали. */
    void boxes(MapView mv, List<android.graphics.RectF> out) {
        if (!isEnabled()) return;
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        List<UserPoints.Point> all = new ArrayList<>(points);
        if (extra != null) all.add(extra);
        for (UserPoints.Point p : all) {
            gp.setCoords(p.lat, p.lon);
            pj.toPixels(gp, pt);
            if (orient != 0f) pj.rotateAndScalePoint(pt.x, pt.y, sp2); else sp2.set(pt.x, pt.y);
            Bitmap b = UserPoints.pin(p.color, density, p.icon);
            float hw = b.getWidth() / 2f, hh = b.getHeight() / 2f;
            float tw = p.name == null || p.name.isEmpty() ? 0 : text.measureText(p.name) + 3 * density;
            out.add(new android.graphics.RectF(sp2.x - hw - 2 * density, sp2.y - hh - 2 * density, sp2.x + hw + tw + 2 * density, sp2.y + hh + 2 * density));
        }
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        for (UserPoints.Point p : points) drawPin(c, pj, orient, p, true);
        if (extra != null) drawPin(c, pj, orient, extra, true);
    }

    private void drawPin(Canvas c, Projection pj, float orient, UserPoints.Point p, boolean withName) {
        gp.setCoords(p.lat, p.lon);
        pj.toPixels(gp, pt);
        Bitmap b = UserPoints.pin(p.color, density, p.icon);
        c.save();
        if (orient != 0f) c.rotate(-orient, pt.x, pt.y);
        if (group) c.drawCircle(pt.x, pt.y, b.getWidth() / 2f + 1.5f * density, ring);
        c.drawBitmap(b, pt.x - b.getWidth() / 2f, pt.y - b.getHeight() / 2f, bmp);
        if (withName && p.name != null && !p.name.isEmpty()) {
            float tx = pt.x + b.getWidth() / 2f + 1 * density;
            float ty = pt.y + text.getTextSize() * 0.35f;
            c.drawText(p.name, tx, ty, halo);
            c.drawText(p.name, tx, ty, text);
        }
        c.restore();
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e, MapView mv) {
        if (listener == null) return false;
        List<UserPoints.Point> hits = hits(e, mv);
        if (hits.isEmpty()) return false;
        listener.onTap(hits, e, mv);
        return true;
    }

    /** Все метки под пальцем (метки, лежащие друг на друге, тоже), ближняя первой. */
    List<UserPoints.Point> hits(MotionEvent e, MapView mv) {
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        float w = 30 * density, h = 30 * density, slop = 10 * density;
        final java.util.Map<UserPoints.Point, Float> dist = new java.util.IdentityHashMap<>();
        List<UserPoints.Point> out = new ArrayList<>();
        List<UserPoints.Point> all = new ArrayList<>(points);
        if (extra != null) all.add(extra);
        for (UserPoints.Point p : all) {
            gp.setCoords(p.lat, p.lon);
            pj.toPixels(gp, pt);
            if (orient != 0f) pj.rotateAndScalePoint(pt.x, pt.y, sp); else sp.set(pt.x, pt.y);
            // метка круглая, центр — в самой точке
            float dx = e.getX() - sp.x, dy = e.getY() - sp.y;
            if (Math.abs(dx) <= w / 2 + slop && Math.abs(dy) <= h / 2 + slop) {
                dist.put(p, dx * dx + dy * dy);
                out.add(p);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Float.compare(dist.get(a), dist.get(b)));
        return out;
    }
}
