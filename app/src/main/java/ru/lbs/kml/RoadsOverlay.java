package ru.lbs.kml;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Только дороги — тонкими линиями поверх любой подложки (данные OpenStreetMap через Overpass).
 * Крупные дороги грузятся квадратами z9, все остальные — квадратами z12. Скачанное хранится на диске.
 */
public class RoadsOverlay extends Overlay {

    static final class Way {
        byte cls;
        float[] lat, lon;
        float n, s, e, w;
    }

    interface Status {
        void on(String text);
    }

    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"};
    private static final String MAJOR = "motorway|trunk|primary|secondary|motorway_link|trunk_link|primary_link|secondary_link";
    private static final String ALL = MAJOR + "|tertiary|tertiary_link|unclassified|residential|living_street|track";

    // классы: 0 магистраль, 1 главная, 2 второстепенная, 3 местная, 4 улица, 5 грунтовка
    private static final int[] COLOR = {0xFFFF6D00, 0xFFFFA000, 0xFFFFD54F, 0xFFFFF59D, 0xEEFFFFFF, 0xFFD7CCC8};
    private static final float[] WIDTH = {3.4f, 3.0f, 2.4f, 2.0f, 1.5f, 1.4f};
    private static final int[] MIN_ZOOM = {7, 8, 10, 12, 13, 13};

