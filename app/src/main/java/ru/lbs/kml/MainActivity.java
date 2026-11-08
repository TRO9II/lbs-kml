package ru.lbs.kml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {

    private static final int MAX_TRIES = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView tvDate, tvInfo, tvState;
    private View stateRow;
    private ProgressBar progress;
    private Button btnUpdate, btnOpen, btnShare, btnMap;
    private Switch autoCheck, optDiff, optGray;
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
        tvDate = findViewById(R.id.tvDate);
        tvInfo = findViewById(R.id.tvInfo);
        tvState = findViewById(R.id.tvState);
        stateRow = findViewById(R.id.stateRow);
        progress = findViewById(R.id.progress);
        btnUpdate = findViewById(R.id.btnUpdate);
        btnOpen = findViewById(R.id.btnOpen);
        btnShare = findViewById(R.id.btnShare);
        btnMap = findViewById(R.id.btnMap);
        btnMap.setOnClickListener(v -> finish());
        autoCheck = findViewById(R.id.autoCheck);
        optDiff = findViewById(R.id.optDiff);
        optGray = findViewById(R.id.optGray);
        optDiff.setChecked(LbsCore.optDiff(this));
        optGray.setChecked(LbsCore.optGray(this));
        optDiff.setOnCheckedChangeListener((b, on) -> optionChanged("optDiff", on));
        optGray.setOnCheckedChangeListener((b, on) -> optionChanged("optGray", on));
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText("v" + ver);
        } catch (Exception ignored) {
        }

        try {
            script = LbsCore.readAsset(this, "lbs.js");
        } catch (Exception e) {
            showState("Ошибка: не найден скрипт расчёта", false);
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

    private void optionChanged(String key, boolean on) {
        LbsCore.prefs(this).edit().putBoolean(key, on).apply();
        Toast.makeText(this, "Применится при следующем «Обновить ЛБС»", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSavedCrash();
        if (!running) showLastFromPrefs();
    }

    // ---------- Отображение ----------

    /** Строка состояния под карточкой: с крутилкой (busy) или без. */
    private void showState(CharSequence text, boolean busy) {
        stateRow.setVisibility(View.VISIBLE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvState.setText(text);
    }

    private void hideState() {
        stateRow.setVisibility(View.GONE);
    }

    /** "06.11 o 22:08" -> "06.11, 22:08" */
    private static String prettyDate(String d) {
        return d == null ? "" : d.replace(" o ", ", ").trim();
    }

    private static String linesWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return n + " линия";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return n + " линии";
        return n + " линий";
    }

    /** Показывает последний сохранённый файл (в т.ч. сделанный фоновой проверкой). */
    private void showLastFromPrefs() {
        SharedPreferences p = LbsCore.prefs(this);
        String last = p.getString("lastUri", null);
        if (last == null) return;
        lastUri = Uri.parse(last);
        btnOpen.setEnabled(true);
        btnShare.setEnabled(true);

        String date = p.getString("lastDate", null);
        tvDate.setText(date != null ? prettyDate(date) : "—");

        StringBuilder sb = new StringBuilder(p.getString("lastName", ""));
        int lines = p.getInt("lastLines", 0);
        if (lines > 0) sb.append(" · ").append(linesWord(lines));
        if (p.contains("lastChanges")) sb.append("\n").append(LbsCore.changesText(p.getInt("lastChanges", -1)));
        long checked = p.getLong("lastCheck", 0);
        if (checked > 0) sb.append("\nПроверено ").append(DateFormat.format("dd.MM в HH:mm", checked));
        tvInfo.setText(sb);
    }

    // ---------- Ошибки ----------

    private void safeSchedule(boolean on) {
        try {
            LbsCore.schedule(this, on);
            if (on) askNotificationPermission();
        } catch (Throwable e) {
            showError("Автопроверка не включилась", e);
        }
    }

    /** Если в прошлый раз приложение упало — предлагаем скопировать текст ошибки. */
    private void showSavedCrash() {
        SharedPreferences p = LbsCore.prefs(this);
        String crash = p.getString("crash", null);
        if (crash == null) return;
        p.edit().remove("crash").apply();
        new AlertDialog.Builder(this)
                .setTitle("Приложение упало в прошлый раз")
                .setMessage("Скопируйте текст ошибки и пришлите разработчику.")
                .setPositiveButton("Скопировать", (d, w) -> copy(crash))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showError(String title, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        final String full = title + ":\n" + sw;
        showState(title + ". Нажмите, чтобы скопировать подробности.", false);
        tvState.setOnClickListener(v -> copy(full));
    }

    private void copy(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("lbs", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Android 15+: контент рисуется под системными панелями — добавляем отступы. */
    private void applySystemBarInsets(View root) {
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
            v.setPadding(l, t, r, b);
            return insets;
        });
        root.requestApplyInsets();
    }

    // ---------- Обновление ----------

    private void startUpdate() {
        running = true;
        tries = 0;
        btnUpdate.setEnabled(false);
        btnUpdate.setText("Обновляю…");
        tvState.setOnClickListener(null);
        showState("Загружаю данные…", true);
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
                showState("Жду ответа сайта…", true);
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
        showState("Строю линию фронта…", true);
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
        resetUpdateButton();
        btnOpen.setEnabled(true);
        btnShare.setEnabled(true);
        showLastFromPrefs();
        showState(isNew ? "✓ Файл сохранён в «Загрузки»"
                        : "✓ Новых данных нет — файл актуален", false);
    }

    private void finishWithError(String msg) {
        running = false;
        resetUpdateButton();
        showState("⚠ " + msg, false);
    }

    private void resetUpdateButton() {
        btnUpdate.setEnabled(true);
        btnUpdate.setText("Обновить ЛБС");
    }

    // ---------- Файл ----------

    private void openFile() {
        if (lastUri == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastUri, LbsCore.KML_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Открыть в…"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Нет приложения для KML (установите AlpineQuest)", Toast.LENGTH_LONG).show();
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
