package com.jhonny.mustmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Respuesta del instalador de Android tras pasarle la APK descargada (Updater.install).
 * Lo normal es STATUS_PENDING_USER_ACTION: Android quiere que el usuario confirme
 * ("¿Quieres actualizar esta app?"), así que se abre su pantalla de confirmación.
 */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        if (!Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(confirm);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return; // la app se reinicia con la versión nueva
        String msg;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                msg = "Actualización cancelada. Puedes volver a intentarlo cuando quieras.";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                msg = "Android no acepta esta versión (la firma o el modelo no coinciden). Escribe al autor desde Acerca de.";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                msg = "No hay espacio suficiente en el dispositivo para actualizar.";
                break;
            default:
                String extra = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                msg = "No se pudo instalar la actualización" + (extra != null ? " (" + extra + ")" : "") + ".";
        }
        Updater.Listener l = Updater.listener;
        if (l != null) l.onUpdateError(msg);
    }
}
