package ru.lbs.kml;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Скачивание новой версии и установка поверх — прямо из приложения. */
final class Updater {

    static final String APK_URL = "https://github.com/TRO9II/lbs-kml/releases/download/latest/LbsKml.apk";

    interface Progress {
        void on(long done, long total);
    }

    private Updater() {}

    /** Скачать APK в кэш (в фоновом потоке). */
    static File download(Context c, Progress p) throws Exception {
        File f = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection con = (HttpURLConnection) new URL(APK_URL).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", c.getPackageName());
        int code = con.getResponseCode();
        if (code != 200) throw new Exception("сервер ответил " + code);
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            long done = 0, last = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                os.write(buf, 0, k);
                done += k;
                if (done - last > 100_000) {
                    last = done;
                    p.on(done, total);
                }
            }
            p.on(done, total);
        }
        return f;
    }

    /** Передать APK системе на установку (покажется окно подтверждения). */
    static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= 31)
            sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("rubezh.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                s.fsync(out);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }
}
