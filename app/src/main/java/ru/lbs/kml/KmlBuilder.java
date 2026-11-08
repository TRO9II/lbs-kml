package ru.lbs.kml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Чистая логика без Android: разбор данных, сравнение снимков, сборка KML. */
final class KmlBuilder {

    /** Порог изменения линии, км. */
    static final double DIFF_KM = 0.3;

    private KmlBuilder() {}

    static final class Parsed {
        String date = "", id = "";
        final List<double[][]> lines = new ArrayList<>();
        final List<double[][]> gray = new ArrayList<>();
        final List<double[][]> added = new ArrayList<>();
        final List<double[][]> removed = new ArrayList<>();
    }

    /** Формат: "DATE дата|id", затем строки "L x,y x,y …" (линии) и "G x,y …" (серая зона). */
    static Parsed parse(String data) {
        Parsed p = new Parsed();
        String[] rows = data.split("\n");
        int start = 0;
        if (rows.length > 0 && rows[0].startsWith("DATE ")) {
            String head = rows[0].substring(5);
            int bar = head.lastIndexOf('|');
            if (bar >= 0) {
                p.date = head.substring(0, bar).trim();
                p.id = head.substring(bar + 1).trim();
            } else {
                p.date = head.trim();
            }
            start = 1;
        }
        for (int i = start; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.isEmpty()) continue;
            if (row.startsWith("G ")) {
                double[][] c = coords(row.substring(2));
                if (c.length >= 3) p.gray.add(c);
            } else if (row.startsWith("A ")) {
                double[][] c = coords(row.substring(2));
                if (c.length >= 2) p.added.add(c);
            } else if (row.startsWith("R ")) {
                double[][] c = coords(row.substring(2));
                if (c.length >= 2) p.removed.add(c);
            } else {
                double[][] c = coords(row.startsWith("L ") ? row.substring(2) : row);
                if (c.length >= 2) p.lines.add(c);
            }
        }
        return p;
    }

    static double[][] coords(String s) {
        String[] pts = s.trim().split(" ");
        List<double[]> out = new ArrayList<>(pts.length);
        for (String pt : pts) {
            String[] xyz = pt.split(",");
            if (xyz.length < 2) continue;
            try {
                out.add(new double[]{Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1])});
            } catch (NumberFormatException ignored) {
            }
        }
        return out.toArray(new double[0][]);
    }

    static String serializeLines(List<double[][]> lines) {
        return serialize("L ", lines);
    }

    static String serialize(String prefix, List<double[][]> list) {
        StringBuilder sb = new StringBuilder();
        if (list == null) return "";
        for (double[][] l : list) {
            sb.append(prefix);
            appendCoords(sb, l, false);
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------- Сравнение ----------

    private static final double KX = 111.32 * Math.cos(Math.toRadians(48.5)); // км на градус долготы
    private static final double KY = 110.57;                                   // км на градус широты
    private static final double CELL = 2.0;                                    // км

    private static long key(int ix, int iy) {
        return (((long) ix) << 32) ^ (iy & 0xffffffffL);
    }

    /**
     * Участки линий {@code now}, которые дальше порога от любой линии {@code base}.
     * Каждый участок включает соседние неизменные вершины, чтобы был виден «выступ».
     */
    static List<double[][]> diff(List<double[][]> now, List<double[][]> base, double thrKm) {
        Map<Long, List<double[]>> grid = new HashMap<>();
        for (double[][] l : base) {
            for (int j = 0; j < l.length - 1; j++) {
                double x1 = l[j][0] * KX, y1 = l[j][1] * KY, x2 = l[j + 1][0] * KX, y2 = l[j + 1][1] * KY;
                double[] seg = {x1, y1, x2, y2};
                int ax = (int) Math.floor(Math.min(x1, x2) / CELL), bx = (int) Math.floor(Math.max(x1, x2) / CELL);
                int ay = (int) Math.floor(Math.min(y1, y2) / CELL), by = (int) Math.floor(Math.max(y1, y2) / CELL);
                for (int ix = ax; ix <= bx; ix++)
                    for (int iy = ay; iy <= by; iy++)
                        grid.computeIfAbsent(key(ix, iy), k -> new ArrayList<>()).add(seg);
            }
        }
        List<double[][]> out = new ArrayList<>();
        for (double[][] l : now) {
            int n = l.length;
            boolean[] ch = new boolean[n];
            for (int i = 0; i < n; i++) ch[i] = !near(grid, l[i][0] * KX, l[i][1] * KY, thrKm);
            int i = 0;
            while (i < n) {
                if (!ch[i]) { i++; continue; }
                int j = i;
                while (j < n && ch[j]) j++;
                int from = Math.max(0, i - 1), to = Math.min(n - 1, j);
                if (to > from) {
                    double[][] part = new double[to - from + 1][];
                    System.arraycopy(l, from, part, 0, part.length);
                    out.add(part);
                }
                i = j;
            }
        }
        return out;
    }

    private static boolean near(Map<Long, List<double[]>> grid, double px, double py, double thr) {
        int cx = (int) Math.floor(px / CELL), cy = (int) Math.floor(py / CELL);
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++) {
                List<double[]> segs = grid.get(key(cx + a, cy + b));
                if (segs == null) continue;
                for (double[] s : segs) if (distToSeg(px, py, s) < thr) return true;
            }
        return false;
    }

    private static double distToSeg(double px, double py, double[] s) {
        double dx = s[2] - s[0], dy = s[3] - s[1];
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((px - s[0]) * dx + (py - s[1]) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double qx = s[0] + t * dx - px, qy = s[1] + t * dy - py;
        return Math.sqrt(qx * qx + qy * qy);
    }

    // ---------- KML ----------

    static String build(List<double[][]> lines, List<double[][]> gray,
                        List<double[][]> added, List<double[][]> removed, String date) {
        if (gray == null) gray = Collections.emptyList();
        if (added == null) added = Collections.emptyList();
        if (removed == null) removed = Collections.emptyList();

        StringBuilder sb = new StringBuilder(lines.size() * 2000 + gray.size() * 1500);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><Folder><name>Новая категория</name><open>1</open>")
          .append("<Style><ListStyle><listItemType>check</listItemType><bgColor>00ffffff</bgColor></ListStyle></Style>")
          .append("<Folder><name>Lines</name><open>1</open>\n");
        for (double[][] l : lines) line(sb, "Front line", "", "FF0000FF", 4, l);
        sb.append("</Folder>\n");

        if (!added.isEmpty() || !removed.isEmpty()) {
            String d = date == null ? "" : date.replace(" o ", " ");
            sb.append("<Folder><name>Изменения</name><open>1</open>\n");
            for (double[][] l : added) line(sb, "Новое положение", "Снимок " + d, "FF00FFFF", 6, l);
            for (double[][] l : removed) line(sb, "Прежнее положение", "До снимка " + d, "FFA0A0A0", 3, l);
            sb.append("</Folder>\n");
        }

        if (!gray.isEmpty()) {
            sb.append("<Folder><name>Серая зона</name><open>1</open>\n");
            for (double[][] g : gray) {
                sb.append("<Placemark><name>Серая зона</name><description>Статус неизвестен</description>")
                  .append("<Style><LineStyle><color>FF9E9E9E</color><width>1</width></LineStyle>")
                  .append("<PolyStyle><color>66A0A0A0</color></PolyStyle></Style>")
                  .append("<Polygon><outerBoundaryIs><LinearRing><coordinates>");
                appendCoords(sb, g, true);
                sb.append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n");
            }
            sb.append("</Folder>\n");
        }

        sb.append("</Folder></Document></kml>\n");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, String desc, String color, int width, double[][] l) {
        sb.append("<Placemark><name>").append(name).append("</name><description>").append(desc)
          .append("</description><Style><LineStyle><color>").append(color).append("</color><width>").append(width)
          .append("</width></LineStyle></Style><LineString><extrude>1</extrude><coordinates>");
        appendCoords(sb, l, false);
        sb.append("</coordinates></LineString></Placemark>\n");
    }

    /** lon,lat,0 через пробел; для полигона кольцо замыкается. */
    private static void appendCoords(StringBuilder sb, double[][] pts, boolean closeRing) {
        for (int j = 0; j < pts.length; j++) {
            if (j > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%.7f,%.7f,0", pts[j][0], pts[j][1]));
        }
        if (closeRing && pts.length > 0) {
            double[] a = pts[0], b = pts[pts.length - 1];
            if (a[0] != b[0] || a[1] != b[1])
                sb.append(' ').append(String.format(Locale.US, "%.7f,%.7f,0", a[0], a[1]));
        }
    }
}
