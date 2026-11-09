package ru.lbs.kml;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Свои слои из файлов (KML, KMZ, GPX — например, выгрузка из AlpineQuest): кодированные лесополосы,
 * квадраты, ориентиры. Слой не смешивается с точками и маршрутами — его можно включить, выключить, удалить.
 * Хранится в компактном двоичном виде в папке приложения, в саму программу ничего не встраивается.
 */
final class UserLayers {

    static final int POINT = 0, LINE = 1, POLY = 2;

    static final class Feature {
        int type;
        String name = "", note = "";
        int color, fill;   // ARGB; fill — заливка многоугольника (0 — нет)
        float width = 2;   // dp
        double[] lat, lon;
        double n = -90, s = 90, e = -180, w = 180;

        void bbox() {
            for (int i = 0; i < lat.length; i++) {
                n = Math.max(n, lat[i]); s = Math.min(s, lat[i]);
                e = Math.max(e, lon[i]); w = Math.min(w, lon[i]);
            }
        }
    }

    static final class Layer {
        String id, name;
        String kind = "vec"; // vec — линии и точки, pgd — растровая карта AlpineQuest
        float opacity = 1f;
        boolean visible = true;
        int count;
        List<Feature> features; // null — ещё не загружен с диска
        double n = -90, s = 90, e = -180, w = 180;
    }

    private UserLayers() {}

    // ---------- список слоёв ----------

