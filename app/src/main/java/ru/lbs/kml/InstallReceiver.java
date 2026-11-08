package ru.lbs.kml;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Ответ системы на установку обновления из приложения. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
            return;
        }
        if (st == PackageInstaller.STATUS_SUCCESS) return; // приложение перезапустится само
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String text;
        if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                || (msg != null && msg.toLowerCase().contains("signature")))
            text = "Обновление не встало: у новой версии другая подпись. Один раз удалите приложение и установите заново "
                    + "(сначала сделайте резервную копию в меню).";
        else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) text = "Установка отменена";
        else text = "Обновление не установлено" + (msg == null ? "" : ": " + msg);
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
        LbsCore.prefs(c).edit().putString("updError", text).apply();
    }
}
