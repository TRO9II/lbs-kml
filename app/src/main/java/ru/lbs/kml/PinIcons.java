package ru.lbs.kml;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Векторные значки точек: рисуются кодом, одинаково чётко в булавке и в выборе значка. */
final class PinIcons {

    private PinIcons() {}

    private static final Paint F = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint S = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path P = new Path();

    /**
     * Рисует значок key с центром (cx, cy) в квадрате ±r.
     * fg — цвет значка, bg — цвет фона (для вырезов, например двери палатки).
     */
    static synchronized void draw(Canvas c, String key, float cx, float cy, float r, int fg, int bg) {
        if (key == null) key = "";
        F.setStyle(Paint.Style.FILL);
        F.setColor(fg);
        F.setTypeface(null);
        S.setStyle(Paint.Style.STROKE);
        S.setColor(fg);
        S.setStrokeWidth(0.22f * r);
        S.setStrokeCap(Paint.Cap.ROUND);
        S.setStrokeJoin(Paint.Join.ROUND);
        c.save();
        c.translate(cx, cy);
        P.reset();
        switch (key) {
            case "":
                c.drawCircle(0, 0, 0.42f * r, F);
                break;
            case "★": {
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float rr = (i % 2 == 0 ? 1.0f : 0.42f) * r;
                    float x = (float) (Math.cos(a) * rr), y = (float) (Math.sin(a) * rr) + 0.08f * r;
                    if (i == 0) P.moveTo(x, y); else P.lineTo(x, y);
                }
                P.close();
                c.drawPath(P, F);
                break;
            }
            case "⚑":
                c.drawLine(-0.5f * r, -0.85f * r, -0.5f * r, 0.9f * r, S);
                P.moveTo(-0.5f * r, -0.88f * r);
                P.lineTo(0.8f * r, -0.5f * r);
                P.lineTo(-0.5f * r, -0.08f * r);
                P.close();
                c.drawPath(P, F);
                break;
            case "✚": {
                float w = 0.27f * r, l = 0.85f * r;
                c.drawRoundRect(-w, -l, w, l, 0.08f * r, 0.08f * r, F);
                c.drawRoundRect(-l, -w, l, w, 0.08f * r, 0.08f * r, F);
                break;
            }
            case "!":
                S.setStrokeWidth(0.34f * r);
                c.drawLine(0, -0.78f * r, 0, 0.22f * r, S);
                c.drawCircle(0, 0.74f * r, 0.19f * r, F);
                break;
            case "✕":
                S.setStrokeWidth(0.3f * r);
                c.drawLine(-0.62f * r, -0.62f * r, 0.62f * r, 0.62f * r, S);
                c.drawLine(0.62f * r, -0.62f * r, -0.62f * r, 0.62f * r, S);
                break;
            case "▲": // высота / гора
                P.moveTo(-0.98f * r, 0.75f * r);
                P.lineTo(-0.22f * r, -0.7f * r);
                P.lineTo(0.12f * r, -0.08f * r);
                P.lineTo(0.42f * r, -0.42f * r);
                P.lineTo(0.98f * r, 0.75f * r);
                P.close();
                c.drawPath(P, F);
                break;
            case "⌂": // строение
                P.moveTo(-0.9f * r, -0.02f * r);
                P.lineTo(0, -0.88f * r);
                P.lineTo(0.9f * r, -0.02f * r);
                P.lineTo(0.62f * r, -0.02f * r);
                P.lineTo(0.62f * r, 0.85f * r);
                P.lineTo(-0.62f * r, 0.85f * r);
                P.lineTo(-0.62f * r, -0.02f * r);
                P.close();
                c.drawPath(P, F);
                F.setColor(bg);
                c.drawRect(-0.17f * r, 0.3f * r, 0.17f * r, 0.85f * r, F);
                break;
            case "≈": // вода
                S.setStrokeWidth(0.2f * r);
                for (int k = 0; k < 3; k++) {
                    float y = (-0.5f + 0.5f * k) * r;
                    P.reset();
                    P.moveTo(-0.9f * r, y);
                    P.quadTo(-0.45f * r, y - 0.32f * r, 0, y);
                    P.quadTo(0.45f * r, y + 0.32f * r, 0.9f * r, y);
                    c.drawPath(P, S);
                }
                break;
            case "tree": // лес
                P.moveTo(0, -0.98f * r);
                P.lineTo(0.55f * r, -0.15f * r);
                P.lineTo(0.3f * r, -0.15f * r);
                P.lineTo(0.75f * r, 0.45f * r);
                P.lineTo(-0.75f * r, 0.45f * r);
                P.lineTo(-0.3f * r, -0.15f * r);
                P.lineTo(-0.55f * r, -0.15f * r);
                P.close();
                c.drawPath(P, F);
                c.drawRect(-0.13f * r, 0.4f * r, 0.13f * r, 0.95f * r, F);
                break;
            case "tent": // лагерь
                P.moveTo(-0.95f * r, 0.8f * r);
                P.lineTo(0, -0.85f * r);
                P.lineTo(0.95f * r, 0.8f * r);
                P.close();
                c.drawPath(P, F);
                P.reset();
                P.moveTo(-0.28f * r, 0.8f * r);
                P.lineTo(0, 0.18f * r);
                P.lineTo(0.28f * r, 0.8f * r);
                P.close();
                F.setColor(bg);
                c.drawPath(P, F);
                break;
            case "car": // транспорт
                P.moveTo(-0.55f * r, -0.08f * r);
                P.lineTo(-0.34f * r, -0.58f * r);
                P.lineTo(0.34f * r, -0.58f * r);
                P.lineTo(0.55f * r, -0.08f * r);
                P.close();
                c.drawPath(P, F);
                c.drawRoundRect(-0.95f * r, -0.12f * r, 0.95f * r, 0.5f * r, 0.18f * r, 0.18f * r, F);
                F.setColor(bg);
                c.drawCircle(-0.5f * r, 0.52f * r, 0.27f * r, F);
                c.drawCircle(0.5f * r, 0.52f * r, 0.27f * r, F);
                F.setColor(fg);
                c.drawCircle(-0.5f * r, 0.52f * r, 0.17f * r, F);
                c.drawCircle(0.5f * r, 0.52f * r, 0.17f * r, F);
                break;
            case "bridge": // мост
                S.setStrokeWidth(0.2f * r);
                c.drawLine(-0.95f * r, 0.1f * r, 0.95f * r, 0.1f * r, S);
                P.moveTo(-0.75f * r, 0.1f * r);
                P.quadTo(0, -1.0f * r, 0.75f * r, 0.1f * r);
                c.drawPath(P, S);
                c.drawLine(-0.65f * r, 0.1f * r, -0.65f * r, 0.75f * r, S);
                c.drawLine(0.65f * r, 0.1f * r, 0.65f * r, 0.75f * r, S);
                S.setStrokeWidth(0.13f * r);
                c.drawLine(-0.9f * r, 0.85f * r, 0.9f * r, 0.85f * r, S);
                break;
            case "⊕": // ориентир
                S.setStrokeWidth(0.17f * r);
                c.drawCircle(0, 0, 0.6f * r, S);
                c.drawLine(0, -0.98f * r, 0, -0.32f * r, S);
                c.drawLine(0, 0.32f * r, 0, 0.98f * r, S);
                c.drawLine(-0.98f * r, 0, -0.32f * r, 0, S);
                c.drawLine(0.32f * r, 0, 0.98f * r, 0, S);
                c.drawCircle(0, 0, 0.14f * r, F);
                break;
            case "relay": { // ретранслятор: мачта и волны
                S.setStrokeWidth(0.17f * r);
                c.drawLine(0, -0.2f * r, -0.42f * r, 0.95f * r, S);
                c.drawLine(0, -0.2f * r, 0.42f * r, 0.95f * r, S);
                c.drawLine(-0.24f * r, 0.4f * r, 0.24f * r, 0.4f * r, S);
                c.drawCircle(0, -0.28f * r, 0.17f * r, F);
                android.graphics.RectF o = new android.graphics.RectF();
                for (int k = 1; k <= 2; k++) {
                    float rr = (0.32f + 0.3f * k) * r;
                    o.set(-rr, -0.28f * r - rr, rr, -0.28f * r + rr);
                    c.drawArc(o, -150, 50, false, S);
                    c.drawArc(o, -80, 50, false, S);
                }
                break;
            }
            case "eye": { // наблюдательный пункт
                P.moveTo(-0.98f * r, 0);
                P.quadTo(0, -0.95f * r, 0.98f * r, 0);
                P.quadTo(0, 0.95f * r, -0.98f * r, 0);
                P.close();
                c.drawPath(P, F);
                F.setColor(bg);
                c.drawCircle(0, 0, 0.36f * r, F);
                F.setColor(fg);
                c.drawCircle(0, 0, 0.2f * r, F);
                break;
            }
            case "pos": { // позиция: бруствер с бойницами
                P.moveTo(-0.95f * r, 0.75f * r);
                P.lineTo(-0.95f * r, -0.2f * r);
                P.lineTo(-0.55f * r, -0.2f * r);
                P.lineTo(-0.55f * r, -0.62f * r);
                P.lineTo(-0.2f * r, -0.62f * r);
                P.lineTo(-0.2f * r, -0.2f * r);
                P.lineTo(0.2f * r, -0.2f * r);
                P.lineTo(0.2f * r, -0.62f * r);
                P.lineTo(0.55f * r, -0.62f * r);
                P.lineTo(0.55f * r, -0.2f * r);
                P.lineTo(0.95f * r, -0.2f * r);
                P.lineTo(0.95f * r, 0.75f * r);
                P.close();
                c.drawPath(P, F);
                break;
            }
            case "mine": { // мины: шар с шипами
                c.drawCircle(0, 0.08f * r, 0.5f * r, F);
                S.setStrokeWidth(0.16f * r);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    c.drawLine((float) Math.cos(a) * 0.5f * r, 0.08f * r + (float) Math.sin(a) * 0.5f * r,
                            (float) Math.cos(a) * 0.9f * r, 0.08f * r + (float) Math.sin(a) * 0.9f * r, S);
                }
                break;
            }
            case "drone": { // БПЛА: крест и четыре винта
                S.setStrokeWidth(0.16f * r);
                c.drawLine(-0.55f * r, -0.55f * r, 0.55f * r, 0.55f * r, S);
                c.drawLine(0.55f * r, -0.55f * r, -0.55f * r, 0.55f * r, S);
                c.drawRoundRect(-0.2f * r, -0.2f * r, 0.2f * r, 0.2f * r, 0.06f * r, 0.06f * r, F);
                S.setStrokeWidth(0.12f * r);
                for (int i = 0; i < 4; i++) {
                    float x = (i % 2 == 0 ? -0.62f : 0.62f) * r, y = (i < 2 ? -0.62f : 0.62f) * r;
                    c.drawCircle(x, y, 0.3f * r, S);
                }
                break;
            }
            case "ew": // РЭБ: молния
                P.moveTo(0.18f * r, -0.98f * r);
                P.lineTo(-0.62f * r, 0.12f * r);
                P.lineTo(-0.05f * r, 0.12f * r);
                P.lineTo(-0.25f * r, 0.98f * r);
                P.lineTo(0.62f * r, -0.18f * r);
                P.lineTo(0.05f * r, -0.18f * r);
                P.close();
                c.drawPath(P, F);
                break;
            case "store": { // склад: ящик
                c.drawRoundRect(-0.85f * r, -0.62f * r, 0.85f * r, 0.75f * r, 0.1f * r, 0.1f * r, F);
                S.setColor(bg);
                S.setStrokeWidth(0.12f * r);
                c.drawLine(-0.85f * r, -0.2f * r, 0.85f * r, -0.2f * r, S);
                c.drawLine(-0.85f * r, -0.62f * r, 0.85f * r, 0.75f * r, S);
                c.drawLine(0.85f * r, -0.62f * r, -0.85f * r, 0.75f * r, S);
                S.setColor(fg);
                break;
            }
            case "shelter": { // укрытие: купол со входом
                P.moveTo(-0.95f * r, 0.75f * r);
                P.cubicTo(-0.95f * r, -0.95f * r, 0.95f * r, -0.95f * r, 0.95f * r, 0.75f * r);
                P.close();
                c.drawPath(P, F);
                F.setColor(bg);
                P.reset();
                P.moveTo(-0.3f * r, 0.75f * r);
                P.cubicTo(-0.3f * r, 0.05f * r, 0.3f * r, 0.05f * r, 0.3f * r, 0.75f * r);
                P.close();
                c.drawPath(P, F);
                break;
            }
            case "fuel": { // ГСМ: канистра
                P.moveTo(-0.6f * r, -0.5f * r);
                P.lineTo(-0.2f * r, -0.9f * r);
                P.lineTo(0.62f * r, -0.9f * r);
                P.lineTo(0.62f * r, 0.95f * r);
                P.lineTo(-0.6f * r, 0.95f * r);
                P.close();
                c.drawPath(P, F);
                S.setColor(bg);
                S.setStrokeWidth(0.12f * r);
                c.drawLine(-0.3f * r, -0.1f * r, 0.35f * r, 0.6f * r, S);
                c.drawLine(0.35f * r, -0.1f * r, -0.3f * r, 0.6f * r, S);
                S.setColor(fg);
                break;
            }
            case "target": // цель: кольца и точка
                S.setStrokeWidth(0.15f * r);
                c.drawCircle(0, 0, 0.82f * r, S);
                c.drawCircle(0, 0, 0.48f * r, S);
                c.drawCircle(0, 0, 0.18f * r, F);
                break;
            case "block": { // блокпост: шлагбаум
                c.drawRect(-0.85f * r, -0.1f * r, -0.6f * r, 0.95f * r, F);
                c.save();
                c.rotate(-12, -0.72f * r, 0);
                c.drawRoundRect(-0.72f * r, -0.28f * r, 0.98f * r, 0.06f * r, 0.08f * r, 0.08f * r, F);
                F.setColor(bg);
                c.drawRect(-0.2f * r, -0.28f * r, 0.1f * r, 0.06f * r, F);
                c.drawRect(0.4f * r, -0.28f * r, 0.7f * r, 0.06f * r, F);
                F.setColor(fg);
                c.restore();
                break;
            }
            case "■":
                c.drawRoundRect(-0.6f * r, -0.6f * r, 0.6f * r, 0.6f * r, 0.12f * r, 0.12f * r, F);
                break;
            case "◆":
                P.moveTo(0, -0.85f * r);
                P.lineTo(0.75f * r, 0);
                P.lineTo(0, 0.85f * r);
                P.lineTo(-0.75f * r, 0);
                P.close();
                c.drawPath(P, F);
                break;
            case "▼":
                P.moveTo(-0.8f * r, -0.6f * r);
                P.lineTo(0.8f * r, -0.6f * r);
                P.lineTo(0, 0.75f * r);
                P.close();
                c.drawPath(P, F);
                break;
            case "●":
                c.drawCircle(0, 0, 0.62f * r, F);
                break;
            default: { // текст: ?, цифры и любые прежние символы
                F.setTextAlign(Paint.Align.CENTER);
                F.setTypeface(Typeface.DEFAULT_BOLD);
                F.setTextSize(1.75f * r);
                android.graphics.Rect b = new android.graphics.Rect();
                F.getTextBounds(key, 0, key.length(), b);
                c.drawText(key, 0, -b.exactCenterY(), F);
                F.setTypeface(null);
                break;
            }
        }
        c.restore();
    }

    /** Значок как Drawable (для кнопок выбора). */
    static final class IconDrawable extends Drawable {
        private final String key;
        private int fg, bg;

        IconDrawable(String key, int fg, int bg) {
            this.key = key;
            this.fg = fg;
            this.bg = bg;
        }

        void setColors(int fg, int bg) {
            this.fg = fg;
            this.bg = bg;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float r = Math.min(b.width(), b.height()) / 2f * 0.9f;
            PinIcons.draw(c, key, b.centerX(), b.centerY(), r, fg, bg);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
