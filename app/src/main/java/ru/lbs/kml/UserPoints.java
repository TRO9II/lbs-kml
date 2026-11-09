package ru.lbs.kml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

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
    static final String[] ICONS = {"", "★", "⚑", "✚", "!", "✕",
            "eye", "pos", "relay", "ew", "drone", "mine",
            "target", "shelter", "store", "fuel", "block", "car",
            "▲", "⌂", "≈", "tree", "tent", "bridge",
            "⊕", "?", "1", "2", "3"};
    static final String[] ICON_NAMES = {"Точка", "Звезда", "Флаг", "Медпункт", "Внимание", "Опасно",
            "Наблюдение (НП)", "Позиция", "Ретранслятор", "РЭБ", "БПЛА", "Мины",
            "Цель", "Укрытие", "Склад", "ГСМ", "Блокпост", "Транспорт",
            "Высота", "Строение", "Вода", "Лес", "Лагерь", "Мост",
            "Ориентир", "Неизвестно", "1", "2", "3"};

    static final class Point {
        String id, name, note, icon = "";
        String geo = ""; // сектор и круги дальности: {"sa":азимут,"sw":ширина,"sr":дальность м,"rg":"1,3,5"}
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
                p.geo = o.optString("geo", "");
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
            if (p.geo != null && !p.geo.isEmpty()) o.put("geo", p.geo);
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

    /** Яркий цвет — значок тёмный, тёмный цвет — значок белый. */
    static boolean light(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, bl = color & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * bl > 170;
    }

    /**
     * Метка точки: круглый знак со значком, белым ободком и тенью.
     * Центр картинки = координата точки (не «капля» с острием).
     */
    static synchronized Bitmap pin(int color, float density, String icon) {
        if (icon == null) icon = "";
        String key = color + "|" + density + "|" + icon;
        Bitmap b = PIN_CACHE.get(key);
        if (b != null) return b;
        int s = Math.round(23 * density);
        b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = s / 2f, cy = s / 2f, r = 8.6f * density;

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(90, 0, 0, 0));
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(1.6f * density, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + 1.2f * density, r + 1f * density, shadow);

        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r + 0.5f * density, edge);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        c.drawCircle(cx, cy, r - 1.4f * density, fill);
        // лёгкий блик сверху для объёма
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        gloss.setShader(new android.graphics.LinearGradient(0, cy - r, 0, cy + r,
                Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r - 1.4f * density, gloss);

        boolean lt = light(color);
        PinIcons.draw(c, icon, cx, cy, (r - 1.4f * density) * 0.62f, lt ? 0xFF1B1F24 : Color.WHITE, color);
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
