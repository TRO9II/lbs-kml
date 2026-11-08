package ru.lbs.kml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Минимальный разбор векторного тайла (Mapbox Vector Tile): только линии слоя transportation. */
final class Mvt {

    static final class Line {
        byte cls;
        float[] lat, lon;
        float n, s, e, w;
    }

    private Mvt() {}

    private static final class Buf {
        final byte[] b;
        int p;
        final int end;

        Buf(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }

        boolean more() { return p < end; }

        long varint() {
            long r = 0;
            int sh = 0;
            while (true) {
                int x = b[p++] & 0xFF;
                r |= (long) (x & 0x7F) << sh;
                if (x < 0x80) return r;
                sh += 7;
            }
        }

        void skip(int wire) {
            if (wire == 0) varint();
            else if (wire == 1) p += 8;
            else if (wire == 2) { int len = (int) varint(); p += len; }
            else if (wire == 5) p += 4;
            else throw new IllegalStateException("wire " + wire);
        }
    }

    /** Класс дороги OpenMapTiles → наш класс (-1 — не рисуем). */
    static byte cls(String c) {
        if (c == null) return -1;
        if (c.endsWith("_construction")) return -1;
        switch (c) {
            case "motorway": case "trunk": return 0;
            case "primary": return 1;
            case "secondary": return 2;
            case "tertiary": return 3;
            case "minor": case "service": return 4;
            case "track": return 5;
            default: return -1;
        }
    }

    static List<Line> roads(byte[] data, int z, int tx, int ty) {
        List<Line> out = new ArrayList<>();
        Buf t = new Buf(data, 0, data.length);
        while (t.more()) {
            long key = t.varint();
            int f = (int) (key >> 3), w = (int) (key & 7);
            if (f == 3 && w == 2) {
                int len = (int) t.varint();
                layer(data, t.p, t.p + len, z, tx, ty, out);
                t.p += len;
            } else t.skip(w);
        }
        return out;
    }

    private static void layer(byte[] b, int start, int end, int z, int tx, int ty, List<Line> out) {
        String name = null;
        List<String> keys = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        List<int[]> feats = new ArrayList<>(); // {start, end}
        int extent = 4096;
        Buf l = new Buf(b, start, end);
        while (l.more()) {
            long key = l.varint();
            int f = (int) (key >> 3), w = (int) (key & 7);
            if (w == 2) {
                int len = (int) l.varint();
                int s = l.p;
                if (f == 1) name = new String(b, s, len, StandardCharsets.UTF_8);
                else if (f == 2) feats.add(new int[]{s, s + len});
                else if (f == 3) keys.add(new String(b, s, len, StandardCharsets.UTF_8));
                else if (f == 4) vals.add(valueString(b, s, s + len));
                l.p = s + len;
            } else if (f == 5 && w == 0) {
                extent = (int) l.varint();
            } else l.skip(w);
        }
        if (!"transportation".equals(name)) return;
        int classKey = keys.indexOf("class");
        if (classKey < 0) return;
        double n = 1 << z;
        for (int[] fe : feats) {
            Buf fb = new Buf(b, fe[0], fe[1]);
            int type = 0, gs = -1, ge = -1;
            String cl = null;
            while (fb.more()) {
                long key = fb.varint();
                int f = (int) (key >> 3), w = (int) (key & 7);
                if (f == 2 && w == 2) {
                    int len = (int) fb.varint();
                    Buf tg = new Buf(b, fb.p, fb.p + len);
                    while (tg.more()) {
                        int k = (int) tg.varint(), v = (int) tg.varint();
                        if (k == classKey && v < vals.size()) cl = vals.get(v);
                    }
                    fb.p += len;
                } else if (f == 3 && w == 0) {
                    type = (int) fb.varint();
                } else if (f == 4 && w == 2) {
                    int len = (int) fb.varint();
                    gs = fb.p;
                    ge = fb.p + len;
                    fb.p += len;
                } else fb.skip(w);
            }
            byte c = cls(cl);
            if (type != 2 || c < 0 || gs < 0) continue;
            // геометрия: MoveTo / LineTo, координаты в долях тайла
            Buf g = new Buf(b, gs, ge);
            int cx = 0, cy = 0;
            List<float[]> cur = null;
            while (g.more()) {
                int cmd = (int) g.varint();
                int id = cmd & 7, cnt = cmd >> 3;
                if (id == 7) continue;
                for (int i = 0; i < cnt; i++) {
                    int dx = (int) g.varint(), dy = (int) g.varint();
                    cx += (dx >>> 1) ^ -(dx & 1);
                    cy += (dy >>> 1) ^ -(dy & 1);
                    double X = (tx + cx / (double) extent) / n, Y = (ty + cy / (double) extent) / n;
                    float lon = (float) (X * 360 - 180);
                    float lat = (float) Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * Y))));
                    if (id == 1) {
                        if (cur != null) add(out, cur, c);
                        cur = new ArrayList<>();
                    }
                    if (cur != null) cur.add(new float[]{lat, lon});
                }
            }
            if (cur != null) add(out, cur, c);
        }
    }

    private static void add(List<Line> out, List<float[]> pts, byte c) {
        if (pts.size() < 2) return;
        Line ln = new Line();
        ln.cls = c;
        ln.lat = new float[pts.size()];
        ln.lon = new float[pts.size()];
        ln.n = -90; ln.s = 90; ln.e = -180; ln.w = 180;
        for (int i = 0; i < pts.size(); i++) {
            ln.lat[i] = pts.get(i)[0];
            ln.lon[i] = pts.get(i)[1];
            ln.n = Math.max(ln.n, ln.lat[i]); ln.s = Math.min(ln.s, ln.lat[i]);
            ln.e = Math.max(ln.e, ln.lon[i]); ln.w = Math.min(ln.w, ln.lon[i]);
        }
        out.add(ln);
    }

    private static String valueString(byte[] b, int s, int e) {
        Buf v = new Buf(b, s, e);
        while (v.more()) {
            long key = v.varint();
            int f = (int) (key >> 3), w = (int) (key & 7);
            if (f == 1 && w == 2) {
                int len = (int) v.varint();
                return new String(b, v.p, len, StandardCharsets.UTF_8);
            }
            v.skip(w);
        }
        return "";
    }
}
