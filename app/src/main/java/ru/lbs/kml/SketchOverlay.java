package ru.lbs.kml;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Кроки: рисунок пальцем поверх карты. Линии хранятся в координатах — двигаете карту, рисунок едет вместе с ней.
 * Пока включено рисование, одно касание рисует; карта двигается в режиме «Двигать».
 */
public class SketchOverlay extends Overlay {

    interface OnTextTap {
        void at(double lat, double lon);
    }

    static final class Stroke {
        int color;
        float width; // dp
        String text = ""; // не пусто — подпись в точке pts[0]
        final List<double[]> pts = new ArrayList<>();
    }

    final List<Stroke> strokes = new ArrayList<>();
    boolean drawing;      // касания рисуют
    boolean textMode;     // касание ставит подпись
    int color = 0xFFFF3D3D;
    float width = 3.5f;
    OnTextTap onText;

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG),
            tx = new Paint(Paint.ANTI_ALIAS_FLAG), txHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private Stroke cur;

    public SketchOverlay(float density) {
        d = density;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        halo.set(p);
        tx.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tx.setTextAlign(Paint.Align.CENTER);
        txHalo.set(tx);
        txHalo.setStyle(Paint.Style.STROKE);
        txHalo.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        Projection pj = mv.getProjection();
        for (Stroke s : strokes) drawStroke(c, pj, s, mv.getMapOrientation());
    }

    private void drawStroke(Canvas c, Projection pj, Stroke s, float orient) {
        if (s.pts.isEmpty()) return;
        boolean light = Color.red(s.color) * 0.3 + Color.green(s.color) * 0.59 + Color.blue(s.color) * 0.11 > 150;
        int haloCol = light ? Color.argb(170, 0, 0, 0) : Color.argb(200, 255, 255, 255);
        if (!s.text.isEmpty()) {
            gp.setCoords(s.pts.get(0)[0], s.pts.get(0)[1]);
            pj.toPixels(gp, pt);
            float size = (11 + s.width * 1.6f) * d;
            tx.setTextSize(size);
            tx.setColor(s.color);
            txHalo.setTextSize(size);
            txHalo.setStrokeWidth(3.5f * d);
            txHalo.setColor(haloCol);
            c.save();
            if (orient != 0f) c.rotate(-orient, pt.x, pt.y);
            c.drawText(s.text, pt.x, pt.y + size * 0.35f, txHalo);
            c.drawText(s.text, pt.x, pt.y + size * 0.35f, tx);
            c.restore();
            return;
        }
        path.reset();
        for (int i = 0; i < s.pts.size(); i++) {
            gp.setCoords(s.pts.get(i)[0], s.pts.get(i)[1]);
            pj.toPixels(gp, pt);
            if (i == 0) path.moveTo(pt.x, pt.y); else path.lineTo(pt.x, pt.y);
        }
        if (s.pts.size() == 1) path.lineTo(pt.x + 0.1f, pt.y);
        halo.setColor(haloCol);
        halo.setStrokeWidth((s.width + 2) * d);
        p.setColor(s.color);
        p.setStrokeWidth(s.width * d);
        c.drawPath(path, halo);
        c.drawPath(path, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e, MapView mv) {
        if (!drawing) return false;
        if (e.getPointerCount() > 1) {
            if (cur != null && cur.pts.size() < 3) strokes.remove(cur);
            cur = null;
            return true;
        }
        IGeoPoint g = mv.getProjection().fromPixels((int) e.getX(), (int) e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (textMode) {
                    if (onText != null) onText.at(g.getLatitude(), g.getLongitude());
                    return true;
                }
                cur = new Stroke();
                cur.color = color;
                cur.width = width;
                cur.pts.add(new double[]{g.getLatitude(), g.getLongitude()});
                strokes.add(cur);
                mv.invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (cur == null) return true;
                for (int h = 0; h < e.getHistorySize(); h++) {
                    IGeoPoint hg = mv.getProjection().fromPixels((int) e.getHistoricalX(h), (int) e.getHistoricalY(h));
                    cur.pts.add(new double[]{hg.getLatitude(), hg.getLongitude()});
                }
                cur.pts.add(new double[]{g.getLatitude(), g.getLongitude()});
                mv.invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (cur != null) simplify(cur, mv);
                cur = null;
                mv.invalidate();
                return true;
        }
        return true;
    }

    /** Прореживание: точки ближе 2 px на текущем масштабе выкидываем. */
    private void simplify(Stroke s, MapView mv) {
        if (s.pts.size() < 3) return;
        Projection pj = mv.getProjection();
        List<double[]> out = new ArrayList<>();
        float lx = 0, ly = 0;
        for (int i = 0; i < s.pts.size(); i++) {
            gp.setCoords(s.pts.get(i)[0], s.pts.get(i)[1]);
            pj.toPixels(gp, pt);
            if (i == 0 || i == s.pts.size() - 1 || Math.hypot(pt.x - lx, pt.y - ly) >= 2 * d) {
                out.add(s.pts.get(i));
                lx = pt.x;
                ly = pt.y;
            }
        }
        s.pts.clear();
        s.pts.addAll(out);
    }

    // ---------- хранение ----------

    void save(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Stroke s : strokes) {
                JSONObject o = new JSONObject().put("c", s.color).put("w", s.width);
                if (!s.text.isEmpty()) o.put("t", s.text);
                JSONArray q = new JSONArray();
                for (double[] x : s.pts) q.put(Math.round(x[0] * 1e6) / 1e6).put(Math.round(x[1] * 1e6) / 1e6);
                a.put(o.put("p", q));
            }
            java.nio.file.Files.write(file(c).toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    void load(Context c) {
        strokes.clear();
        try {
            File f = file(c);
            if (!f.exists()) return;
            JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Stroke s = new Stroke();
                s.color = o.optInt("c", 0xFFFF3D3D);
                s.width = (float) o.optDouble("w", 3.5);
                s.text = o.optString("t", "");
                JSONArray q = o.getJSONArray("p");
                for (int j = 0; j + 1 < q.length(); j += 2) s.pts.add(new double[]{q.getDouble(j), q.getDouble(j + 1)});
                strokes.add(s);
            }
        } catch (Exception ignored) {
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "sketch.json");
    }
}
