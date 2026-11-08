package ru.lbs.kml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {

    private static final int MAX_TRIES = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private Button btnUpdate, btnOpen, btnShare;
    private CheckBox autoCheck;
    private String script;
    private boolean running;
    private int tries;
    private Uri lastUri;

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
        autoCheck = findViewById(R.id.autoCheck);

        try {
            script = LbsCore.readAsset(this, "lbs.js");
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

        boolean auto = LbsCore.autoEnabled(this);
        autoCheck.setChecked(auto);
        safeSchedule(auto);
        autoCheck.setOnCheckedChangeListener((b, on) -> {
            safeSchedule(on);
            Toast.makeText(this, on ? "Автопроверка включена" : "Автопроверка выключена",
                    Toast.LENGTH_SHORT).show();
        });

        btnUpdate.setOnClickListener(v -> startUpdate());
        btnOpen.setOnClickListener(v -> openFile());
        btnShare.setOnClickListener(v -> shareFile());
    }

    private void safeSchedule(boolean on) {
        try {
            LbsCore.schedule(this, on);
            if (on) askNotificationPermission();
        } catch (Throwable e) {
            showError("Автопроверка не включилась", e);
        }
    }

    /** Если в прошлый раз приложение упало — показываем текст ошибки. */
    private boolean showSavedCrash() {
        SharedPreferences p = LbsCore.prefs(this);
        String crash = p.getString("crash", null);
        if (crash == null) return false;
        p.edit().remove("crash").apply();
        String text = "Приложение упало в прошлый раз. Пришлите этот текст:\n\n" + crash;
        status.setText(text);
        new AlertDialog.Builder(this)
                .setTitle("Ошибка в прошлый раз")
                .setMessage(text)
                .setPositiveButton("Скопировать", (d, w) -> copy(crash))
                .setNegativeButton("Закрыть", null)
                .show();
        return true;
    }

    private void showError(String title, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        status.setText(title + ":\n" + sw);
    }

    private void copy(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (showSavedCrash()) return;
        if (!running) showLastFromPrefs();
    }

    /** Показывает последний сохранённый файл (в т.ч. сделанный фоновой проверкой). */
    private void showLastFromPrefs() {
        SharedPreferences p = LbsCore.prefs(this);
        String last = p.getString("lastUri", null);
        if (last == null) return;
        lastUri = Uri.parse(last);
        btnOpen.setEnabled(true);
        btnShare.setEnabled(true);
        StringBuilder sb = new StringBuilder("Последний файл: ").append(p.getString("lastName", ""));
        String date = p.getString("lastDate", null);
        if (date != null) sb.append("\nСнимок DeepState: ").append(date);
        long checked = p.getLong("lastCheck", 0);
        if (checked > 0) sb.append("\nПроверено: ").append(DateFormat.format("dd.MM HH:mm", checked));
        status.setText(sb);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
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
        web.loadUrl(LbsCore.SOURCE_URL);
    }

    private void extract() {
        if (!running) return;
        web.evaluateJavascript(script, this::onResult);
    }

    private void onResult(String raw) {
        if (!running) return;
        String res = LbsCore.decode(raw);
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
                LbsCore.Result r = LbsCore.buildAndSave(getApplicationContext(), data);
                boolean isNew = LbsCore.remember(getApplicationContext(), r);
                handler.post(() -> onSaved(r, isNew));
            } catch (Exception e) {
                handler.post(() -> finishWithError("Не удалось сохранить файл: " + e.getMessage()));
            }
        }).start();
    }

    private void onSaved(LbsCore.Result r, boolean isNew) {
        lastUri = r.uri;
        btnUpdate.setEnabled(true);
        btnOpen.setEnabled(true);
        btnShare.setEnabled(true);
        status.setText("Готово: " + r.name + " (Загрузки)\n" +
                "Снимок DeepState: " + r.date + "\n" +
                "Линий: " + r.lines +
                (isNew ? "" : "\nНовых данных с прошлого раза нет."));
    }

    private void finishWithError(String msg) {
        running = false;
        btnUpdate.setEnabled(true);
        status.setText("Ошибка: " + msg);
    }

    private void openFile() {
        if (lastUri == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastUri, LbsCore.KML_MIME);
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
        i.setType(LbsCore.KML_MIME);
        i.putExtra(Intent.EXTRA_STREAM, lastUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Отправить файл ЛБС"));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
