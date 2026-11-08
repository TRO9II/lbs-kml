package ru.lbs.kml;

import android.app.Application;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Сохраняет текст любой ошибки, чтобы показать его при следующем запуске. */
public class LbsApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                LbsCore.prefs(this).edit().putString("crash", sw.toString()).commit();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
