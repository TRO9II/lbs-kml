package ru.lbs.kml;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Масштабная линейка: горизонтальная черта с засечками на концах. */
public class ScaleBarView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float barPx = 100;

    public ScaleBarView(Context c, AttributeSet a) {
        super(c, a);
        p.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);
        p.setStrokeCap(Paint.Cap.SQUARE);
        p.setColor(c.getColor(R.color.hud_text));
    }

    void setBarPx(float px) {
        if (Math.abs(px - barPx) < 0.5f) return;
        barPx = px;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension((int) (barPx + 4 * d), (int) (10 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float x0 = 2 * d, x1 = x0 + barPx, yb = getHeight() - 2 * d;
        c.drawLine(x0, yb, x1, yb, p);
        c.drawLine(x0, yb, x0, 2 * d, p);
        c.drawLine(x1, yb, x1, 2 * d, p);
    }
}
