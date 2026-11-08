package ru.lbs.kml;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Общая логика: расчёт KML, сохранение, настройки, расписание. */
final class LbsCore {

    static final String SOURCE_URL = "https://deepstatemap.live/api/history/last";
    static final String KML_MIME = "application/vnd.google-earth.kml+xml";
    static final String CHANNEL = "lbs_updates";
    static final int JOB_ID = 1001;
    static final long PERIOD_MS = 12L * 60 * 60 * 1000;   // проверка каждые 12 часов
    static final long FLEX_MS = 2L * 60 * 60 * 1000;

    private LbsCore() {}

    static class Result {
        String name, date, id;
        int lines;
        Uri uri;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lbs", Context.MODE_PRIVATE);
    }

    static String readAsset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Результат evaluateJavascript приходит как JSON-литерал. */
    static String decode(String raw) {
        if (raw == null || raw.equals("null")) return null;
        try {
            Object v = new JSONTokener(raw).nextValue();
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Result buildAndSave(Context c, String data) throws Exception {
        String[] rows = data.split("\n");
        String head = rows[0].substring(5); // после "DATE "
        String date = head, id = "";
        int bar = head.lastIndexOf('|');
        if (bar >= 0) {
            date = head.substring(0, bar).trim();
            id = head.substring(bar + 1).trim();
        }

        StringBuilder sb = new StringBuilder(rows.length * 2000);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><Folder><name>Новая категория</name><open>1</open>")
          .append("<Style><ListStyle><listItemType>check</listItemType><bgColor>00ffffff</bgColor></ListStyle></Style>")
          .append("<Folder><name>Lines</name><open>1</open>\n");
        int count = 0;
        for (int i = 1; i < rows.length; i++) {
            String line = rows[i].trim();
            if (line.isEmpty()) continue;
            sb.append("<Placemark><name>Front line</name><description></description><Style><LineStyle><color>FF0000FF</color><width>4</width></LineStyle></Style><LineString><extrude>1</extrude><coordinates>");
            String[] pts = line.split(" ");
            for (int j = 0; j < pts.length; j++) {
                if (j > 0) sb.append(' ');
                sb.append(pts[j]).append(",0");
            }
            sb.append("</coordinates></LineString></Placemark>\n");
            count++;
        }
        sb.append("</Folder></Folder></Document></kml>\n");

        String name = fileName(date);
        Uri uri = saveToDownloads(c, name, sb.toString().getBytes(StandardCharsets.UTF_8));

        Result r = new Result();
        r.name = name;
        r.date = date;
        r.id = id;
        r.lines = count;
        r.uri = uri;
        return r;
    }

    /** Запоминает результат. Возвращает true, если снимок новый (id отличается от прошлого). */
    static boolean remember(Context c, Result r) {
        SharedPreferences p = prefs(c);
        String prevId = p.getString("lastId", "");
        boolean isNew = r.id.isEmpty() || !r.id.equals(prevId);
        p.edit()
                .putString("lastId", r.id)
                .putString("lastUri", r.uri.toString())
                .putString("lastName", r.name)
                .putString("lastDate", r.date)
                .putInt("lastLines", r.lines)
                .putLong("lastCheck", System.currentTimeMillis())
                .apply();
        return isNew;
    }

    /** "06.11 o 22:08" -> LBS_06_11_2026.kml */
    static String fileName(String date) {
        Calendar now = Calendar.getInstance();
        Matcher m = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})").matcher(date);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int year = now.get(Calendar.YEAR);
            if (month > now.get(Calendar.MONTH) + 1) year--; // снимок из прошлого года
            return String.format(Locale.US, "LBS_%02d_%02d_%d.kml", day, month, year);
        }
        return String.format(Locale.US, "LBS_%1$td_%1$tm_%1$tY.kml", now);
    }

    static Uri saveToDownloads(Context c, String name, byte[] bytes) throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri uri = null;
        try (Cursor cur = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{name}, null)) {
            if (cur != null && cur.moveToFirst()) uri = ContentUris.withAppendedId(coll, cur.getLong(0));
        }
        if (uri == null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, KML_MIME);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            uri = cr.insert(coll, cv);
            if (uri == null) throw new Exception("нет доступа к папке «Загрузки»");
        }
        try (OutputStream os = cr.openOutputStream(uri, "wt")) {
            if (os == null) throw new Exception("не удалось открыть файл");
            os.write(bytes);
        }
        return uri;
    }

    static boolean autoEnabled(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    /** Включает/выключает фоновую проверку. */
    static void schedule(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!on) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(c, LbsJob.class))
                .setPeriodic(PERIOD_MS, FLEX_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(ji);
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Новая ЛБС",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Уведомления о новом снимке DeepStateMap");
        nm.createNotificationChannel(ch);
    }
}
