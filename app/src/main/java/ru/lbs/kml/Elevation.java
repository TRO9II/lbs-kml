package ru.lbs.kml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Высота над уровнем моря по открытым тайлам рельефа Terrain Tiles (AWS, формат Terrarium; на Украине — SRTM ~30 м).
 * Точность ±5–10 м. Тайлы кэшируются на телефоне — однажды загруженный район работает без интернета.
 */
final class Elevation {

    interface Callback {
        void on(double meters); // NaN — неизвестно
    }

    private static final int Z = 14;
    private static final String URL_TPL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png";
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final Map<String, int[]> MEM = new LinkedHashMap<String, int[]>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
            return size() > 24;
        }
    };

    private Elevation() {}

    static void get(Context c, double lat, double lon, Callback cb) {
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            double v = sync(app, lat, lon);
            UI.post(() -> cb.on(v));
        });
    }

    /** Высота с билинейной интерполяцией внутри тайла. */
    static double sync(Context c, double lat, double lon) {
        try {
            int n = 1 << Z;
            double xf = (lon + 180) / 360 * n;
            double yf = (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n;
            int tx = (int) Math.floor(xf), ty = (int) Math.floor(yf);
            int[] px = tile(c, tx, ty);
            if (px == null) return Double.NaN;
            double fx = (xf - tx) * 256 - 0.5, fy = (yf - ty) * 256 - 0.5;
            fx = Math.max(0, Math.min(255, fx));
            fy = Math.max(0, Math.min(255, fy));
            int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(255, x0 + 1), y1 = Math.min(255, y0 + 1);
            double ax = fx - x0, ay = fy - y0;
            double h00 = h(px[y0 * 256 + x0]), h10 = h(px[y0 * 256 + x1]);
            double h01 = h(px[y1 * 256 + x0]), h11 = h(px[y1 * 256 + x1]);
            return (h00 * (1 - ax) + h10 * ax) * (1 - ay) + (h01 * (1 - ax) + h11 * ax) * ay;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Terrarium: (R·256 + G + B/256) − 32768. */
    private static double h(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r * 256.0 + g + b / 256.0 - 32768.0;
    }

    private static int[] tile(Context c, int x, int y) throws Exception {
        String key = Z + "_" + x + "_" + y;
        synchronized (MEM) {
            int[] m = MEM.get(key);
            if (m != null) return m;
        }
        File dir = new File(c.getFilesDir(), "elev");
        File f = new File(dir, key + ".png");
        byte[] bytes;
        if (f.exists() && f.length() > 0) {
            bytes = java.nio.file.Files.readAllBytes(f.toPath());
        } else {
            HttpURLConnection con = (HttpURLConnection) new URL(String.format(java.util.Locale.US, URL_TPL, Z, x, y)).openConnection();
            con.setRequestProperty("User-Agent", c.getPackageName());
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int k;
                while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                bytes = bo.toByteArray();
            }
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(bytes);
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPremultiplied = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (b == null || b.getWidth() != 256) return null;
        int[] px = new int[256 * 256];
        b.getPixels(px, 0, 256, 0, 0, 256, 256);
        b.recycle();
        synchronized (MEM) {
            MEM.put(key, px);
        }
        return px;
    }

    /** "186 м" */
    static String fmt(double m) {
        return Double.isNaN(m) ? "—" : Math.round(m) + " м";
    }
}
