package ru.lbs.kml;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Архив спутниковых снимков Esri World Imagery Wayback: все выпуски с 2014 года.
 * Находит выпуски, в которых снимок в заданной точке действительно другой, и даты съёмки.
 */
final class Wayback {

    static final String CONFIG = "https://s3-us-west-2.amazonaws.com/config.maptiles.arcgis.com/waybackconfig.json";

    static final class Release {
        int num;
        String title, date, url, meta;
    }

    static final class Version {
        Release rel;
        String captured = "", res = "", provider = "";
    }

    interface Progress {
        void on(String text);
    }

    private Wayback() {}

    static String http(Context c, String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", c.getPackageName() + " (https://github.com/TRO9II/lbs-kml)");
        con.setConnectTimeout(12000);
        con.setReadTimeout(20000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } finally {
            con.disconnect();
        }
    }

    /** Список выпусков, новые первыми (кэш на сутки). */
    static List<Release> releases(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "wayback.json");
        String json;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24L * 3600 * 1000) {
            json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } else {
            json = new String(http(c, CONFIG).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            java.nio.file.Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        }
        JSONObject o = new JSONObject(json);
        List<Release> out = new ArrayList<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject v = o.getJSONObject(k);
            Release r = new Release();
            r.num = Integer.parseInt(k);
            r.title = v.optString("itemTitle");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(r.title);
            r.date = m.find() ? m.group(1) : "";
            r.url = v.optString("itemURL");
            r.meta = v.optString("metadataLayerUrl", "");
            out.add(r);
        }
        Collections.sort(out, (a, b) -> b.date.compareTo(a.date));
        return out;
    }

    /** Выпуски с разными снимками в точке (новые первыми). */
    static List<Version> scan(Context c, double lat, double lon, Progress pr) throws Exception {
        final List<Release> rel = releases(c);
        int z = 17, n = 1 << z;
        final int x = (int) Math.floor((lon + 180) / 360 * n);
        final int y = (int) Math.floor((1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n);
        final Map<Integer, String> hash = new ConcurrentHashMap<>();
        final ExecutorService ex = Executors.newFixedThreadPool(6);
        final int[] done = {0};
        try {
            // 1) выборка с шагом — параллельно
            int step = 8;
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < rel.size(); i += step) idx.add(i);
            if (idx.get(idx.size() - 1) != rel.size() - 1) idx.add(rel.size() - 1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i : idx) fs.add(ex.submit(() -> { tileHash(c, rel, i, x, y, hash); count(done, pr); }));
            for (Future<?> f : fs) f.get();
            // 2) уточнение границ — бисекция между соседними выборками, где снимок отличается
            List<Integer> changes = Collections.synchronizedList(new ArrayList<>());
            fs.clear();
            for (int k = 1; k < idx.size(); k++) {
                final int a = idx.get(k - 1), b = idx.get(k);
                fs.add(ex.submit(() -> {
                    bisect(c, rel, a, b, x, y, hash, changes, done, pr);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            List<Integer> starts = new ArrayList<>(changes);
            starts.add(0);
            Collections.sort(starts);
            // 3) даты съёмки
            List<Version> out = new ArrayList<>();
            List<Future<Version>> vf = new ArrayList<>();
            for (int i : starts) {
                if (hash.get(i) == null || hash.get(i).isEmpty()) continue;
                final Release r = rel.get(i);
                vf.add(ex.submit(() -> describe(c, r, lat, lon)));
            }
            for (Future<Version> f : vf) out.add(f.get());
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    private static void count(int[] done, Progress pr) {
        synchronized (done) {
            done[0]++;
            if (pr != null) pr.on("Сравниваю снимки разных лет… " + done[0]);
        }
    }

    private static void bisect(Context c, List<Release> rel, int a, int b, int x, int y, Map<Integer, String> hash,
                               List<Integer> changes, int[] done, Progress pr) throws Exception {
        if (tileHash(c, rel, a, x, y, hash).equals(tileHash(c, rel, b, x, y, hash))) return;
        if (b - a == 1) {
            changes.add(b);
            return;
        }
        int m = (a + b) / 2;
        tileHash(c, rel, m, x, y, hash);
        count(done, pr);
        bisect(c, rel, a, m, x, y, hash, changes, done, pr);
        bisect(c, rel, m, b, x, y, hash, changes, done, pr);
    }

    private static String tileHash(Context c, List<Release> rel, int i, int x, int y, Map<Integer, String> hash) {
        String h = hash.get(i);
        if (h != null) return h;
        try {
            String u = rel.get(i).url.replace("{level}", "17").replace("{row}", Integer.toString(y)).replace("{col}", Integer.toString(x));
            byte[] b = http(c, u).getBytes(StandardCharsets.ISO_8859_1);
            byte[] d = MessageDigest.getInstance("MD5").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 6; k++) sb.append(String.format("%02x", d[k]));
            h = sb.toString();
        } catch (Exception e) {
            h = ""; // нет снимка в этом выпуске
        }
        hash.put(i, h);
        return h;
    }

    private static Version describe(Context c, Release r, double lat, double lon) {
        Version v = new Version();
        v.rel = r;
        if (r.meta.isEmpty()) return v;
        try {
            String u = r.meta + String.format(Locale.US,
                    "/identify?geometry=%.6f,%.6f&geometryType=esriGeometryPoint&sr=4326&layers=all&tolerance=0"
                            + "&mapExtent=%.6f,%.6f,%.6f,%.6f&imageDisplay=100,100,96&returnGeometry=false&f=json",
                    lon, lat, lon - 0.001, lat - 0.001, lon + 0.001, lat + 0.001);
            JSONArray res = new JSONObject(new String(http(c, u).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))
                    .optJSONArray("results");
            if (res != null && res.length() > 0) {
                JSONObject a = res.getJSONObject(0).getJSONObject("attributes");
                v.captured = fmtDate(a.opt("SRC_DATE2") != null ? a.opt("SRC_DATE2") : a.opt("SRC_DATE"));
                Object sr = a.opt("SAMP_RES") != null ? a.opt("SAMP_RES") : a.opt("SRC_RES");
                v.res = sr == null || "Null".equals(String.valueOf(sr)) ? "" : String.valueOf(sr).replace('.', ',') + " м";
                Object pv = a.opt("NICE_DESC") != null ? a.opt("NICE_DESC") : a.opt("SRC_DESC");
                v.provider = pv == null || "Null".equals(String.valueOf(pv)) ? "" : String.valueOf(pv);
            }
        } catch (Exception ignored) {
        }
        return v;
    }

    /** "9/7/2024" или миллисекунды → "07.09.2024". */
    static String fmtDate(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        try {
            if (s.matches("\\d{9,}")) return new java.text.SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new java.util.Date(Long.parseLong(s)));
            String[] p = s.split("/");
            if (p.length == 3) return String.format(Locale.US, "%02d.%02d.%s", Integer.parseInt(p[1].trim()), Integer.parseInt(p[0].trim()), p[2].trim().substring(0, 4));
        } catch (Exception ignored) {
        }
        return s;
    }
}
