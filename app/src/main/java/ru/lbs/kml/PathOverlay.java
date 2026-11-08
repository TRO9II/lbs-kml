package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Typeface;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Сохранённые маршруты и редактируемая линия (линейка / маршрут):
 * линия с подсветкой, пронумерованные вершины, длины участков посередине.
 */
public class PathOverlay extends Overlay {

    private final float density;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Path path = new Path();

    private List<Routes.Route> saved = new ArrayList<>();
    private List<double[]> active;
    private int activeColor = 0xFFFFB300;

    public PathOverlay(float density) {
        this.density = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        lineHalo.set(line);
        lineHalo.setColor(Color.argb(150, 0, 0, 0));
        vStroke.setStyle(Paint.Style.STROKE);
        vStroke.setColor(Color.WHITE);
        vStroke.setStrokeWidth(2 * density);
        num.setColor(Color.BLACK);
        num.setTextSize(10.5f * density);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextAlign(Paint.Align.CENTER);
        label.setColor(Color.WHITE);
        label.setTextSize(11.5f * density);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextAlign(Paint.Align.CENTER);
        labelBg.setColor(Color.argb(215, 21, 24, 29));
    }

    void setSaved(List<Routes.Route> routes) {
        saved = routes;
    }

    /** Редактируемая линия (null — нет). */
    void setActive(List<double[]> pts, int color) {
        active = pts;
        activeColor = color;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        for (Routes.Route r : saved) if (r.visible && r.pts.size() > 1) drawPath(c, pj, orient, r.pts, r.color, false);
        if (active != null && !active.isEmpty()) drawPath(c, pj, orient, active, activeColor, true);
    }

    private void drawPath(Canvas c, Projection pj, float orient, List<double[]> pts, int color, boolean editing) {
        int n = pts.size();
        float[] xs = new float[n], ys = new float[n];
        path.reset();
        for (int i = 0; i < n; i++) {
            gp.setCoords(pts.get(i)[0], pts.get(i)[1]);
            pj.toPixels(gp, pt);
            xs[i] = pt.x;
            ys[i] = pt.y;
            if (i == 0) path.moveTo(pt.x, pt.y); else path.lineTo(pt.x, pt.y);
        }
        float w = (editing ? 3.4f : 3f) * density;
        lineHalo.setStrokeWidth(w + 2.6f * density);
        line.setStrokeWidth(w);
        line.setColor(color);
        if (n > 1) {
            c.drawPath(path, lineHalo);
            c.drawPath(path, line);
        }

        // длины участков посередине (только у редактируемой линии и коротких маршрутов)
        if (editing || n <= 30) {
            for (int i = 1; i < n; i++) {
                float mx = (xs[i] + xs[i - 1]) / 2, my = (ys[i] + ys[i - 1]) / 2;
                if (Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) < 70 * density) continue;
                double[] a = pts.get(i - 1), b = pts.get(i);
                String t = Geo.dist(Geo.inverse(a[0], a[1], b[0], b[1])[0]);
                pill(c, orient, t, mx, my);
            }
        }

        // вершины
        float r = (editing ? 9 : 6.5f) * density;
        vFill.setColor(color);
        for (int i = 0; i < n; i++) {
            c.drawCircle(xs[i], ys[i], r, vFill);
            c.drawCircle(xs[i], ys[i], r, vStroke);
            if (editing || i == 0 || i == n - 1) {
                c.save();
                if (orient != 0f) c.rotate(-orient, xs[i], ys[i]);
                num.setTextSize((editing ? 10.5f : 8.5f) * density);
                c.drawText(Integer.toString(i + 1), xs[i], ys[i] + num.getTextSize() * 0.36f, num);
                c.restore();
            }
        }
    }

    private void pill(Canvas c, float orient, String t, float x, float y) {
        c.save();
        if (orient != 0f) c.rotate(-orient, x, y);
        float tw = label.measureText(t), h = label.getTextSize();
        float px = 6 * density, py = 3.5f * density;
        c.drawRoundRect(x - tw / 2 - px, y - h / 2 - py - h * 0.1f, x + tw / 2 + px, y + h / 2 + py - h * 0.1f,
                8 * density, 8 * density, labelBg);
        c.drawText(t, x, y + h * 0.3f, label);
        c.restore();
    }
}
