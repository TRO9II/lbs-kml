package ru.lbs.kml;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Рамка выбора участка для офлайн-загрузки: вне рамки затемнено, углы можно тянуть.
 * Касания мимо углов проходят к карте — её можно двигать и приближать под рамкой.
 */
public class AreaFrameView extends View {

    interface OnChange {
        void changed();
    }

    final RectF frame = new RectF();
    private final Paint dim = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG),
            knobRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private int drag = -1; // какой угол тянем: 0 лв, 1 пв, 2 пн, 3 лн
    private float topLimit, bottomLimit;
    OnChange onChange;

    public AreaFrameView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        dim.setColor(Color.argb(120, 0, 0, 0));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        line.setColor(0xFFFF5A4E);
        line.setPathEffect(new android.graphics.DashPathEffect(new float[]{10 * d, 6 * d}, 0));
        knob.setColor(Color.WHITE);
        knobRing.setColor(0xFFFF5A4E);
    }

    /** Свободное место по вертикали (сверху — карточки, снизу — панель). */
    void setLimits(float top, float bottom) {
        topLimit = top;
        bottomLimit = bottom;
        if (getWidth() > 0) {
            frame.setEmpty();
            onSizeChanged(getWidth(), getHeight(), 0, 0);
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (frame.isEmpty()) {
            float bot = bottomLimit > 0 ? bottomLimit : h;
            float top = Math.max(topLimit, 0);
            float fw = w * 0.72f, fh = Math.min((bot - top) * 0.62f, fw * 1.2f);
            float cy = (top + bot) / 2;
            frame.set((w - fw) / 2, cy - fh / 2, (w + fw) / 2, cy + fh / 2);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, frame.top, dim);
        c.drawRect(0, frame.bottom, w, h, dim);
        c.drawRect(0, frame.top, frame.left, frame.bottom, dim);
        c.drawRect(frame.right, frame.top, w, frame.bottom, dim);
        c.drawRect(frame, line);
        float[][] k = corners();
        for (float[] p : k) {
            c.drawCircle(p[0], p[1], 11 * d, knobRing);
            c.drawCircle(p[0], p[1], 7.5f * d, knob);
        }
    }

    private float[][] corners() {
        return new float[][]{{frame.left, frame.top}, {frame.right, frame.top}, {frame.right, frame.bottom}, {frame.left, frame.bottom}};
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                drag = -1;
                float[][] k = corners();
                for (int i = 0; i < 4; i++) if (Math.hypot(e.getX() - k[i][0], e.getY() - k[i][1]) < 30 * d) drag = i;
                return drag >= 0; // мимо углов — карта под рамкой
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag < 0) return false;
                float min = 60 * d, x = Math.max(8 * d, Math.min(getWidth() - 8 * d, e.getX()));
                float y = Math.max(topLimit + 8 * d, Math.min((bottomLimit > 0 ? bottomLimit : getHeight()) - 8 * d, e.getY()));
                if (drag == 0 || drag == 3) frame.left = Math.min(x, frame.right - min); else frame.right = Math.max(x, frame.left + min);
                if (drag == 0 || drag == 1) frame.top = Math.min(y, frame.bottom - min); else frame.bottom = Math.max(y, frame.top + min);
                invalidate();
                if (onChange != null) onChange.changed();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = drag >= 0;
                drag = -1;
                return was;
        }
        return false;
    }
}
