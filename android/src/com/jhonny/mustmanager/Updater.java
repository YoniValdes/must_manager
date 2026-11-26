package com.jhonny.mustmanager;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizaciones de la app desde las Releases públicas de GitHub.
 *
 * Es lo ÚNICO que usa internet en toda la app, y solo para esto:
 *  - una vez al día pregunta a GitHub cuál es la última versión publicada (~2 KB de respuesta);
 *  - descarga la APK solo cuando el usuario pulsa "Actualizar".
 * No se envía ningún dato del usuario ni de la batería: es una petición anónima de lectura.
 *
 * La instalación la confirma siempre el usuario en la pantalla de Android (obligatorio fuera de
 * la Play Store). Android solo acepta la APK si está firmada con la misma llave: una APK ajena
 * nunca podría sustituir a esta, y el historial y los ajustes se conservan.
 */
final class Updater {

    static final String RELEASES_API = "https://api.github.com/repos/YoniValdes/must_manager/releases/latest";
    static final String ACTION_INSTALL_STATUS = "com.jhonny.mustmanager.INSTALL_STATUS";
    static final String EXTRA_SHOW_UPDATE = "showUpdate";

    private static final String PREFS = "update";
    private static final String CHANNEL = "updates";
    private static final int ID_UPDATE = 300;
    private static final long CHECK_EVERY_MS = 24L * 3600 * 1000;
    private static final long RETRY_AFTER_ERROR_MS = 3L * 3600 * 1000;
    private static final int TIMEOUT_MS = 20000;

    /** Avisos para la pantalla (MainActivity), si está abierta. */
    interface Listener {
        void onUpdateState(JSONObject state);
        void onUpdateProgress(long done, long total);
        void onUpdateError(String message);
    }

    static volatile Listener listener;
    private static volatile boolean busy;
    private static long lastAutoTry; // en memoria: evita leer las preferencias con cada lectura del BMS

    private Updater() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** ¿Buscar solo una vez al día? (ajuste del usuario; activado por defecto) */
    static boolean autoEnabled(Context ctx) {
        return prefs(ctx).getBoolean("auto", true);
    }

