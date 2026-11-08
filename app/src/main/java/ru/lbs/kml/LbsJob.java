package ru.lbs.kml;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Фоновая проверка: загружает снимок DeepStateMap, при новом — сохраняет KML и уведомляет. */
public class LbsJob extends JobService {

    private static final int MAX_TRIES = 20;
    private static final long TIMEOUT_MS = 120_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private JobParameters params;
    private String script;
    private int tries;
    private boolean done;

    @Override
    public boolean onStartJob(JobParameters p) {
        try {
            return start(p);
        } catch (Throwable e) {
            cleanup();
            return false;
        }
    }

    private boolean start(JobParameters p) {
        params = p;
        done = false;
        tries = 0;
        try {
            script = LbsCore.readAsset(this, "lbs.js");
        } catch (Exception e) {
            return false;
        }
        try {
            web = new WebView(getApplicationContext());
        } catch (Exception e) {
            return false;
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!done) handler.postDelayed(LbsJob.this::extract, 1500);
            }
        });
        web.loadUrl(LbsCore.SOURCE_URL);
        handler.postDelayed(() -> finish(true), TIMEOUT_MS);
        return true;
    }

    private void extract() {
        if (done || web == null) return;
        try {
            web.evaluateJavascript(script, this::onResult);
        } catch (Throwable e) {
            finish(true);
        }
    }

    private void onResult(String raw) {
        if (done) return;
        String res = LbsCore.decode(raw);
        if (res == null || res.startsWith("WAIT")) {
            if (++tries < MAX_TRIES) handler.postDelayed(this::extract, 2000);
            else finish(true);
            return;
        }
        if (res.startsWith("ERR")) {
            finish(false);
            return;
        }
        final String data = res;
        new Thread(() -> {
            try {
                LbsCore.Result r = LbsCore.buildAndSave(getApplicationContext(), data);
                if (LbsCore.remember(getApplicationContext(), r)) {
                    try {
                        notifyNew(r);
                    } catch (Throwable ignored) {
                    }
                }
                handler.post(() -> finish(false));
            } catch (Throwable e) {
                handler.post(() -> finish(true));
            }
        }).start();
    }

    private void notifyNew(LbsCore.Result r) {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LbsCore.ensureChannel(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent app = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piApp = PendingIntent.getActivity(this, 0, app,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(r.uri, LbsCore.KML_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piView = PendingIntent.getActivity(this, 1, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, LbsCore.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Новая ЛБС: " + r.date)
                .setContentText(r.name + " сохранён в «Загрузки» (линий: " + r.lines + ")")
                .setContentIntent(piApp)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource("android", android.R.drawable.ic_menu_view),
                        "Открыть", piView).build())
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }

    private void finish(boolean retry) {
        if (done) return;
        done = true;
        cleanup();
        jobFinished(params, retry);
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        done = true;
        cleanup();
        return true; // перенести на потом
    }
}
