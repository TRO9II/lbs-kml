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
    private boolean activeArea;
    private final Paint areaFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rubber = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rubberHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liveText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liveSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pc = new Point();

    public PathOverlay(float density) {
        this.density = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        lineHalo.set(line);
        lineHalo.setColor(Color.argb(150, 0, 0, 0));
        vStroke.setStyle(Paint.Style.STROKE);
        vStroke.setColor(Color.WHITE);
        vStroke.setStrokeWidth(1.5f * density);
        num.setColor(Color.BLACK);
        num.setTextSize(10.5f * density);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextAlign(Paint.Align.CENTER);
        label.setColor(Color.WHITE);
        label.setTextSize(11.5f * density);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextAlign(Paint.Align.CENTER);
        labelBg.setColor(Color.argb(215, 21, 24, 29));
        rubber.setStyle(Paint.Style.STROKE);
        rubber.setStrokeWidth(2.4f * density);
        rubber.setStrokeCap(Paint.Cap.ROUND);
        rubber.setPathEffect(new android.graphics.DashPathEffect(new float[]{9 * density, 7 * density}, 0));
        rubberHalo.set(rubber);
        rubberHalo.setPathEffect(null);
        rubberHalo.setStrokeWidth(4.2f * density);
        rubberHalo.setColor(Color.argb(110, 0, 0, 0));
        liveText.setColor(Color.WHITE);
        liveText.setTextSize(15 * density);
        liveText.setTypeface(Typeface.DEFAULT_BOLD);
        liveSub.setColor(Color.argb(220, 200, 205, 212));
        liveSub.setTextSize(11 * density);
    }

    void setSaved(List<Routes.Route> routes) {
        saved = routes;
    }

    /** Редактируемая линия (null — нет). */
    void setActive(List<double[]> pts, int color) {
        setActive(pts, color, false);
    }

    void setActive(List<double[]> pts, int color, boolean area) {
        active = pts;
        activeColor = color;
        activeArea = area;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        for (Routes.Route r : saved) if (r.visible && r.pts.size() > 1) drawPath(c, pj, orient, r.pts, r.color, false, r.area);
        if (active != null && !active.isEmpty()) {
            drawPath(c, pj, orient, active, activeColor, true, activeArea);
            drawRubber(c, mv, pj, orient);
        }
    }

    /** «Резинка»: пунктир от последней точки к прицелу (центру карты) и расстояние у прицела. */
    private void drawRubber(Canvas c, MapView mv, Projection pj, float orient) {
        double[] last = active.get(active.size() - 1);
        org.osmdroid.api.IGeoPoint ctr = mv.getMapCenter();
        gp.setCoords(last[0], last[1]);
        pj.toPixels(gp, pt);
        gp.setCoords(ctr.getLatitude(), ctr.getLongitude());
        pj.toPixels(gp, pc);
        if (Math.hypot(pc.x - pt.x, pc.y - pt.y) < 4 * density) return;
        rubber.setColor(activeColor);
        c.drawLine(pt.x, pt.y, pc.x, pc.y, rubberHalo);
        c.drawLine(pt.x, pt.y, pc.x, pc.y, rubber);

        double[] inv = Geo.inverse(last[0], last[1], ctr.getLatitude(), ctr.getLongitude());
        double grid = Geo.gridBearing(last[0], last[1], ctr.getLatitude(), ctr.getLongitude());
        String main = Geo.dist(inv[0]);
        String sub = String.format(java.util.Locale.US, "%.1f° · %s", inv[1], Geo.mils(grid));
        double total = Routes.length(active) + inv[0];
        if (active.size() > 1) sub += " · всего " + Geo.dist(total);

        // плашка справа-сверху от прицела, всегда ровно
        c.save();
        if (orient != 0f) c.rotate(-orient, pc.x, pc.y);
        float x = pc.x + 22 * density, y = pc.y - 20 * density;
        float w = Math.max(liveText.measureText(main), liveSub.measureText(sub));
        float pad = 8 * density;
        c.drawRoundRect(x - pad, y - liveText.getTextSize() - pad * 0.6f, x + w + pad,
                y + liveSub.getTextSize() + pad, 10 * density, 10 * density, labelBg);
        c.drawText(main, x, y, liveText);
        c.drawText(sub, x, y + liveSub.getTextSize() + 3 * density, liveSub);
        c.restore();
    }

    private void drawPath(Canvas c, Projection pj, float orient, List<double[]> pts, int color, boolean editing, boolean area) {
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
        // линия: тонкая тёмная обводка + цвет; у сохранённых — стрелки направления
        float w = (editing ? 3.2f : 2.8f) * density;
        lineHalo.setStrokeWidth(w + 2.2f * density);
        line.setStrokeWidth(w);
        line.setColor(color);
        boolean light = Color.red(color) * 0.3 + Color.green(color) * 0.59 + Color.blue(color) * 0.11 > 150;
        lineHalo.setColor(light ? Color.argb(170, 0, 0, 0) : Color.argb(200, 255, 255, 255));
        if (area && n > 2) {
            path.close();
            areaFill.setColor((color & 0x00FFFFFF) | 0x2E000000);
            c.drawPath(path, areaFill);
        }
        if (n > 1) {
            c.drawPath(path, lineHalo);
            c.drawPath(path, line);
            if (!area) arrows(c, xs, ys, light ? Color.argb(230, 20, 20, 20) : Color.WHITE);
        }

        // длины участков посередине (у редактируемой линии и коротких маршрутов, если участок длинный на экране)
        if ((editing || n <= 30) && !(area && !editing)) {
            for (int i = 1; i < n; i++) {
                float mx = (xs[i] + xs[i - 1]) / 2, my = (ys[i] + ys[i - 1]) / 2;
                if (Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) < (editing ? 70 : 110) * density) continue;
                double[] a = pts.get(i - 1), b = pts.get(i);
                String t = Geo.dist(Geo.inverse(a[0], a[1], b[0], b[1])[0]);
                pill(c, orient, t, mx, my);
            }
        }

        if (editing) {
            // правка: номера на вершинах
            float r = 6.5f * density;
            vFill.setColor(color);
            num.setColor(light ? Color.BLACK : Color.WHITE);
            num.setTextSize(8f * density);
            for (int i = 0; i < n; i++) {
                c.drawCircle(xs[i], ys[i], r, vFill);
                c.drawCircle(xs[i], ys[i], r, vStroke);
                c.save();
                if (orient != 0f) c.rotate(-orient, xs[i], ys[i]);
                c.drawText(Integer.toString(i + 1), xs[i], ys[i] + num.getTextSize() * 0.36f, num);
                c.restore();
            }
            return;
        }
        // готовый маршрут: маленькие узлы, заметные только начало и конец
        float node = 2.3f * density;
        for (int i = 1; i < n - 1; i++) {
            c.drawCircle(xs[i], ys[i], node + 1.2f * density, dark());
            vFill.setColor(Color.WHITE);
            c.drawCircle(xs[i], ys[i], node, vFill);
        }
        if (!area) {
            // старт — кружок цвета линии в белом кольце, финиш — белый кружок с цветной серединой
            vFill.setColor(Color.WHITE);
            c.drawCircle(xs[0], ys[0], 5.2f * density, vFill);
            vFill.setColor(color);
            c.drawCircle(xs[0], ys[0], 3.6f * density, vFill);
            vFill.setColor(Color.argb(220, 20, 20, 20));
            c.drawCircle(xs[n - 1], ys[n - 1], 5.6f * density, vFill);
            vFill.setColor(Color.WHITE);
            c.drawCircle(xs[n - 1], ys[n - 1], 4.4f * density, vFill);
            vFill.setColor(color);
            c.drawCircle(xs[n - 1], ys[n - 1], 2.4f * density, vFill);
        }
    }

    private final Paint darkP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Paint dark() {
        darkP.setColor(Color.argb(200, 20, 20, 20));
        return darkP;
    }

    private final Paint arrowP = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Шевроны «>» вдоль линии через каждые ~90 dp — видно, куда идёт маршрут. */
    private void arrows(Canvas c, float[] xs, float[] ys, int col) {
        arrowP.setStyle(Paint.Style.STROKE);
        arrowP.setStrokeWidth(1.6f * density);
        arrowP.setStrokeCap(Paint.Cap.ROUND);
        arrowP.setStrokeJoin(Paint.Join.ROUND);
        arrowP.setColor(col);
        float step = 90 * density, a = 3.2f * density, carry = step / 2;
        for (int i = 1; i < xs.length; i++) {
            float dx = xs[i] - xs[i - 1], dy = ys[i] - ys[i - 1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 1) continue;
            float ux = dx / len, uy = dy / len;
            float d = carry;
            while (d < len) {
                float x = xs[i - 1] + ux * d, y = ys[i - 1] + uy * d;
                path.reset();
                path.moveTo(x - ux * a - uy * a, y - uy * a + ux * a);
                path.lineTo(x, y);
                path.lineTo(x - ux * a + uy * a, y - uy * a - ux * a);
                c.drawPath(path, arrowP);
                d += step;
            }
            carry = d - len;
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
