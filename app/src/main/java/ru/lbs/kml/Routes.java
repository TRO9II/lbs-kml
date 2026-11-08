package ru.lbs.kml;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Маршруты из прямых участков: хранение, расчёты по участкам, экспорт в KML. */
final class Routes {

    static final int[] COLORS = {0xFF00E5FF, 0xFFFFB300, 0xFF76FF03, 0xFFFF4081, 0xFFFFFFFF, 0xFF7C4DFF};
    static final String[] COLOR_NAMES = {"Бирюзовый", "Янтарный", "Салатовый", "Розовый", "Белый", "Фиолетовый"};

    static final class Route {
        String id, name;
        int color = COLORS[0];
        boolean visible = true;
        long created;
        final List<double[]> pts = new ArrayList<>(); // {lat, lon}
    }

    /** Участок: длина, азимут, дирекционный угол, нарастающий итог. */
    static final class Leg {
        double dist, az, grid, total;
    }

    private Routes() {}

    static List<Leg> legs(List<double[]> pts) {
        List<Leg> out = new ArrayList<>();
        double total = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double[] inv = Geo.inverse(a[0], a[1], b[0], b[1]);
            Leg l = new Leg();
            l.dist = inv[0];
            l.az = inv[1];
            l.grid = Geo.gridBearing(a[0], a[1], b[0], b[1]);
            total += inv[0];
            l.total = total;
            out.add(l);
        }
        return out;
    }

    static double length(List<double[]> pts) {
        double t = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            t += Geo.inverse(a[0], a[1], b[0], b[1])[0];
        }
        return t;
    }

    /** Таблица участков моноширинным текстом. */
    static String table(List<double[]> pts) {
        List<Leg> legs = legs(pts);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-6s %9s %7s %6s %9s%n", "Уч.", "Длина", "Азимут", "Д.у.", "Итого"));
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            sb.append(String.format(Locale.US, "%-6s %9s %6.1f° %6s %9s%n",
                    (i + 1) + "→" + (i + 2), Geo.dist(l.dist), l.az, Geo.mils(l.grid), Geo.dist(l.total)));
        }
        sb.append("\nТочки (СК-42):\n");
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            sb.append(String.format(Locale.US, "%2d  %s%n", i + 1, Sk42.format(p[0], p[1])));
        }
        return sb.toString();
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "routes.json");
    }

    static synchronized List<Route> load(Context c) {
        List<Route> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, k;
            while (off < b.length && (k = in.read(b, off, b.length - off)) > 0) off += k;
            JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Route r = new Route();
                r.id = o.optString("id");
                r.name = o.optString("name");
                r.color = o.optInt("color", COLORS[0]);
                r.visible = o.optBoolean("visible", true);
                r.created = o.optLong("created");
                JSONArray p = o.getJSONArray("pts");
                for (int j = 0; j < p.length(); j++) {
                    JSONArray q = p.getJSONArray(j);
                    r.pts.add(new double[]{q.getDouble(0), q.getDouble(1)});
                }
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static synchronized void save(Context c, List<Route> routes) throws Exception {
        JSONArray arr = new JSONArray();
        for (Route r : routes) {
            JSONObject o = new JSONObject();
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("color", r.color);
            o.put("visible", r.visible);
            o.put("created", r.created);
            JSONArray p = new JSONArray();
            for (double[] q : r.pts) {
                JSONArray a = new JSONArray();
                a.put(q[0]);
                a.put(q[1]);
                p.put(a);
            }
            o.put("pts", p);
            arr.put(o);
        }
        File tmp = new File(c.getFilesDir(), "routes.json.tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file(c))) throw new Exception("не удалось сохранить маршруты");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String kmlColor(int argb) {
        return String.format(Locale.US, "ff%02x%02x%02x", argb & 0xFF, (argb >> 8) & 0xFF, (argb >> 16) & 0xFF);
    }

    /** KML: линия маршрута + контрольные точки КТ1, КТ2… с азимутами в описании. */
    static String toKml(Route r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>").append(esc(r.name)).append("</name>\n")
          .append("<Placemark><name>").append(esc(r.name)).append("</name><description>")
          .append(esc("Длина: " + Geo.dist(length(r.pts)) + ", участков: " + Math.max(0, r.pts.size() - 1)))
          .append("</description><Style><LineStyle><color>").append(kmlColor(r.color))
          .append("</color><width>4</width></LineStyle></Style><LineString><coordinates>");
        for (int i = 0; i < r.pts.size(); i++) {
            double[] p = r.pts.get(i);
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%.7f,%.7f,0", p[1], p[0]));
        }
        sb.append("</coordinates></LineString></Placemark>\n");
        List<Leg> legs = legs(r.pts);
        for (int i = 0; i < r.pts.size(); i++) {
            double[] p = r.pts.get(i);
            String d = "СК-42: " + Sk42.format(p[0], p[1]);
            if (i < legs.size()) {
                Leg l = legs.get(i);
                d += "\nДалее: " + Geo.dist(l.dist) + ", аз. " + String.format(Locale.US, "%.1f°", l.az)
                        + ", д.у. " + Geo.mils(l.grid);
            }
            sb.append("<Placemark><name>КТ").append(i + 1).append("</name><description>").append(esc(d))
              .append("</description><Point><coordinates>")
              .append(String.format(Locale.US, "%.7f,%.7f,0", p[1], p[0]))
              .append("</coordinates></Point></Placemark>\n");
        }
        sb.append("</Document></kml>\n");
        return sb.toString();
    }
}
