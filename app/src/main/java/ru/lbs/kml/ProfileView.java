package ru.lbs.kml;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** График профиля высот: заливка, ось высот, касание — высота и расстояние в этой точке. */
public class ProfileView extends View {

    private double[] dist, elev; // м
    private double minE, maxE;
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float touchX = -1;
    private double[] sight;      // высота луча визирования в каждой точке (уже с поправкой на кривизну Земли)
    private boolean[] hidden;    // точка рельефа не видна наблюдателю
    private final Paint sightOk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sightBad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hiddenLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ProfileView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * d);
        line.setColor(Color.rgb(255, 179, 0));
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(Color.argb(40, 255, 255, 255));
        grid.setStrokeWidth(1);
        text.setColor(Color.argb(200, 200, 205, 212));
        text.setTextSize(10.5f * d);
        mark.setColor(Color.WHITE);
        mark.setStrokeWidth(1.2f * d);
        tip.setColor(Color.WHITE);
        tip.setTextSize(12 * d);
        tipBg.setColor(Color.argb(230, 21, 24, 29));
        sightOk.setStyle(Paint.Style.STROKE);
        sightOk.setStrokeWidth(2f * d);
        sightOk.setColor(Color.rgb(102, 187, 106));
        sightOk.setPathEffect(new android.graphics.DashPathEffect(new float[]{8 * d, 5 * d}, 0));
        sightBad.set(sightOk);
        sightBad.setColor(Color.rgb(255, 82, 82));
        hiddenLine.setStyle(Paint.Style.STROKE);
        hiddenLine.setStrokeWidth(3.2f * d);
        hiddenLine.setColor(Color.argb(220, 255, 82, 82));
        hiddenLine.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Линия прямой видимости и «невидимые» участки рельефа (null — убрать). */
    void setSight(double[] sight, boolean[] hidden) {
        this.sight = sight;
        this.hidden = hidden;
        if (elev != null) setData(dist, elev);
    }

    void setData(double[] dist, double[] elev) {
        this.dist = dist;
        this.elev = elev;
        minE = Double.MAX_VALUE;
        maxE = -Double.MAX_VALUE;
        for (double e : elev) {
            if (Double.isNaN(e)) continue;
            minE = Math.min(minE, e);
            maxE = Math.max(maxE, e);
        }
        if (sight != null) for (double e : sight) {
            if (Double.isNaN(e)) continue;
            minE = Math.min(minE, e);
            maxE = Math.max(maxE, e);
        }
        if (maxE - minE < 20) { double m = (maxE + minE) / 2; minE = m - 10; maxE = m + 10; }
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (190 * d));
    }

    private float left() { return 40 * d; }

    @Override
    protected void onDraw(Canvas c) {
        float l = left(), r = getWidth() - 8 * d, t = 12 * d, b = getHeight() - 22 * d;
        if (dist == null || dist.length < 2 || minE == Double.MAX_VALUE) {
            c.drawText("Загружаю высоты…", l, (t + b) / 2, text);
            return;
        }
        double total = dist[dist.length - 1];
        // сетка и подписи высот
        for (int i = 0; i <= 3; i++) {
            float y = t + (b - t) * i / 3f;
            c.drawLine(l, y, r, y, grid);
            double e = maxE - (maxE - minE) * i / 3.0;
            c.drawText(Math.round(e) + " м", 2 * d, y + 4 * d, text);
        }
        c.drawText("0", l, getHeight() - 6 * d, text);
        String tot = Geo.dist(total);
        c.drawText(tot, r - text.measureText(tot), getHeight() - 6 * d, text);

        path.reset();
        boolean started = false;
        float lastX = l;
        for (int i = 0; i < dist.length; i++) {
            if (Double.isNaN(elev[i])) continue;
            float x = (float) (l + (r - l) * dist[i] / total);
            float y = (float) (b - (b - t) * (elev[i] - minE) / (maxE - minE));
            if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
            lastX = x;
        }
        Path area = new Path(path);
        area.lineTo(lastX, b);
        area.lineTo(l, b);
        area.close();
        fill.setShader(new LinearGradient(0, t, 0, b, Color.argb(120, 255, 179, 0), Color.argb(10, 255, 179, 0), Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        c.drawPath(path, line);

        if (hidden != null) {
            for (int i = 1; i < dist.length; i++) {
                if (!hidden[i] || !hidden[i - 1] || Double.isNaN(elev[i]) || Double.isNaN(elev[i - 1])) continue;
                c.drawLine(px(dist[i - 1], l, r, total), py(elev[i - 1], t, b), px(dist[i], l, r, total), py(elev[i], t, b), hiddenLine);
            }
        }
        if (sight != null) {
            for (int i = 1; i < dist.length; i++) {
                if (Double.isNaN(sight[i]) || Double.isNaN(sight[i - 1])) continue;
                boolean blocked = !Double.isNaN(elev[i]) && elev[i] > sight[i];
                c.drawLine(px(dist[i - 1], l, r, total), py(sight[i - 1], t, b), px(dist[i], l, r, total), py(sight[i], t, b),
                        blocked ? sightBad : sightOk);
            }
        }

        if (touchX >= l && touchX <= r) {
            double at = (touchX - l) / (r - l) * total;
            int i = 1;
            while (i < dist.length - 1 && dist[i] < at) i++;
            double k = dist[i] == dist[i - 1] ? 0 : (at - dist[i - 1]) / (dist[i] - dist[i - 1]);
            double e = elev[i - 1] + (elev[i] - elev[i - 1]) * k;
            if (!Double.isNaN(e)) {
                float y = (float) (b - (b - t) * (e - minE) / (maxE - minE));
                c.drawLine(touchX, t, touchX, b, mark);
                c.drawCircle(touchX, y, 4 * d, mark);
                String s = Math.round(e) + " м · " + Geo.dist(at);
                float tw = tip.measureText(s);
                float tx = Math.min(Math.max(touchX - tw / 2, l), r - tw);
                c.drawRoundRect(tx - 6 * d, t - 2 * d, tx + tw + 6 * d, t + 16 * d, 6 * d, 6 * d, tipBg);
                c.drawText(s, tx, t + 11 * d, tip);
            }
        }
    }

    private float px(double dd, float l, float r, double total) {
        return (float) (l + (r - l) * dd / total);
    }

    private float py(double e, float t, float b) {
        return (float) (b - (b - t) * (e - minE) / (maxE - minE));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            touchX = -1;
        } else {
            touchX = e.getX();
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        invalidate();
        return true;
    }
}