    static void setAutoEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("auto", on).apply();
    }

    /** "0.22" > "0.21"; tolera la "v" de la etiqueta y partes que falten. */
    static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.length() == 0) return false;
        String[] a = candidate.replaceFirst("^[vV]", "").split("\\."), b = current.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Lo que sabe la app de la última versión publicada, para la página. */
    static JSONObject state(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String cur = currentVersion(ctx), latest = p.getString("latest", "");
        JSONObject o = new JSONObject();
        try {
            o.put("current", cur);
            o.put("latest", latest);
            o.put("newer", isNewer(latest, cur) && p.getString("url", "").length() > 0);
            o.put("notes", p.getString("notes", ""));
            o.put("size", p.getLong("size", 0));
            o.put("checkedAt", p.getLong("checkedAt", 0));
            o.put("auto", autoEnabled(ctx));
            o.put("busy", busy);
        } catch (JSONException ignored) { }
        return o;
    }

    /**
     * Comprobación automática: como mucho una vez al día (o a las 3 h si falló), en segundo plano,
     * y avisa con una notificación si hay una versión nueva. La llaman MonitorService y MainActivity.
     */
    static void maybeCheck(final Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastAutoTry < 10 * 60 * 1000) return;
        lastAutoTry = now;
        if (!autoEnabled(ctx) || busy) return;
        SharedPreferences p = prefs(ctx);
        boolean failed = p.getBoolean("failed", false);
        if (now - p.getLong("triedAt", 0) < (failed ? RETRY_AFTER_ERROR_MS : CHECK_EVERY_MS)) return;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(app);
                    notifyIfNew(app);
                } catch (Exception ignored) {
                    // Sin conexión o GitHub no responde: se reintentará más tarde. Nunca molesta al usuario.
                }
            }
        }, "mm-update-check").start();
    }

    /** Pregunta a GitHub por la última Release y guarda versión, novedades y enlace de la APK. */
    static JSONObject check(Context ctx) throws IOException, JSONException {
        SharedPreferences p = prefs(ctx);
        p.edit().putLong("triedAt", System.currentTimeMillis()).putBoolean("failed", true).apply();
        HttpURLConnection c = open(RELEASES_API, ctx);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) { // aún no hay ninguna Release publicada
            p.edit().putBoolean("failed", false).putLong("checkedAt", System.currentTimeMillis()).apply();
            return state(ctx);
        }
        if (code != 200) throw new IOException("GitHub respondió " + code);
        JSONObject rel = new JSONObject(readAll(c.getInputStream(), 512 * 1024));
        c.disconnect();
        String tag = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
        String url = "";
        long size = 0;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                    url = a.optString("browser_download_url", "");
                    size = a.optLong("size", 0);
                    break;
                }
            }
        }
        // Solo se aceptan descargas de GitHub (la API las da así; es una defensa más).
        if (!url.startsWith("https://github.com/")) url = "";
        p.edit().putString("latest", tag).putString("url", url).putLong("size", size)
                .putString("notes", rel.optString("body", "")).putLong("checkedAt", System.currentTimeMillis())
                .putBoolean("failed", false).apply();
        return state(ctx);
    }

    /** Notificación "Hay una versión nueva", una sola vez por versión. */
    static void notifyIfNew(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String latest = p.getString("latest", "");
        if (!state(ctx).optBoolean("newer") || latest.equals(p.getString("notified", ""))) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Actualizaciones de la app", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Aviso cuando hay una versión nueva de Must Manager");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class).putExtra(EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(ctx, ID_UPDATE, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Hay una nueva versión de Must Manager")
                .setContentText("Versión " + latest + " disponible. Toca para ver las novedades y actualizar.")
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            nm.notify(ID_UPDATE, b.build());
            p.edit().putString("notified", latest).apply();
        } catch (SecurityException ignored) {
            // Sin permiso de notificaciones: el aviso queda en la app (Acerca de).
        }
    }

    static void cancelNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_UPDATE);
    }

    // ------------------------------------------------------------------ instalar

    /** Android 8+: ¿puede esta app pedir instalar APKs? (interruptor "Instalar apps desconocidas") */
    static boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** Abre la pantalla de Android con el interruptor "Permitir de esta fuente" para Must Manager. */
    static void openInstallPermission(Activity a) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    /** Descarga la APK y se la pasa al instalador de Android (en un hilo aparte). */
    static void downloadAndInstall(final Context ctx) {
        if (busy) return;
        busy = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = prefs(app).getString("url", "");
                    if (url.length() == 0) throw new IOException("No hay ninguna versión nueva para descargar.");
                    File apk = download(app, url);
                    install(app, apk);
                } catch (Exception e) {
                    Listener l = listener;
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (l != null) l.onUpdateError("No se pudo descargar la actualización (" + msg + "). Comprueba la conexión y vuelve a intentarlo.");
                } finally {
                    busy = false;
                }
            }
        }, "mm-update-download").start();
    }

    private static File download(Context ctx, String url) throws IOException {
        long expected = prefs(ctx).getLong("size", 0);
        File out = new File(ctx.getCacheDir(), "actualizacion.apk");
        HttpURLConnection c = open(url, ctx); // GitHub redirige a su almacén de descargas (https → https)
        if (c.getResponseCode() != 200) throw new IOException("respuesta " + c.getResponseCode());
        long total = c.getContentLength() > 0 ? c.getContentLength() : expected;
        InputStream in = c.getInputStream();
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[32 * 1024];
        long done = 0, lastReport = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (done - lastReport > 64 * 1024) {
                    lastReport = done;
                    Listener l = listener;
                    if (l != null) l.onUpdateProgress(done, total);
                }
            }
        } finally {
            os.close();
            in.close();
            c.disconnect();
        }
        if (expected > 0 && done != expected) throw new IOException("descarga incompleta");
        Listener l = listener;
        if (l != null) l.onUpdateProgress(done, done);
        return out;
    }

    /** Instalador oficial de Android (PackageInstaller): no hace falta FileProvider ni librerías. */
    private static void install(Context ctx, File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName()); // solo puede actualizar ESTA app
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            InputStream in = new FileInputStream(apk);
            OutputStream out = s.openWrite("must-manager.apk", 0, apk.length());
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            out.close();
            in.close();
            Intent status = new Intent(ctx, InstallReceiver.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent sender = PendingIntent.getBroadcast(ctx, id, status, flags);
            s.commit(sender.getIntentSender());
        } catch (IOException e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    // ------------------------------------------------------------------ red

    private static HttpURLConnection open(String url, Context ctx) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        // GitHub exige un User-Agent. Solo el nombre y la versión de la app: ningún dato del usuario.
        c.setRequestProperty("User-Agent", "MustManager/" + currentVersion(ctx));
        return c;
    }

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > max) throw new IOException("respuesta demasiado grande");
            }
        } finally {
            in.close();
        }
        return b.toString("UTF-8");
    }
}
