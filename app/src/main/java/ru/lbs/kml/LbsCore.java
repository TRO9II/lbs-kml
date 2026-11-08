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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
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
        int changes = -1;
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

    static boolean optGray(Context c) { return prefs(c).getBoolean("optGray", true); }
    static boolean optDiff(Context c) { return prefs(c).getBoolean("optDiff", true); }

    static Result buildAndSave(Context c, String data) throws Exception {
        KmlBuilder.Parsed p = KmlBuilder.parse(data);
        if (p.lines.isEmpty()) throw new Exception("пустая линия фронта");

        List<double[][]> base = updateHistory(c, p);
        List<double[][]> added = Collections.emptyList(), removed = Collections.emptyList();
        int changes = -1; // -1 = не с чем сравнивать
        if (base != null) {
            added = KmlBuilder.diff(p.lines, base, KmlBuilder.DIFF_KM);
            removed = KmlBuilder.diff(base, p.lines, KmlBuilder.DIFF_KM);
            changes = added.size() + removed.size();
        }
        boolean diff = optDiff(c);
        String kml = KmlBuilder.build(p.lines,
                optGray(c) ? p.gray : null,
                diff ? added : null,
                diff ? removed : null,
                p.date);

        String name = fileName(p.date);
        Uri uri = saveToDownloads(c, name, kml.getBytes(StandardCharsets.UTF_8));

        Result r = new Result();
        r.name = name;
        r.date = p.date;
        r.id = p.id;
        r.lines = p.lines.size();
        r.changes = changes;
        r.uri = uri;
        return r;
    }

    /**
     * Хранит текущий и предыдущий снимок линии во внутренней памяти.
     * Возвращает линии предыдущего снимка (или null, если его ещё нет).
     */
    private static synchronized List<double[][]> updateHistory(Context c, KmlBuilder.Parsed p) throws Exception {
        File cur = new File(c.getFilesDir(), "cur.txt");
        File base = new File(c.getFilesDir(), "base.txt");
        String curId = cur.exists() ? firstLine(cur) : null;
        if (curId != null && !curId.equals(p.id)) {
            if (base.exists() && !base.delete()) throw new Exception("не удалось обновить историю");
            if (!cur.renameTo(base)) throw new Exception("не удалось обновить историю");
        }
        writeText(cur, p.id + "\n" + KmlBuilder.serializeLines(p.lines));
        if (!base.exists()) return null;
        String text = readText(base);
        int nl = text.indexOf('\n');
        return KmlBuilder.parse(nl >= 0 ? text.substring(nl + 1) : "").lines;
    }

    private static String firstLine(File f) throws Exception {
        String t = readText(f);
        int nl = t.indexOf('\n');
        return (nl >= 0 ? t.substring(0, nl) : t).trim();
    }

    private static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void writeText(File f, String s) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
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
                .putInt("lastChanges", r.changes)
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
        try {
            js.schedule(job(c, true));
        } catch (SecurityException e) {
            // нет разрешения на условие «есть сеть» — планируем без него
            js.schedule(job(c, false));
        }
    }

    private static JobInfo job(Context c, boolean needNetwork) {
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, LbsJob.class))
                .setPeriodic(PERIOD_MS, FLEX_MS)
                .setPersisted(true);
        if (needNetwork) b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        return b.build();
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Новая ЛБС",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Уведомления о новом снимке DeepStateMap");
        nm.createNotificationChannel(ch);
    }

    /** Текст про изменения для экрана и уведомления. */
    static String changesText(int changes) {
        if (changes < 0) return "Изменения появятся со следующим снимком";
        if (changes == 0) return "Линия не изменилась";
        int m10 = changes % 10, m100 = changes % 100;
        String w = (m10 == 1 && m100 != 11) ? "участок" :
                (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "участка" : "участков";
        return "Изменилось: " + changes + " " + w;
    }
}
