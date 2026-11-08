package ru.lbs.kml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Свои точки на карте: хранение, цвета, значки, экспорт в KML. */
final class UserPoints {

    static final String[] COLOR_NAMES = {"Красный", "Тёмно-красный", "Оранжевый", "Янтарный", "Жёлтый", "Салатовый",
            "Зелёный", "Тёмно-зелёный", "Бирюзовый", "Голубой", "Синий", "Фиолетовый", "Розовый", "Коричневый",
            "Серый", "Чёрный"};
    static final int[] COLORS = {
            0xFFE53935, 0xFF8E0000, 0xFFFB8C00, 0xFFFFB300, 0xFFFDD835, 0xFF9CCC65,
            0xFF43A047, 0xFF1B5E20, 0xFF00897B, 0xFF29B6F6, 0xFF1E88E5, 0xFF7E57C2, 0xFFEC407A, 0xFF6D4C41,
            0xFF78909C, 0xFF212121};

    /** Значки внутри булавки (символ, название). Пустой — белая точка. */
    static final String[] ICONS = {"", "★", "⚑", "✚", "!", "✕", "▲", "■", "◆", "⌂", "≈", "?", "⊕", "▼", "●", "1", "2", "3"};
    static final String[] ICON_NAMES = {"Точка", "Звезда", "Флаг", "Медицина", "Внимание", "Цель", "Высота",
            "Квадрат", "Ромб", "Строение", "Вода", "Неизвестно", "Ориентир", "Спуск", "Круг", "1", "2", "3"};

    static final class Point {
        String id, name, note, icon = "";
        int color;
        double lat, lon;
        long created;
    }

    private UserPoints() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "points.json");
    }

    static synchronized List<Point> load(Context c) {
        List<Point> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, k;
            while (off < b.length && (k = in.read(b, off, b.length - off)) > 0) off += k;
            JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Point p = new Point();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.note = o.optString("note");
                p.color = o.optInt("color", COLORS[0]);
                p.icon = o.optString("icon", "");
                p.lat = o.getDouble("lat");
                p.lon = o.getDouble("lon");
                p.created = o.optLong("created");
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static synchronized void save(Context c, List<Point> pts) throws Exception {
        JSONArray arr = new JSONArray();
        for (Point p : pts) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("note", p.note == null ? "" : p.note);
            o.put("color", p.color);
            o.put("icon", p.icon == null ? "" : p.icon);
            o.put("lat", p.lat);
            o.put("lon", p.lon);
            o.put("created", p.created);
            arr.put(o);
        }
        File tmp = new File(c.getFilesDir(), "points.json.tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file(c))) throw new Exception("не удалось сохранить точки");
    }

    static String colorName(int color) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == color) return COLOR_NAMES[i];
        return "";
    }

    // ---------- значок-булавка ----------

    private static final Map<String, Bitmap> PIN_CACHE = new HashMap<>();

    static Bitmap pin(int color, float density) {
        return pin(color, density, "");
    }

    /** Булавка заданного цвета с белой обводкой и значком (или точкой); острие — в центре низа картинки. */
    static synchronized Bitmap pin(int color, float density, String icon) {
        if (icon == null) icon = "";
        String key = color + "|" + density + "|" + icon;
        Bitmap b = PIN_CACHE.get(key);
        if (b != null) return b;
        int w = Math.round(26 * density), h = Math.round(35 * density);
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = w / 2f, r = w / 2f - 2 * density, cy = r + 2 * density;
        Path p = new Path();
        p.addCircle(cx, cy, r, Path.Direction.CW);
        Path tip = new Path();
        tip.moveTo(cx - r * 0.62f, cy + r * 0.72f);
        tip.lineTo(cx, h - 1.5f * density);
        tip.lineTo(cx + r * 0.62f, cy + r * 0.72f);
        tip.close();
        p.op(tip, Path.Op.UNION);

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(70, 0, 0, 0));
        c.save();
        c.translate(0, 1.2f * density);
        c.drawPath(p, shadow);
        c.restore();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        c.drawPath(p, fill);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
        stroke.setColor(Color.WHITE);
        c.drawPath(p, stroke);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.WHITE);
        if (icon.isEmpty()) {
            c.drawCircle(cx, cy, r * 0.36f, dot);
        } else {
            dot.setTextAlign(Paint.Align.CENTER);
            dot.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            dot.setTextSize(r * 1.15f);
            android.graphics.Rect bounds = new android.graphics.Rect();
            dot.getTextBounds(icon, 0, icon.length(), bounds);
            c.drawText(icon, cx, cy - bounds.exactCenterY(), dot);
        }
        PIN_CACHE.put(key, b);
        return b;
    }

    // ---------- экспорт ----------

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** KML: цвет в формате AABBGGRR. */
    private static String kmlColor(int argb) {
        return String.format(Locale.US, "ff%02x%02x%02x", argb & 0xFF, (argb >> 8) & 0xFF, (argb >> 16) & 0xFF);
    }

    static String toKml(List<Point> pts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Мои точки</name>\n");
        for (int i = 0; i < COLORS.length; i++) {
            sb.append("<Style id=\"c").append(i).append("\"><IconStyle><color>").append(kmlColor(COLORS[i]))
              .append("</color><Icon><href>http://maps.google.com/mapfiles/kml/paddle/wht-blank.png</href></Icon></IconStyle></Style>\n");
        }
        for (Point p : pts) {
            int ci = 0;
            for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == p.color) ci = i;
            String sk = Sk42.format(p.lat, p.lon);
            sb.append("<Placemark><name>").append(esc(p.name)).append("</name><description>")
              .append(esc((p.note == null || p.note.isEmpty() ? "" : p.note + "\n") + "СК-42: " + sk))
              .append("</description><styleUrl>#c").append(ci).append("</styleUrl><Point><coordinates>")
              .append(String.format(Locale.US, "%.7f,%.7f,0", p.lon, p.lat))
              .append("</coordinates></Point></Placemark>\n");
        }
        sb.append("</Document></kml>\n");
        return sb.toString();
    }

    /** GPX 1.1: путевые точки (для навигаторов и AlpineQuest). */
    static String toGpx(List<Point> pts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<gpx version=\"1.1\" creator=\"LBS KML\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (Point p : pts) {
            sb.append(String.format(Locale.US, "<wpt lat=\"%.7f\" lon=\"%.7f\">", p.lat, p.lon))
              .append("<name>").append(esc(p.name)).append("</name>")
              .append("<desc>").append(esc((p.note == null || p.note.isEmpty() ? "" : p.note + "; ")
                      + "СК-42: " + Sk42.format(p.lat, p.lon))).append("</desc>")
              .append("</wpt>\n");
        }
        sb.append("</gpx>\n");
        return sb.toString();
    }
}
