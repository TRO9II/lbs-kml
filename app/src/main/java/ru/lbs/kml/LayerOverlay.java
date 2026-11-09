package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.MotionEvent;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Свои слои из файлов: линии (лесополосы), квадраты, точки — с подписями в стиле карты:
 * белые жирные буквы с тёмной обводкой, вдоль линии, всегда «не вверх ногами».
 * Подписи не налезают друг на друга; мелкие объекты на общем плане без подписей.
 */
public class LayerOverlay extends Overlay {

    interface OnTap {
        void tapped(UserLayers.Layer layer, UserLayers.Feature f);
    }

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final List<RectF> placed = new ArrayList<>();
    private float[] xs = new float[256], ys = new float[256];
    private List<UserLayers.Layer> layers = new ArrayList<>();
    OnTap onTap;

    public LayerOverlay(float density) {
        d = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        halo.set(text);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setColor(0xF0000000);
        dot.setStyle(Paint.Style.FILL);
    }

    void setLayers(List<UserLayers.Layer> l) {
        layers = l;
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow || layers.isEmpty()) return;
        BoundingBox bb = mv.getBoundingBox();
        double n = bb.getLatNorth(), s = bb.getLatSouth(), e = bb.getLonEast(), w = bb.getLonWest();
        double z = mv.getZoomLevelDouble();
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        boolean labels = z >= 12.5;
        float ts = (float) Math.max(9, Math.min(14, 9 + (z - 12.5) * 1.6)) * d;
        text.setTextSize(ts);
        halo.setTextSize(ts);
        halo.setStrokeWidth(Math.max(2.5f * d, ts * 0.24f));
        placed.clear();
        for (UserLayers.Layer one : layers) {
            if (!one.visible || one.features == null) continue;
            boolean fade = one.opacity < 0.99f;
            int sc = fade ? c.saveLayerAlpha(null, Math.round(255 * Math.max(0.1f, one.opacity))) : -1;
            drawLayers(c, java.util.Collections.singletonList(one), n, s, e, w, z, pj, orient, labels, ts);
            if (fade) c.restoreToCount(sc);
        }
    }

    private void drawLayers(Canvas c, List<UserLayers.Layer> layers, double n, double s, double e, double w, double z,
                            Projection pj, float orient, boolean labels, float ts) {
        // сначала геометрия, потом подписи — чтобы буквы были поверх линий
        for (UserLayers.Layer l : layers) {
            if (!l.visible || l.features == null || l.n < s || l.s > n || l.e < w || l.w > e) continue;
            for (UserLayers.Feature f : l.features) {
                if (f.n < s || f.s > n || f.e < w || f.w > e) continue;
                if (f.type == UserLayers.POINT) continue;
                project(pj, f);
                int k = f.lat.length;
                path.reset();
                path.moveTo(xs[0], ys[0]);
                for (int i = 1; i < k; i++) path.lineTo(xs[i], ys[i]);
                if (f.type == UserLayers.POLY) {
                    path.close();
                    if (f.fill != 0) {
                        fill.setColor(f.fill);
                        c.drawPath(path, fill);
                    }
                }
                if (f.color != 0) {
                    line.setColor(f.color);
                    line.setStrokeWidth(Math.max(1f, f.width) * d * (z < 13 ? 0.75f : 1f));
                    c.drawPath(path, line);
                }
            }
        }
        for (UserLayers.Layer l : layers) {
            if (!l.visible || l.features == null || l.n < s || l.s > n || l.e < w || l.w > e) continue;
            for (UserLayers.Feature f : l.features) {
                if (f.type != UserLayers.POINT || f.n < s || f.s > n || f.e < w || f.w > e) continue;
                gp.setCoords(f.lat[0], f.lon[0]);
                pj.toPixels(gp, pt);
                dot.setColor(0xF0000000);
                c.drawCircle(pt.x, pt.y, 5 * d, dot);
                dot.setColor(f.color != 0 ? f.color | 0xFF000000 : Color.WHITE);
                c.drawCircle(pt.x, pt.y, 3.6f * d, dot);
                if (labels && !f.name.isEmpty()) labelAt(c, f.name, pt.x, pt.y + ts * 1.15f, 0, orient, false);
            }
            if (!labels) continue;
            for (UserLayers.Feature f : l.features) {
                if (f.type == UserLayers.POINT || f.name.isEmpty() || f.n < s || f.s > n || f.e < w || f.w > e) continue;
                project(pj, f);
                if (f.type == UserLayers.LINE) labelLine(c, f, orient);
                else labelPoly(c, f, orient);
            }
        }
    }

    private void project(Projection pj, UserLayers.Feature f) {
        int k = f.lat.length;
        if (xs.length < k) {
            xs = new float[k + 64];
            ys = new float[k + 64];
        }
        for (int i = 0; i < k; i++) {
            gp.setCoords(f.lat[i], f.lon[i]);
            pj.toPixels(gp, pt);
            xs[i] = pt.x;
            ys[i] = pt.y;
        }
    }

    /** Подпись линии — посередине её длины, вдоль участка. */
    private void labelLine(Canvas c, UserLayers.Feature f, float orient) {
        int k = f.lat.length;
        float total = 0;
        for (int i = 1; i < k; i++) total += (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        float tw = text.measureText(f.name);
        if (total < tw * 0.7f) return; // на этом масштабе полоса короче подписи
        float half = total / 2, acc = 0;
        for (int i = 1; i < k; i++) {
            float dx = xs[i] - xs[i - 1], dy = ys[i] - ys[i - 1];
            float seg = (float) Math.hypot(dx, dy);
            if (acc + seg >= half && seg > 0) {
                float t = (half - acc) / seg;
                float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
                // для плавности берём общее направление полосы, если её участок короткий
                if (seg < tw && k > 2) ang = (float) Math.toDegrees(Math.atan2(ys[k - 1] - ys[0], xs[k - 1] - xs[0]));
                labelAt(c, f.name, xs[i - 1] + dx * t, ys[i - 1] + dy * t, ang, orient, false);
                return;
            }
            acc += seg;
        }
    }

    /** Подпись квадрата / участка — в центре, если помещается. */
    private void labelPoly(Canvas c, UserLayers.Feature f, float orient) {
        int k = f.lat.length;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, sx = 0, sy = 0;
        for (int i = 0; i < k; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
            sx += xs[i];
            sy += ys[i];
        }
        float tw = text.measureText(f.name);
        float size = Math.min(maxX - minX, maxY - minY);
        if (size < 12 * d || Math.max(maxX - minX, maxY - minY) < tw * 0.8f) return;
        boolean small = f.name.length() <= 3; // номера клеток — помельче, без обводки-рамки
        if (small) {
            float old = text.getTextSize();
            float sz = Math.min(old, size * 0.55f);
            text.setTextSize(sz);
            halo.setTextSize(sz);
            labelAt(c, f.name, sx / k, sy / k, 0, orient, true);
            text.setTextSize(old);
            halo.setTextSize(old);
        } else {
            labelAt(c, f.name, (minX + maxX) / 2, (minY + maxY) / 2, 0, orient, false);
        }
    }

    private void labelAt(Canvas c, String s, float x, float y, float ang, float orient, boolean force) {
        float screen = ang + orient;
        while (screen > 180) screen -= 360;
        while (screen <= -180) screen += 360;
        if (screen > 90 || screen <= -90) ang += 180;
        float tw = text.measureText(s), th = text.getTextSize();
        // занятое место — грубо, по описанному прямоугольнику повёрнутой подписи
        double r = Math.toRadians(ang + orient);
        float hw = (float) (Math.abs(Math.cos(r)) * tw / 2 + Math.abs(Math.sin(r)) * th / 2);
        float hh = (float) (Math.abs(Math.sin(r)) * tw / 2 + Math.abs(Math.cos(r)) * th / 2);
        RectF box = new RectF(x - hw, y - hh, x + hw, y + hh);
        if (!force) {
            for (RectF p : placed) if (RectF.intersects(p, box)) return;
        }
        placed.add(box);
        c.save();
        c.rotate(ang, x, y);
        float by = y + th * 0.35f; // y — центр подписи
        c.drawText(s, x, by, halo);
        c.drawText(s, x, by, text);
        c.restore();
    }

    // ---------- нажатие на объект слоя ----------

    @Override
    public boolean onSingleTapConfirmed(MotionEvent ev, MapView mv) {
        if (onTap == null || layers.isEmpty()) return false;
        Projection pj = mv.getProjection();
        float tx = ev.getX(), ty = ev.getY(), best = 14 * d;
        UserLayers.Layer bl = null;
        UserLayers.Feature bf = null;
        org.osmdroid.api.IGeoPoint g = pj.fromPixels((int) tx, (int) ty);
        for (UserLayers.Layer l : layers) {
            if (!l.visible || l.features == null) continue;
            for (UserLayers.Feature f : l.features) {
                double pad = 0.01;
                if (g.getLatitude() > f.n + pad || g.getLatitude() < f.s - pad || g.getLongitude() > f.e + pad || g.getLongitude() < f.w - pad) continue;
                project(pj, f);
                int k = f.lat.length;
                if (f.type == UserLayers.POINT) {
                    float dd = (float) Math.hypot(xs[0] - tx, ys[0] - ty);
                    if (dd < best) { best = dd; bl = l; bf = f; }
                    continue;
                }
                int segs = f.type == UserLayers.POLY ? k : k - 1;
                for (int i = 0; i < segs; i++) {
                    int j = (i + 1) % k;
                    float dd = segDist(tx, ty, xs[i], ys[i], xs[j], ys[j]);
                    if (dd < best) { best = dd; bl = l; bf = f; }
                }
                if (bf == null && f.type == UserLayers.POLY && inside(tx, ty, k)) { bl = l; bf = f; best = 13 * d; }
            }
        }
        if (bf == null) return false;
        onTap.tapped(bl, bf);
        return true;
    }

    private boolean inside(float x, float y, int k) {
        boolean in = false;
        for (int i = 0, j = k - 1; i < k; j = i++)
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) in = !in;
        return in;
    }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return (float) Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }
}
