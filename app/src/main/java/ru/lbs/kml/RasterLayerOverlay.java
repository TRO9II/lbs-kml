package ru.lbs.kml;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Растровые слои из файлов AlpineQuest (.pgd) поверх карты. Картинка привязана по четырём углам:
 * каждый тайл растягивается на своё место (перспективное преобразование), уровень пирамиды
 * подбирается под масштаб. Тайлы читаются с диска в фоне и держатся в небольшом кэше.
 */
public class RasterLayerOverlay extends Overlay {

    static final class Item {
        UserLayers.Layer layer;
        PgdFile pgd;
        double[] mx = new double[4], my = new double[4]; // углы в метрах Меркатора
        double[] h, hi; // проективная привязка и обратная к ней (если есть в файле)
    }

    private final List<Item> items = new ArrayList<>();
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 140;
        }
    };
    private final Set<String> pending = new HashSet<>();
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final float[] src = new float[8], dst = new float[8];
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Point pt = new Point();
    private MapView view;

    void set(List<Item> list) {
        synchronized (cache) {
            cache.clear();
            pending.clear();
        }
        items.clear();
        items.addAll(list);
    }

    static Item item(UserLayers.Layer l, PgdFile f) {
        Item it = new Item();
        it.layer = l;
        it.pgd = f;
        for (int i = 0; i < 4; i++) {
            it.mx[i] = mercX(f.lon[i]);
            it.my[i] = mercY(f.lat[i]);
        }
        if (f.hom != null) {
            it.h = f.hom;
            it.hi = inv3(f.hom);
            // углы — по той же матрице, чтобы рамка совпадала с картинкой
            double[][] px = {{0, 0}, {f.width, 0}, {f.width, f.height}, {0, f.height}};
            for (int i = 0; i < 4; i++) {
                double[] q = hom(it.h, px[i][0], px[i][1]);
                it.mx[i] = q[0];
                it.my[i] = q[1];
            }
        }
        return it;
    }

    static double mercX(double lon) {
        return Math.toRadians(lon) * 6378137.0;
    }

    static double mercY(double lat) {
        return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * 6378137.0;
    }

    static double lonOf(double x) {
        return Math.toDegrees(x / 6378137.0);
    }

    static double latOf(double y) {
        return Math.toDegrees(2 * Math.atan(Math.exp(y / 6378137.0)) - Math.PI / 2);
    }

    /** Пиксель → Меркатор по матрице AlpineQuest (у неё ось Y направлена вниз). */
    private static double[] hom(double[] m, double x, double y) {
        double w = m[6] * x + m[7] * y + m[8];
        return new double[]{(m[0] * x + m[1] * y + m[2]) / w, -(m[3] * x + m[4] * y + m[5]) / w};
    }

    private static double[] inv3(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-18) return null;
        return new double[]{A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }

    /** Точка картинки (u, v ∈ 0..1) → метры Меркатора: по матрице привязки, а без неё — билинейно по углам. */
    private static void at(Item it, double u, double v, double[] out) {
        if (it.h != null) {
            double[] q = hom(it.h, u * it.pgd.width, v * it.pgd.height);
            out[0] = q[0];
            out[1] = q[1];
            return;
        }
        double a = (1 - u) * (1 - v), b = u * (1 - v), c = u * v, d = (1 - u) * v;
        out[0] = a * it.mx[0] + b * it.mx[1] + c * it.mx[2] + d * it.mx[3];
        out[1] = a * it.my[0] + b * it.my[1] + c * it.my[2] + d * it.my[3];
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow || items.isEmpty()) return;
        view = mv;
        Projection pj = mv.getProjection();
        BoundingBox bb = mv.getBoundingBox();
        double sx0 = mercX(bb.getLonWest()), sx1 = mercX(bb.getLonEast()), sy0 = mercY(bb.getLatSouth()), sy1 = mercY(bb.getLatNorth());
        double screenRes = (sx1 - sx0) / Math.max(1, mv.getWidth()); // метров Меркатора на пиксель экрана
        double[] p = new double[2];
        for (Item it : items) {
            if (!it.layer.visible) continue;
            double minX = Math.min(Math.min(it.mx[0], it.mx[1]), Math.min(it.mx[2], it.mx[3]));
            double maxX = Math.max(Math.max(it.mx[0], it.mx[1]), Math.max(it.mx[2], it.mx[3]));
            double minY = Math.min(Math.min(it.my[0], it.my[1]), Math.min(it.my[2], it.my[3]));
            double maxY = Math.max(Math.max(it.my[0], it.my[1]), Math.max(it.my[2], it.my[3]));
            if (maxX < sx0 || minX > sx1 || maxY < sy0 || minY > sy1) continue;
            PgdFile f = it.pgd;
            int n = f.levels.size();
            // уровень: самый мелкий, у которого пиксель картинки не крупнее ~1,4 пикселя экрана
            int li = n - 1;
            for (int k = 0; k < n; k++) {
                double lw = f.width / Math.pow(2, n - 1 - k);
                if ((maxX - minX) / lw <= screenRes * 1.4) { li = k; break; }
            }
            PgdFile.Level lv = f.levels.get(li);
            double lw = f.width / Math.pow(2, n - 1 - li), lh = f.height / Math.pow(2, n - 1 - li);
            paint.setAlpha(Math.round(255 * Math.max(0.15f, Math.min(1f, it.layer.opacity))));
            // какие тайлы попадают на экран: грубо — по обратной проекции углов экрана в долю картинки
            int c0 = 0, c1 = lv.cols - 1, r0 = 0, r1 = lv.rows - 1;
            double[] uv = new double[2];
            double umin = 2, umax = -1, vmin = 2, vmax = -1;
            double[][] corners = {{sx0, sy0}, {sx1, sy0}, {sx1, sy1}, {sx0, sy1}, {(sx0 + sx1) / 2, sy0}, {(sx0 + sx1) / 2, sy1}, {sx0, (sy0 + sy1) / 2}, {sx1, (sy0 + sy1) / 2}};
            for (double[] q : corners) {
                if (!invert(it, q[0], q[1], uv)) { umin = -1; umax = 2; vmin = -1; vmax = 2; break; }
                umin = Math.min(umin, uv[0]); umax = Math.max(umax, uv[0]);
                vmin = Math.min(vmin, uv[1]); vmax = Math.max(vmax, uv[1]);
            }
            c0 = Math.max(0, (int) Math.floor(umin * lw / 256) - 1);
            c1 = Math.min(lv.cols - 1, (int) Math.floor(umax * lw / 256) + 1);
            r0 = Math.max(0, (int) Math.floor(vmin * lh / 256) - 1);
            r1 = Math.min(lv.rows - 1, (int) Math.floor(vmax * lh / 256) + 1);
            if ((long) (c1 - c0 + 1) * (r1 - r0 + 1) > 400) continue; // слишком мелко — не тянем сотни тайлов
            for (int col = c0; col <= c1; col++)
                for (int row = r0; row <= r1; row++) {
                    if (!lv.tiles.containsKey((long) col << 32 | row)) continue;
                    String key = it.layer.id + "/" + li + "/" + col + "/" + row;
                    Bitmap bmp;
                    synchronized (cache) {
                        bmp = cache.get(key);
                    }
                    if (bmp == null) {
                        request(it, lv, key, col, row);
                        continue;
                    }
                    double u0 = col * 256 / lw, u1 = (col * 256 + bmp.getWidth()) / lw;
                    double v0 = row * 256 / lh, v1 = (row * 256 + bmp.getHeight()) / lh;
                    double[][] q = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                    for (int k = 0; k < 4; k++) {
                        at(it, q[k][0], q[k][1], p);
                        gp.setCoords(latOf(p[1]), lonOf(p[0]));
                        pj.toPixels(gp, pt);
                        dst[2 * k] = pt.x;
                        dst[2 * k + 1] = pt.y;
                    }
                    float w = bmp.getWidth(), h = bmp.getHeight();
                    src[0] = 0; src[1] = 0; src[2] = w; src[3] = 0; src[4] = w; src[5] = h; src[6] = 0; src[7] = h;
                    m.setPolyToPoly(src, 0, dst, 0, 4);
                    c.drawBitmap(bmp, m, paint);
                }
        }
    }

    /** Метры Меркатора → доля картинки (u, v): обратная билинейная, несколько шагов Ньютона. */
    private static boolean invert(Item it, double x, double y, double[] uv) {
        if (it.hi != null) {
            double[] q = hom(it.hi, x, -y); // обратная матрица ждёт (X, −Y); hom() снова меняет знак
            uv[0] = q[0] / it.pgd.width;
            uv[1] = -q[1] / it.pgd.height;
            return !Double.isNaN(uv[0]) && !Double.isNaN(uv[1]);
        }
        double u = 0.5, v = 0.5;
        double[] p = new double[2];
        for (int i = 0; i < 12; i++) {
            at(it, u, v, p);
            double ex = p[0] - x, ey = p[1] - y;
            double dxu = (1 - v) * (it.mx[1] - it.mx[0]) + v * (it.mx[2] - it.mx[3]);
            double dyu = (1 - v) * (it.my[1] - it.my[0]) + v * (it.my[2] - it.my[3]);
            double dxv = (1 - u) * (it.mx[3] - it.mx[0]) + u * (it.mx[2] - it.mx[1]);
            double dyv = (1 - u) * (it.my[3] - it.my[0]) + u * (it.my[2] - it.my[1]);
            double det = dxu * dyv - dxv * dyu;
            if (Math.abs(det) < 1e-9) return false;
            u -= (ex * dyv - ey * dxv) / det;
            v -= (dxu * ey - dyu * ex) / det;
        }
        uv[0] = u;
        uv[1] = v;
        return !Double.isNaN(u) && !Double.isNaN(v);
    }

    private void request(Item it, PgdFile.Level lv, String key, int col, int row) {
        synchronized (cache) {
            if (pending.contains(key) || pending.size() > 48) return;
            pending.add(key);
        }
        exec.execute(() -> {
            Bitmap b = it.pgd.tile(lv, col, row);
            synchronized (cache) {
                pending.remove(key);
                if (b != null) cache.put(key, b);
            }
            MapView v = view;
            if (v != null && b != null) v.postInvalidate();
        });
    }
}
