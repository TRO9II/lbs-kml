package ru.lbs.kml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Импорт KML и GPX (AlpineQuest, Google Earth, навигаторы):
 * точки → свои точки, линии / маршруты / треки → маршруты.
 */
final class Importer {

    static final class Result {
        final List<UserPoints.Point> points = new ArrayList<>();
        final List<Routes.Route> routes = new ArrayList<>();
    }

    private static final int MAX_ROUTE_PTS = 800;

    private Importer() {}

    static Result parse(byte[] data, String nameHint) throws Exception {
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
        Result r = new Result();
        if (root.equalsIgnoreCase("gpx")) parseGpx(doc, r);
        else parseKml(doc, r);
        return r;
    }

    // ---------- KML ----------

    private static void parseKml(Document doc, Result r) {
        NodeList pms = doc.getElementsByTagNameNS("*", "Placemark");
        int idx = 0;
        for (int i = 0; i < pms.getLength(); i++) {
            Element pm = (Element) pms.item(i);
            String name = text(pm, "name");
            String desc = text(pm, "description");
            NodeList points = pm.getElementsByTagNameNS("*", "Point");
            if (name.matches("КТ\\d+")) continue; // контрольные точки нашего же экспорта маршрута
            for (int k = 0; k < points.getLength(); k++) {
                List<double[]> c = coords(text((Element) points.item(k), "coordinates"));
                if (c.isEmpty()) continue;
                r.points.add(point(name.isEmpty() ? "Точка " + (++idx) : name, desc, c.get(0)[0], c.get(0)[1]));
            }
            NodeList lines = pm.getElementsByTagNameNS("*", "LineString");
            for (int k = 0; k < lines.getLength(); k++) {
                List<double[]> c = coords(text((Element) lines.item(k), "coordinates"));
                if (c.size() >= 2) r.routes.add(route(name.isEmpty() ? "Линия " + (r.routes.size() + 1) : name, c));
            }
            // gx:Track (треки Google Earth / AQ)
            NodeList tracks = pm.getElementsByTagNameNS("*", "Track");
            for (int k = 0; k < tracks.getLength(); k++) {
                NodeList cs = ((Element) tracks.item(k)).getElementsByTagNameNS("*", "coord");
                List<double[]> c = new ArrayList<>();
                for (int j = 0; j < cs.getLength(); j++) {
                    String[] p = cs.item(j).getTextContent().trim().split("\\s+");
                    if (p.length >= 2) c.add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[0])});
                }
                if (c.size() >= 2) r.routes.add(route(name.isEmpty() ? "Трек " + (r.routes.size() + 1) : name, c));
            }
        }
    }

    /** "lon,lat[,alt] lon,lat…" → {lat, lon} */
    private static List<double[]> coords(String s) {
        List<double[]> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.trim().split("\\s+")) {
            String[] p = t.split(",");
            if (p.length < 2) continue;
            try {
                out.add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[0])});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ---------- GPX ----------

    private static void parseGpx(Document doc, Result r) {
        NodeList wpts = doc.getElementsByTagNameNS("*", "wpt");
        for (int i = 0; i < wpts.getLength(); i++) {
            Element w = (Element) wpts.item(i);
            double[] ll = latLon(w);
            if (ll == null) continue;
            String name = text(w, "name");
            r.points.add(point(name.isEmpty() ? "Точка " + (i + 1) : name, text(w, "desc"), ll[0], ll[1]));
        }
        NodeList rtes = doc.getElementsByTagNameNS("*", "rte");
        for (int i = 0; i < rtes.getLength(); i++) {
            Element rte = (Element) rtes.item(i);
            List<double[]> c = new ArrayList<>();
            NodeList pts = rte.getElementsByTagNameNS("*", "rtept");
            for (int k = 0; k < pts.getLength(); k++) {
                double[] ll = latLon((Element) pts.item(k));
                if (ll != null) c.add(ll);
            }
            String name = directText(rte, "name");
            if (c.size() >= 2) r.routes.add(route(name.isEmpty() ? "Маршрут " + (r.routes.size() + 1) : name, c));
        }
        NodeList trks = doc.getElementsByTagNameNS("*", "trk");
        for (int i = 0; i < trks.getLength(); i++) {
            Element trk = (Element) trks.item(i);
            List<double[]> c = new ArrayList<>();
            NodeList pts = trk.getElementsByTagNameNS("*", "trkpt");
            for (int k = 0; k < pts.getLength(); k++) {
                double[] ll = latLon((Element) pts.item(k));
                if (ll != null) c.add(ll);
            }
            String name = directText(trk, "name");
            if (c.size() >= 2) r.routes.add(route(name.isEmpty() ? "Трек " + (r.routes.size() + 1) : name, c));
        }
    }

    private static double[] latLon(Element e) {
        try {
            return new double[]{Double.parseDouble(e.getAttribute("lat")), Double.parseDouble(e.getAttribute("lon"))};
        } catch (Exception ex) {
            return null;
        }
    }

    // ---------- общее ----------

    private static String text(Element parent, String local) {
        NodeList nl = parent.getElementsByTagNameNS("*", local);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent().trim();
    }

    /** Текст прямого потомка (чтобы имя трека не подменилось именем его точки). */
    private static String directText(Element parent, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(n.getLocalName())) return n.getTextContent().trim();
        }
        return "";
    }

    private static UserPoints.Point point(String name, String note, double lat, double lon) {
        UserPoints.Point p = new UserPoints.Point();
        p.name = name.length() > 60 ? name.substring(0, 60) : name;
        p.note = note == null ? "" : note.replaceAll("<[^>]*>", " ").trim();
        if (p.note.length() > 300) p.note = p.note.substring(0, 300);
        p.lat = lat;
        p.lon = lon;
        p.color = 0xFF29B6F6;
        p.icon = "";
        return p;
    }

    /** Маршрут; слишком длинные треки прореживаются до MAX_ROUTE_PTS точек. */
    private static Routes.Route route(String name, List<double[]> pts) {
        Routes.Route r = new Routes.Route();
        r.name = name.length() > 60 ? name.substring(0, 60) : name;
        r.color = Routes.COLORS[1];
        if (pts.size() <= MAX_ROUTE_PTS) {
            r.pts.addAll(pts);
        } else {
            double step = (pts.size() - 1) / (double) (MAX_ROUTE_PTS - 1);
            for (int i = 0; i < MAX_ROUTE_PTS; i++) r.pts.add(pts.get((int) Math.round(i * step)));
        }
        return r;
    }
}