    static List<Layer> list(Context c) {
        List<Layer> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(LbsCore.prefs(c).getString("userLayers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Layer l = new Layer();
                l.id = o.getString("id");
                l.name = o.optString("name", "Слой");
                l.visible = o.optBoolean("on", true);
                l.count = o.optInt("count");
                l.kind = o.optString("kind", "vec");
                l.opacity = (float) o.optDouble("op", 1);
                l.n = o.optDouble("n", -90); l.s = o.optDouble("s", 90);
                l.e = o.optDouble("e", -180); l.w = o.optDouble("w", 180);
                if ((l.kind.equals("pgd") ? pgdFile(c, l.id) : file(c, l.id)).exists()) out.add(l);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveList(Context c, List<Layer> layers) {
        JSONArray a = new JSONArray();
        try {
            for (Layer l : layers)
                a.put(new JSONObject().put("id", l.id).put("name", l.name).put("on", l.visible).put("count", l.count)
                        .put("kind", l.kind).put("op", l.opacity)
                        .put("n", l.n).put("s", l.s).put("e", l.e).put("w", l.w));
        } catch (Exception ignored) {
        }
        LbsCore.prefs(c).edit().putString("userLayers", a.toString()).apply();
    }

    static File file(Context c, String id) {
        File d = new File(c.getFilesDir(), "layers");
        d.mkdirs();
        return new File(d, id + ".bin");
    }

    static File pgdFile(Context c, String id) {
        File d = new File(c.getFilesDir(), "layers");
        d.mkdirs();
        return new File(d, id + ".pgd");
    }

    static void delete(Context c, Layer l) {
        file(c, l.id).delete();
        pgdFile(c, l.id).delete();
    }

    // ---------- двоичное хранение ----------

    static void write(Context c, Layer l) throws Exception {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file(c, l.id))))) {
            o.writeInt(1);
            o.writeInt(l.features.size());
            for (Feature f : l.features) {
                o.writeByte(f.type);
                o.writeUTF(cut(f.name, 200));
                o.writeUTF(cut(f.note, 1000));
                o.writeInt(f.color);
                o.writeInt(f.fill);
                o.writeFloat(f.width);
                o.writeInt(f.lat.length);
                for (int i = 0; i < f.lat.length; i++) {
                    o.writeFloat((float) f.lat[i]);
                    o.writeFloat((float) f.lon[i]);
                }
            }
        }
    }

    static List<Feature> read(Context c, String id) {
        List<Feature> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file(c, id)), 65536))) {
            in.readInt();
            int n = in.readInt();
            for (int k = 0; k < n; k++) {
                Feature f = new Feature();
                f.type = in.readByte();
                f.name = in.readUTF();
                f.note = in.readUTF();
                f.color = in.readInt();
                f.fill = in.readInt();
                f.width = in.readFloat();
                int m = in.readInt();
                f.lat = new double[m];
                f.lon = new double[m];
                for (int i = 0; i < m; i++) {
                    f.lat[i] = in.readFloat();
                    f.lon[i] = in.readFloat();
                }
                f.bbox();
                out.add(f);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ---------- разбор файлов ----------

    /** KML, KMZ (zip с doc.kml) или GPX → объекты слоя. */
    static List<Feature> parse(byte[] data) throws Exception {
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') data = unzipKml(data);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        String root = doc.getDocumentElement().getLocalName();
        if (root == null) root = doc.getDocumentElement().getNodeName();
        List<Feature> out = new ArrayList<>();
        if (root.equalsIgnoreCase("gpx")) parseGpx(doc, out);
        else parseKml(doc, out);
        return out;
    }

    private static byte[] unzipKml(byte[] zip) throws Exception {
        byte[] best = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry en;
            while ((en = z.getNextEntry()) != null) {
                String nm = en.getName().toLowerCase();
                if (!nm.endsWith(".kml") && !nm.endsWith(".gpx")) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int k;
                while ((k = z.read(buf)) > 0) {
                    bo.write(buf, 0, k);
                    if (bo.size() > 60 * 1024 * 1024) throw new Exception("файл слишком большой");
                }
                if (nm.endsWith("doc.kml") || best == null || bo.size() > best.length) best = bo.toByteArray();
            }
        }
        if (best == null) throw new Exception("в архиве нет KML");
        return best;
    }

    /** Стиль KML: цвет линии, толщина, заливка, цвет значка. */
    private static final class St {
        int line, poly, icon;
        float width = -1;
        boolean fill = true, outline = true;
    }

    private static void parseKml(Document doc, List<Feature> out) {
        Map<String, St> styles = new HashMap<>();
        NodeList sl = doc.getElementsByTagNameNS("*", "Style");
        for (int i = 0; i < sl.getLength(); i++) {
            Element s = (Element) sl.item(i);
            String id = s.getAttribute("id");
            if (!id.isEmpty()) styles.put(id, style(s));
        }
        Map<String, String> maps = new HashMap<>();
        NodeList sm = doc.getElementsByTagNameNS("*", "StyleMap");
        for (int i = 0; i < sm.getLength(); i++) {
            Element m = (Element) sm.item(i);
            NodeList pairs = m.getElementsByTagNameNS("*", "Pair");
            for (int k = 0; k < pairs.getLength(); k++) {
                Element p = (Element) pairs.item(k);
                if ("normal".equals(text(p, "key"))) maps.put(m.getAttribute("id"), text(p, "styleUrl").replace("#", ""));
            }
        }
        NodeList pms = doc.getElementsByTagNameNS("*", "Placemark");
        for (int i = 0; i < pms.getLength(); i++) {
            Element pm = (Element) pms.item(i);
            String name = directText(pm, "name");
            String note = directText(pm, "description").replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
            St st = null;
            NodeList inl = pm.getElementsByTagNameNS("*", "Style");
            if (inl.getLength() > 0) st = style((Element) inl.item(0));
            if (st == null) {
                String url = directText(pm, "styleUrl").replace("#", "");
                if (maps.containsKey(url)) url = maps.get(url);
                st = styles.get(url);
            }
            if (st == null) st = new St();

            NodeList pts = pm.getElementsByTagNameNS("*", "Point");
            for (int k = 0; k < pts.getLength(); k++) {
                double[][] c = coords(text((Element) pts.item(k), "coordinates"));
                if (c[0].length == 0) continue;
                Feature f = feature(POINT, name, note, c);
                f.color = st.icon != 0 ? st.icon : 0xFFFFFFFF;
                out.add(f);
            }
            NodeList lines = pm.getElementsByTagNameNS("*", "LineString");
            for (int k = 0; k < lines.getLength(); k++) {
                double[][] c = coords(text((Element) lines.item(k), "coordinates"));
                if (c[0].length < 2) continue;
                Feature f = feature(LINE, name, note, c);
                f.color = st.line != 0 ? st.line : 0xFF101010;
                f.width = st.width > 0 ? Math.min(8, st.width) : 2;
                out.add(f);
            }
            NodeList polys = pm.getElementsByTagNameNS("*", "Polygon");
            for (int k = 0; k < polys.getLength(); k++) {
                NodeList outer = ((Element) polys.item(k)).getElementsByTagNameNS("*", "outerBoundaryIs");
                Element src = outer.getLength() > 0 ? (Element) outer.item(0) : (Element) polys.item(k);
                double[][] c = coords(text(src, "coordinates"));
                if (c[0].length < 3) continue;
                Feature f = feature(POLY, name, note, c);
                f.color = st.outline ? (st.line != 0 ? st.line : 0xFF101010) : 0;
                f.fill = st.fill && st.poly != 0 ? st.poly : 0;
                f.width = st.width > 0 ? Math.min(8, st.width) : 1.6f;
                out.add(f);
            }
            NodeList tracks = pm.getElementsByTagNameNS("*", "Track");
            for (int k = 0; k < tracks.getLength(); k++) {
                NodeList cs = ((Element) tracks.item(k)).getElementsByTagNameNS("*", "coord");
                List<double[]> l = new ArrayList<>();
                for (int j = 0; j < cs.getLength(); j++) {
                    String[] p = cs.item(j).getTextContent().trim().split("\\s+");
                    try {
                        if (p.length >= 2) l.add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[0])});
                    } catch (Exception ignored) {
                    }
                }
                if (l.size() < 2) continue;
                double[][] c = new double[2][l.size()];
                for (int j = 0; j < l.size(); j++) { c[0][j] = l.get(j)[0]; c[1][j] = l.get(j)[1]; }
                Feature f = feature(LINE, name, note, c);
                f.color = st.line != 0 ? st.line : 0xFF101010;
                f.width = st.width > 0 ? Math.min(8, st.width) : 2;
                out.add(f);
            }
        }
    }

    private static St style(Element s) {
        St st = new St();
        Element ls = first(s, "LineStyle"), ps = first(s, "PolyStyle"), is = first(s, "IconStyle");
        if (ls != null) {
            st.line = kmlColor(text(ls, "color"));
            try {
                st.width = Float.parseFloat(text(ls, "width"));
            } catch (Exception ignored) {
            }
        }
        if (ps != null) {
            st.poly = kmlColor(text(ps, "color"));
            st.fill = !"0".equals(text(ps, "fill"));
            st.outline = !"0".equals(text(ps, "outline"));
        }
        if (is != null) st.icon = kmlColor(text(is, "color"));
        return st;
    }

    /** KML: aabbggrr → ARGB. */
    private static int kmlColor(String s) {
        if (s == null || s.length() < 8) return 0;
        try {
            long v = Long.parseLong(s.trim().substring(0, 8), 16);
            int a = (int) (v >> 24 & 0xFF), b = (int) (v >> 16 & 0xFF), g = (int) (v >> 8 & 0xFF), r = (int) (v & 0xFF);
            return a << 24 | r << 16 | g << 8 | b;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void parseGpx(Document doc, List<Feature> out) {
        NodeList wpts = doc.getElementsByTagNameNS("*", "wpt");
        for (int i = 0; i < wpts.getLength(); i++) {
            Element w = (Element) wpts.item(i);
            double[] ll = latLon(w);
            if (ll == null) continue;
            Feature f = feature(POINT, directText(w, "name"), directText(w, "desc"), new double[][]{{ll[0]}, {ll[1]}});
            f.color = 0xFFFFFFFF;
            out.add(f);
        }
        for (String tag : new String[]{"rte", "trk"}) {
            NodeList rs = doc.getElementsByTagNameNS("*", tag);
            for (int i = 0; i < rs.getLength(); i++) {
                Element r = (Element) rs.item(i);
                NodeList pts = r.getElementsByTagNameNS("*", tag.equals("rte") ? "rtept" : "trkpt");
                List<double[]> l = new ArrayList<>();
                for (int k = 0; k < pts.getLength(); k++) {
                    double[] ll = latLon((Element) pts.item(k));
                    if (ll != null) l.add(ll);
                }
                if (l.size() < 2) continue;
                double[][] c = new double[2][l.size()];
                for (int j = 0; j < l.size(); j++) { c[0][j] = l.get(j)[0]; c[1][j] = l.get(j)[1]; }
                Feature f = feature(LINE, directText(r, "name"), directText(r, "desc"), c);
                f.color = 0xFF101010;
                out.add(f);
            }
        }
    }

    private static Feature feature(int type, String name, String note, double[][] c) {
        Feature f = new Feature();
        f.type = type;
        f.name = name == null ? "" : name.trim();
        f.note = note == null ? "" : note;
        f.lat = c[0];
        f.lon = c[1];
        f.bbox();
        return f;
    }

    /** «lon,lat[,alt] …» → {lats, lons}. */
    private static double[][] coords(String s) {
        List<double[]> l = new ArrayList<>();
        if (s != null) for (String t : s.trim().split("\\s+")) {
            String[] p = t.split(",");
            if (p.length < 2) continue;
            try {
                l.add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[0])});
            } catch (NumberFormatException ignored) {
            }
        }
        double[][] c = new double[2][l.size()];
        for (int i = 0; i < l.size(); i++) { c[0][i] = l.get(i)[0]; c[1][i] = l.get(i)[1]; }
        return c;
    }

    private static double[] latLon(Element e) {
        try {
            return new double[]{Double.parseDouble(e.getAttribute("lat")), Double.parseDouble(e.getAttribute("lon"))};
        } catch (Exception ex) {
            return null;
        }
    }

    private static Element first(Element parent, String local) {
        NodeList nl = parent.getElementsByTagNameNS("*", local);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    private static String text(Element parent, String local) {
        NodeList nl = parent.getElementsByTagNameNS("*", local);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent().trim();
    }

    private static String directText(Element parent, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(n.getLocalName())) return n.getTextContent().trim();
        return "";
    }
}
