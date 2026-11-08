package ru.lbs.kml;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String SOURCE_URL = "https://deepstatemap.live/api/history/last";
    private static final String KML_MIME = "application/vnd.google-earth.kml+xml";
    private static final int MAX_TRIES = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private Button btnUpdate, btnOpen, btnShare;
    private String script;
    private boolean running;
    private int tries;
    private Uri lastUri;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.root));

        web = findViewById(R.id.web);
        status = findViewById(R.id.status);
        btnUpdate = findViewById(R.id.btnUpdate);
        btnOpen = findViewById(R.id.btnOpen);
        btnShare = findViewById(R.id.btnShare);
        prefs = getSharedPreferences("lbs", MODE_PRIVATE);

        try {
            script = readAsset("lbs.js");
        } catch (Exception e) {
            status.setText("Ошибка: не найден скрипт расчёта");
            btnUpdate.setEnabled(false);
            return;
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (running) handler.postDelayed(MainActivity.this::extract, 1500);
            }
        });

        String last = prefs.getString("lastUri", null);
        if (last != null) {
            lastUri = Uri.parse(last);
            btnOpen.setEnabled(true);
            btnShare.setEnabled(true);
            status.setText("Последний файл: " + prefs.getString("lastName", "") +
                    "\nНажмите «Обновить ЛБС» для проверки новых данных");
        }

        btnUpdate.setOnClickListener(v -> startUpdate());
        btnOpen.setOnClickListener(v -> openFile());
        btnShare.setOnClickListener(v -> shareFile());
    }

    /** Android 15+: контент рисуется под системными панелями — добавляем отступы. */
    private void applySystemBarInsets(View root) {
        final int pad = root.getPaddingTop();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = in.left; t = in.top; r = in.right; b = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(pad + l, pad + t, pad + r, pad + b);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void startUpdate() {
        running = true;
        tries = 0;
        btnUpdate.setEnabled(false);
        status.setText("Загружаю данные DeepStateMap…");
        web.loadUrl(SOURCE_URL);
    }

    private void extract() {
        if (!running) return;
        web.evaluateJavascript(script, this::onResult);
    }

    private void onResult(String raw) {
        String res = decode(raw);
        if (res == null || res.startsWith("WAIT")) {
            if (++tries < MAX_TRIES) {
                status.setText("Жду ответа сайта… (" + tries + ")");
                handler.postDelayed(this::extract, 2000);
            } else {
                finishWithError("Сайт не отдал данные. Проверьте интернет и попробуйте ещё раз.");
            }
            return;
        }
        if (res.startsWith("ERR")) {
            finishWithError(res.substring(3).trim());
            return;
        }
        running = false;
        status.setText("Строю файл…");
        final String data = res;
        new Thread(() -> {
            try {
                Result r = buildAndSave(data);
                handler.post(() -> onSaved(r));
            } catch (Exception e) {
                handler.post(() -> finishWithError("Не удалось сохранить файл: " + e.getMessage()));
            }
        }).start();
    }

    private static class Result {
        String name, date, id;
        int lines;
        Uri uri;
    }

    private Result buildAndSave(String data) throws Exception {
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
        Uri uri = saveToDownloads(name, sb.toString().getBytes(StandardCharsets.UTF_8));

        Result r = new Result();
        r.name = name;
        r.date = date;
        r.id = id;
        r.lines = count;
        r.uri = uri;
        return r;
    }

    /** "06.11 o 22:08" -> LBS_06_11_2026.kml */
    private static String fileName(String date) {
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

    private Uri saveToDownloads(String name, byte[] bytes) throws Exception {
        ContentResolver cr = getContentResolver();
        Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri uri = null;
        try (Cursor c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{name}, null)) {
            if (c != null && c.moveToFirst()) uri = ContentUris.withAppendedId(coll, c.getLong(0));
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

    private void onSaved(Result r) {
        String prevId = prefs.getString("lastId", "");
        boolean same = !r.id.isEmpty() && r.id.equals(prevId);
        prefs.edit()
                .putString("lastId", r.id)
                .putString("lastUri", r.uri.toString())
                .putString("lastName", r.name)
                .apply();
        lastUri = r.uri;
        btnUpdate.setEnabled(true);
        btnOpen.setEnabled(true);
        btnShare.setEnabled(true);
        status.setText("Готово: " + r.name + " (Загрузки)\n" +
                "Снимок DeepState: " + r.date + "\n" +
                "Линий: " + r.lines +
                (same ? "\nНовых данных с прошлого раза нет." : ""));
    }

    private void finishWithError(String msg) {
        running = false;
        btnUpdate.setEnabled(true);
        status.setText("Ошибка: " + msg);
    }

    private void openFile() {
        if (lastUri == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastUri, KML_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Открыть в…"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Нет приложения для KML (установите Google Earth)", Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile() {
        if (lastUri == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(KML_MIME);
        i.putExtra(Intent.EXTRA_STREAM, lastUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Отправить файл ЛБС"));
    }

    private static String decode(String raw) {
        if (raw == null || raw.equals("null")) return null;
        try {
            Object v = new JSONTokener(raw).nextValue();
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
