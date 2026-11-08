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
 * Только дороги — тонкими линиями поверх любой подложки.
 * Данные OpenStreetMap в виде векторных тайлов OpenFreeMap (быстрый CDN, без ключа). Скачанное хранится на диске.
 */
public class RoadsOverlay extends Overlay {

    interface Status {
        void on(String text);
    }

    private static final String TILEJSON = "https://tiles.openfreemap.org/planet";
    private volatile String tpl;

    // классы: 0 магистраль, 1 главная, 2 второстепенная, 3 местная, 4 улица, 5 грунтовка
    private static final int[] COLOR = {0xFFFF6D00, 0xFFFFA000, 0xFFFFD54F, 0xFFFFF59D, 0xEEFFFFFF, 0xFFD7CCC8};
    private static final float[] WIDTH = {3.4f, 3.0f, 2.4f, 2.0f, 1.5f, 1.4f};
    private static final int[] MIN_ZOOM = {0, 0, 0, 0, 11, 12};

    private final Context app;
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dash;
    private final Path path = new Path();
    private final Point pt = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final Map<String, List<Mvt.Line>> mem = Collections.synchronizedMap(new LinkedHashMap<String, List<Mvt.Line>>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Mvt.Line>> e) {
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

    /** Масштаб тайлов данных для масштаба карты (тайл на экране 512–1024 px). */
    static int dataZoom(double zoom) {
        return (int) Math.max(8, Math.min(14, Math.floor(zoom) - 1));
    }

    /** Ключи квадратов, нужных для области bb и масштаба zoom. */
    static List<String> keysFor(BoundingBox bb, double zoom, int limit) {
        List<String> out = new ArrayList<>();
        if (zoom < 8) return out;
        int z = dataZoom(zoom);
        int x0 = tx(bb.getLonWest(), z), x1 = tx(bb.getLonEast(), z), y0 = ty(bb.getLatNorth(), z), y1 = ty(bb.getLatSouth(), z);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++) {
                if (out.size() >= limit) return out;
                out.add("V" + z + "_" + x + "_" + y);
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
                if (pending.size() > 2) say("Загружаю дороги…");
                List<Mvt.Line> ws = fetch(key);
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

    private String template() throws Exception {
        if (tpl != null) return tpl;
        File f = new File(app.getFilesDir(), "roads/tilejson.txt");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3L * 24 * 3600 * 1000) {
            tpl = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (!tpl.isEmpty()) return tpl;
        }
        try {
            JSONObject o = new JSONObject(new String(get(TILEJSON), StandardCharsets.UTF_8));
            tpl = o.getJSONArray("tiles").getString(0);
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), tpl.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (f.exists()) tpl = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            else throw e;
        }
        return tpl;
    }

    private byte[] get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", app.getPackageName() + " (https://github.com/TRO9II/lbs-kml)");
        con.setConnectTimeout(12000);
        con.setReadTimeout(20000);
        int code = con.getResponseCode();
        if (code == 204 || code == 404) return new byte[0]; // пустой квадрат
        if (code != 200) throw new Exception("сервер " + code);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = con.getInputStream()) {
            byte[] buf = new byte[32768];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        }
        byte[] b = bo.toByteArray();
        if (b.length > 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) {
            try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(b))) {
                ByteArrayOutputStream o2 = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int k;
                while ((k = gz.read(buf)) > 0) o2.write(buf, 0, k);
                b = o2.toByteArray();
            }
        }
        return b;
    }

    private List<Mvt.Line> fetch(String key) throws Exception {
        String[] p = key.substring(1).split("_");
        int z = Integer.parseInt(p[0]), x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
        String url = template().replace("{z}", p[0]).replace("{x}", p[1]).replace("{y}", p[2]);
        byte[] b = get(url);
        return b.length == 0 ? new ArrayList<>() : Mvt.roads(b, z, x, y);
    }

    private static void bounds(Mvt.Line w) {
        w.n = -90; w.s = 90; w.e = -180; w.w = 180;
        for (int k = 0; k < w.lat.length; k++) {
            w.n = Math.max(w.n, w.lat[k]); w.s = Math.min(w.s, w.lat[k]);
            w.e = Math.max(w.e, w.lon[k]); w.w = Math.min(w.w, w.lon[k]);
        }
    }

    private static void write(File f, List<Mvt.Line> ws) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            o.writeInt(ws.size());
            for (Mvt.Line w : ws) {
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

    private static List<Mvt.Line> read(File f) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int n = in.readInt();
            List<Mvt.Line> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Mvt.Line w = new Mvt.Line();
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
        if (zoom < 8) return;
        BoundingBox bb = mv.getBoundingBox();
        List<String> keys = keysFor(bb, zoom, 24);
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
                    List<Mvt.Line> ws = mem.get(k);
                    if (ws == null) {
                        request(k);
                        continue;
                    }
                    for (Mvt.Line w : ws) {
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
