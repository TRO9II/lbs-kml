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
        void onTap(UserPoints.Point p);
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

    public PointsOverlay(float density) {
        this.density = density;
        text.setColor(Color.WHITE);
        text.setTextSize(13 * density);
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
        UserPoints.Point hit = hitTest(e, mv);
        if (hit == null) return false;
        listener.onTap(hit);
        return true;
    }

    private UserPoints.Point hitTest(MotionEvent e, MapView mv) {
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        float w = 30 * density, h = 30 * density, slop = 10 * density;
        UserPoints.Point best = null;
        float bestD = Float.MAX_VALUE;
        List<UserPoints.Point> all = new ArrayList<>(points);
        if (extra != null) all.add(extra);
        for (UserPoints.Point p : all) {
            gp.setCoords(p.lat, p.lon);
            pj.toPixels(gp, pt);
            if (orient != 0f) pj.rotateAndScalePoint(pt.x, pt.y, sp); else sp.set(pt.x, pt.y);
            // метка круглая, центр — в самой точке
            float dx = e.getX() - sp.x, dy = e.getY() - sp.y;
            if (Math.abs(dx) <= w / 2 + slop && Math.abs(dy) <= h / 2 + slop) {
                float d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = p; }
            }
        }
        return best;
    }
}
