package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Typeface;
import android.view.MotionEvent;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/** Участники группы на карте: кружок с инициалами цвета позывного, подпись и «сколько минут назад». */
public class MembersOverlay extends Overlay {

    interface OnTap {
        void on(ChatClient.Member m);
    }

    private final float d;
    private List<ChatClient.Member> members = new ArrayList<>();
    private OnTap listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            ini = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            labelBg = new Paint(Paint.ANTI_ALIAS_FLAG), acc = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pt = new Point(), sp = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Path tri = new Path(), tailPath = new Path();
    private final Paint tailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MembersOverlay(float density) {
        d = density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * d);
        ring.setColor(Color.WHITE);
        ini.setColor(Color.WHITE);
        ini.setTextAlign(Paint.Align.CENTER);
        ini.setTypeface(Typeface.DEFAULT_BOLD);
        ini.setTextSize(10.5f * d);
        label.setColor(Color.WHITE);
        label.setTextSize(11.5f * d);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        labelBg.setColor(Color.argb(205, 21, 24, 29));
        acc.setColor(Color.argb(30, 255, 255, 255));
        arrow.setColor(Color.WHITE);
        tailPaint.setStyle(Paint.Style.STROKE);
        tailPaint.setStrokeWidth(2.4f * density);
        tailPaint.setStrokeCap(Paint.Cap.ROUND);
        tailPaint.setStrokeJoin(Paint.Join.ROUND);
        tailPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * density, 5 * density}, 0));
    }

    private final Point sp3 = new Point();

    /** Где на экране кружки участников с подписями. */
    void boxes(MapView mv, List<android.graphics.RectF> out) {
        if (!isEnabled()) return;
        org.osmdroid.views.Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        for (ChatClient.Member m : members) {
            org.osmdroid.util.GeoPoint g = new org.osmdroid.util.GeoPoint(m.lat, m.lon);
            Point q = pj.toPixels(g, null);
            if (orient != 0f) pj.rotateAndScalePoint(q.x, q.y, sp3); else sp3.set(q.x, q.y);
            String t = (m.name.isEmpty() ? "?" : m.name) + " · " + age(m.recv);
            float tw = label.measureText(t);
            out.add(new android.graphics.RectF(sp3.x - 14 * d, sp3.y - 20 * d, sp3.x + 11 * d + 10 * d + tw + 6 * d, sp3.y + 14 * d));
        }
    }

    void setMembers(List<ChatClient.Member> m) {
        members = m;
    }

    void setListener(OnTap l) {
        listener = l;
    }

    static int color(String name) {
        int[] c = {0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8, 0xFF4DD0E1, 0xFFF06292, 0xFFAED581, 0xFFFFD54F};
        return c[Math.abs(name.hashCode()) % c.length];
    }

    static String initials(String name) {
        String n = name.trim();
        if (n.isEmpty()) return "?";
        String[] p = n.split("\\s+");
        if (p.length > 1) return ("" + p[0].charAt(0) + p[1].charAt(0)).toUpperCase();
        return n.substring(0, Math.min(2, n.length())).toUpperCase();
    }

    static String age(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        if (m < 1) return "сейчас";
        if (m < 60) return m + " мин";
        if (m < 24 * 60) return (m / 60) + " ч";
        return (m / 1440) + " д";
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        long now = System.currentTimeMillis();
        for (ChatClient.Member m : members) {
            gp.setCoords(m.lat, m.lon);
            pj.toPixels(gp, pt);
            long ageMs = now - m.recv;
            boolean stale = ageMs > 15 * 60_000;
            int col = color(m.name);
            int alpha = stale ? 120 : 255;
            // точность
            GeoPoint g2 = new GeoPoint(m.lat + 100.0 / 111132.0, m.lon);
            Point p2 = pj.toPixels(g2, null);
            float pxPerM = (float) (Math.hypot(p2.x - pt.x, p2.y - pt.y) / 100.0);
            float ar = m.acc * pxPerM;
            if (!stale && ar > 14 * d) c.drawCircle(pt.x, pt.y, ar, acc);
            // след за последний час
            if (m.tail.size() > 1) {
                tailPaint.setColor((col & 0x00FFFFFF) | (stale ? 0x55000000 : 0xB0000000));
                tailPath.reset();
                for (int i = 0; i < m.tail.size(); i++) {
                    gp.setCoords(m.tail.get(i)[0], m.tail.get(i)[1]);
                    Point q = pj.toPixels(gp, null);
                    if (i == 0) tailPath.moveTo(q.x, q.y); else tailPath.lineTo(q.x, q.y);
                }
                tailPath.lineTo(pt.x, pt.y);
                c.drawPath(tailPath, tailPaint);
                gp.setCoords(m.lat, m.lon);
            }
            // направление движения
            if (!stale && !Float.isNaN(m.heading)) {
                c.save();
                c.rotate(m.heading, pt.x, pt.y);
                tri.reset();
                tri.moveTo(pt.x, pt.y - 19 * d);
                tri.lineTo(pt.x - 5 * d, pt.y - 12 * d);
                tri.lineTo(pt.x + 5 * d, pt.y - 12 * d);
                tri.close();
                arrow.setColor(col);
                c.drawPath(tri, arrow);
                c.restore();
            }
            c.save();
            if (orient != 0f) c.rotate(-orient, pt.x, pt.y);
            fill.setColor(stale ? 0xFF6B7280 : col);
            fill.setAlpha(alpha);
            ring.setAlpha(alpha);
            float r = 11 * d;
            c.drawCircle(pt.x, pt.y, r, fill);
            c.drawCircle(pt.x, pt.y, r, ring);
            ini.setAlpha(alpha);
            c.drawText(initials(m.name), pt.x, pt.y + ini.getTextSize() * 0.36f, ini);
            String t = (m.name.isEmpty() ? "?" : m.name) + " · " + age(m.recv);
            float tw = label.measureText(t), x = pt.x + r + 5 * d, y = pt.y + label.getTextSize() * 0.35f;
            float pad = 5 * d;
            c.drawRoundRect(x - pad, y - label.getTextSize() - 1 * d, x + tw + pad, y + 5 * d, 7 * d, 7 * d, labelBg);
            label.setAlpha(alpha);
            c.drawText(t, x, y, label);
            c.restore();
        }
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e, MapView mv) {
        if (listener == null || members.isEmpty()) return false;
        Projection pj = mv.getProjection();
        float orient = mv.getMapOrientation();
        ChatClient.Member best = null;
        float bestD = 26 * d;
        for (ChatClient.Member m : members) {
            gp.setCoords(m.lat, m.lon);
            pj.toPixels(gp, pt);
            if (orient != 0f) pj.rotateAndScalePoint(pt.x, pt.y, sp); else sp.set(pt.x, pt.y);
            float dd = (float) Math.hypot(e.getX() - sp.x, e.getY() - sp.y);
            if (dd < bestD) { bestD = dd; best = m; }
        }
        if (best == null) return false;
        listener.on(best);
        return true;
    }
}
