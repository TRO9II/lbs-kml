package ru.lbs.kml;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * Фоновая работа группы: связь с сервером, уведомления о сообщениях и SOS,
 * отправка своей метки — даже когда приложение свёрнуто или экран выключен.
 */
public class GroupService extends Service {

    static final String CH_RUN = "group_run", CH_MSG = "group_msg", CH_SOS = "group_sos";
    private static final int ID_RUN = 2001, ID_MSG = 2002, ID_SOS = 2003;
    static volatile boolean running;

    private ChatClient chat;
    private LocationManager lm;
    private boolean locOn;
    private final LocationListener ll = new LocationListener() {
        @Override public void onLocationChanged(Location l) { ShareLoc.onLocation(GroupService.this, l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private final ChatClient.Listener listener = new ChatClient.Listener() {
        @Override
        public void onMessages(List<ChatClient.Msg> added) {
            for (ChatClient.Msg m : added) {
                if (m.mine) continue;
                if (m.kind.equals("sos")) {
                    notifySos(m);
                    continue;
                }
                if (MapActivity.visible) continue; // на экране — уведомление не нужно
                if (m.kind.equals("obj")) continue;
                notifyMsg(m);
            }
        }

        @Override
        public void onRole(String role) {
            if (role.equals("removed") || role.equals("left")) stop(GroupService.this);
            else updateRunning();
        }

        @Override
        public void onStatus(String status, boolean ok) {
            updateRunning();
        }
    };

    static boolean wanted(Context c) {
        return LbsCore.prefs(c).getBoolean("groupBg", true) && ChatClient.get(c).configured()
                && !LbsCore.prefs(c).getBoolean("offline", false);
    }

    static void start(Context c) {
        if (!wanted(c)) return;
        try {
            c.startForegroundService(new Intent(c, GroupService.class));
        } catch (Exception ignored) {
            // запуск из фона запрещён — запустится при следующем открытии приложения
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, GroupService.class));
    }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel run = new NotificationChannel(CH_RUN, "Группа: работа в фоне", NotificationManager.IMPORTANCE_MIN);
        run.setShowBadge(false);
        nm.createNotificationChannel(run);
        NotificationChannel msg = new NotificationChannel(CH_MSG, "Сообщения группы", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(msg);
        NotificationChannel sos = new NotificationChannel(CH_SOS, "SOS от участников", NotificationManager.IMPORTANCE_HIGH);
        sos.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        sos.enableVibration(true);
        sos.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        sos.setBypassDnd(true);
        nm.createNotificationChannel(sos);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        channels(this);
        chat = ChatClient.get(this);
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!wanted(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean share = LbsCore.prefs(this).getBoolean("shareLoc", false)
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int base = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC; // у переписки нет лимита 6 ч в сутки
                int type = base | (share ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
                startForeground(ID_RUN, runNotif(), type);
            } else {
                startForeground(ID_RUN, runNotif());
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        chat.addListener(listener);
        chat.start();
        setLocation(share);
        return START_STICKY;
    }

    private void setLocation(boolean on) {
        if (lm == null) lm = getSystemService(LocationManager.class);
        if (lm == null) return;
        if (on && !locOn) {
            try {
                long every = Math.max(10_000, LbsCore.prefs(this).getInt("shareEvery", 60) * 1000L / 2);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, every, 20f, ll, Looper.getMainLooper());
                locOn = true;
            } catch (SecurityException ignored) {
            }
        } else if (!on && locOn) {
            lm.removeUpdates(ll);
            locOn = false;
        }
    }

    private PendingIntent openApp(String extra) {
        Intent i = new Intent(this, MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (extra != null) i.putExtra(extra, true);
        return PendingIntent.getActivity(this, extra == null ? 0 : extra.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification runNotif() {
        boolean share = LbsCore.prefs(this).getBoolean("shareLoc", false);
        return new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("Рубеж · " + chat.groupName())
                .setContentText(share ? "На связи · вас видно на карте группы" : "На связи · метка выключена")
                .setContentIntent(openApp("openChat"))
                .setOngoing(true)
                .build();
    }

    private void updateRunning() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && running) nm.notify(ID_RUN, runNotif());
    }

    private void notifyMsg(ChatClient.Msg m) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        String text;
        switch (m.kind) {
            case "pt": text = "📍 " + m.ptName; break;
            case "st": text = "⚑ " + m.text; break;
            case "voice": text = "🎤 Голосовое сообщение"; break;
            case "photo": text = "📷 Фото" + (m.text.isEmpty() ? "" : ": " + m.text); break;
            default: text = m.text;
        }
        nm.notify(ID_MSG, new Notification.Builder(this, CH_MSG)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(m.from.isEmpty() ? chat.groupName() : m.from)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp("openChat"))
                .build());
    }

    private void notifySos(ChatClient.Msg m) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent i = new Intent(this, MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("sosSeq", m.seq);
        PendingIntent pi = PendingIntent.getActivity(this, (int) m.seq, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(ID_SOS, new Notification.Builder(this, CH_SOS)
                .setSmallIcon(R.drawable.ic_flag)
                .setContentTitle("SOS · " + (m.from.isEmpty() ? "участник" : m.from))
                .setContentText((m.text.isEmpty() ? "Нужна помощь" : m.text) + (m.hasLoc ? " · нажмите — показать на карте" : ""))
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build());
    }

    @Override
    public void onDestroy() {
        running = false;
        chat.removeListener(listener);
        setLocation(false);
        if (!MapActivity.visible) chat.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