    private final Context app;
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dash;
    private final Path path = new Path();
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Map<String, List<Way>> mem = Collections.synchronizedMap(new LinkedHashMap<String, List<Way>>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Way>> e) {
            return size() > 40;
        }
    });
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> failedAt = Collections.synchronizedMap(new HashMap<>());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private volatile boolean offline;
    private volatile MapView view;
    private Status status;

    public RoadsOverlay(Context c, float density) {
        app = c.getApplicationContext();
        d = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        halo.set(line);
        halo.setColor(Color.argb(130, 0, 0, 0));
        dash = new DashPathEffect(new float[]{6 * density, 4 * density}, 0);
    }

    void setOffline(boolean off) { offline = off; }

    void setStatus(Status s) { status = s; }

    private void say(String t) {
        Status s = status;
        MapView v = view;
        if (s != null && v != null) v.post(() -> s.on(t));
    }

    // ---------- квадраты ----------

    private static int tx(double lon, int z) { return (int) Math.floor((lon + 180) / 360 * (1 << z)); }

    private static int ty(double lat, int z) {
        double r = Math.toRadians(Math.max(-85, Math.min(85, lat)));
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z));
    }

    private static double[] bbox(int x, int y, int z) {
        double n = 1 << z;
        double w = x / n * 360 - 180, e = (x + 1) / n * 360 - 180;
        double north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
        double south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))));
        return new double[]{south, w, north, e};
    }

    /** Ключи квадратов, нужных для области bb и масштаба zoom. */
    static List<String> keysFor(BoundingBox bb, double zoom, int limit) {
        List<String> out = new ArrayList<>();
        boolean detail = zoom >= 12.5;
        int z = detail ? 12 : 9;
        if (zoom < 7) return out;
        int x0 = tx(bb.getLonWest(), z), x1 = tx(bb.getLonEast(), z), y0 = ty(bb.getLatNorth(), z), y1 = ty(bb.getLatSouth(), z);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++) {
                if (out.size() >= limit) return out;
                out.add((detail ? "B_" : "A_") + x + "_" + y);
            }
        if (detail) { // крупные дороги на подложке тоже нужны, если квадрат z12 ещё не загружен
            for (int x = tx(bb.getLonWest(), 9); x <= tx(bb.getLonEast(), 9); x++)
                for (int y = ty(bb.getLatNorth(), 9); y <= ty(bb.getLatSouth(), 9); y++) out.add("A_" + x + "_" + y);
        }
        return out;
    }

    private File file(String key) {
        File dir = new File(app.getFilesDir(), "roads");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key + ".bin");
    }

    boolean cached(String key) {
        return mem.containsKey(key) || file(key).exists();
    }

    /** Поставить квадрат в очередь (из памяти → с диска → из сети). */
    void request(String key) {
        if (mem.containsKey(key) || pending.contains(key)) return;
        Long f = failedAt.get(key);
        if (f != null && System.currentTimeMillis() - f < 60_000) return;
        pending.add(key);
        File fl = file(key);
        if (fl.exists()) {
            disk.execute(() -> {
                try {
                    mem.put(key, read(fl));
                } catch (Exception e) {
                    fl.delete();
                } finally {
                    pending.remove(key);
                    MapView v = view;
                    if (v != null) v.postInvalidate();
                }
            });
            return;
        }
        if (offline) {
            pending.remove(key);
            return;
        }
        net.execute(() -> {
            try {
                say("Загружаю дороги…");
                List<Way> ws = fetch(key);
                write(fl, ws);
                mem.put(key, ws);
                failedAt.remove(key);
            } catch (Exception e) {
                failedAt.put(key, System.currentTimeMillis());
                say("Дороги не загрузились: " + e.getMessage());
            } finally {
                pending.remove(key);
                if (pending.isEmpty()) say(null);
                MapView v = view;
                if (v != null) v.postInvalidate();
            }
        });
    }

    /** Синхронно скачать квадрат (для сохранения района без сети). */
    void download(String key) throws Exception {
        if (file(key).exists()) return;
        write(file(key), fetch(key));
    }

    private List<Way> fetch(String key) throws Exception {
        String[] p = key.split("_");
        boolean detail = p[0].equals("B");
        double[] b = bbox(Integer.parseInt(p[1]), Integer.parseInt(p[2]), detail ? 12 : 9);
        String q = String.format(java.util.Locale.US,
                "[out:json][timeout:120];way[\"highway\"~\"^(%s)$\"](%.5f,%.5f,%.5f,%.5f);out geom qt;",
                detail ? ALL : MAJOR, b[0], b[1], b[2], b[3]);
        Exception last = null;
        for (String ep : ENDPOINTS) {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(ep).openConnection();
                con.setRequestProperty("User-Agent", app.getPackageName() + " (https://github.com/TRO9II/lbs-kml)");
                con.setConnectTimeout(15000);
                con.setReadTimeout(150000);
                con.setDoOutput(true);
                try (OutputStream os = con.getOutputStream()) {
                    os.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8));
                }
                if (con.getResponseCode() != 200) throw new Exception("сервер " + con.getResponseCode());
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = con.getInputStream()) {
                    byte[] buf = new byte[32768];
                    int k;
                    while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                }
                return parse(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("нет связи") : last;
    }

    private static byte cls(String h) {
        if (h.startsWith("motorway") || h.startsWith("trunk")) return 0;
        if (h.startsWith("primary")) return 1;
        if (h.startsWith("secondary")) return 2;
        if (h.startsWith("tertiary") || h.equals("unclassified")) return 3;
        if (h.equals("track")) return 5;
        return 4;
    }

    private static List<Way> parse(String json) throws Exception {
        JSONArray els = new JSONObject(json).getJSONArray("elements");
        List<Way> out = new ArrayList<>(els.length());
        for (int i = 0; i < els.length(); i++) {
            JSONObject o = els.getJSONObject(i);
            JSONArray g = o.optJSONArray("geometry");
            JSONObject tags = o.optJSONObject("tags");
            if (g == null || g.length() < 2 || tags == null) continue;
            Way w = new Way();
            w.cls = cls(tags.optString("highway"));
            int n = g.length();
            w.lat = new float[n];
            w.lon = new float[n];
            for (int k = 0; k < n; k++) {
                JSONObject q = g.getJSONObject(k);
                w.lat[k] = (float) q.getDouble("lat");
                w.lon[k] = (float) q.getDouble("lon");
            }
            bounds(w);
            out.add(w);
        }
        return out;
    }

    private static void bounds(Way w) {
        w.n = -90; w.s = 90; w.e = -180; w.w = 180;
        for (int k = 0; k < w.lat.length; k++) {
            w.n = Math.max(w.n, w.lat[k]); w.s = Math.min(w.s, w.lat[k]);
            w.e = Math.max(w.e, w.lon[k]); w.w = Math.min(w.w, w.lon[k]);
        }
    }

    private static void write(File f, List<Way> ws) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            o.writeInt(ws.size());
            for (Way w : ws) {
                o.writeByte(w.cls);
                o.writeInt(w.lat.length);
                for (int k = 0; k < w.lat.length; k++) {
                    o.writeFloat(w.lat[k]);
                    o.writeFloat(w.lon[k]);
                }
            }
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static List<Way> read(File f) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int n = in.readInt();
            List<Way> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Way w = new Way();
                w.cls = in.readByte();
                int m = in.readInt();
                w.lat = new float[m];
                w.lon = new float[m];
                for (int k = 0; k < m; k++) {
                    w.lat[k] = in.readFloat();
                    w.lon[k] = in.readFloat();
                }
                bounds(w);
                out.add(w);
            }
            return out;
        }
    }

    // ---------- рисование ----------

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        view = mv;
        double zoom = mv.getZoomLevelDouble();
        if (zoom < 7) return;
        BoundingBox bb = mv.getBoundingBox();
        List<String> keys = keysFor(bb, zoom, 12);
        Projection pj = mv.getProjection();
        float sN = (float) bb.getLatNorth(), sS = (float) bb.getLatSouth(), sE = (float) bb.getLonEast(), sW = (float) bb.getLonWest();
        float zf = (float) Math.max(0.75, Math.min(1.25, (zoom - 10) / 6 + 0.8));
        for (int pass = 0; pass < 2; pass++) {
            for (int cl = 5; cl >= 0; cl--) {
                if (zoom < MIN_ZOOM[cl]) continue;
                Paint p = pass == 0 ? halo : line;
                p.setStrokeWidth((WIDTH[cl] * zf + (pass == 0 ? 1.6f : 0)) * d);
                if (pass == 1) p.setColor(COLOR[cl]);
                p.setPathEffect(pass == 1 && cl == 5 ? dash : null);
                for (String k : keys) {
                    List<Way> ws = mem.get(k);
                    if (ws == null) {
                        request(k);
                        continue;
                    }
                    for (Way w : ws) {
                        if (w.cls != cl || w.n < sS || w.s > sN || w.e < sW || w.w > sE) continue;
                        path.reset();
                        for (int i = 0; i < w.lat.length; i++) {
                            gp.setCoords(w.lat[i], w.lon[i]);
                            pj.toPixels(gp, pt);
                            if (i == 0) path.moveTo(pt.x, pt.y);
                            else path.lineTo(pt.x, pt.y);
                        }
                        c.drawPath(path, p);
                    }
                }
            }
        }
    }

    @Override
    public void onDetach(MapView mv) {
        view = null;
        net.shutdownNow();
        disk.shutdownNow();
    }
}
