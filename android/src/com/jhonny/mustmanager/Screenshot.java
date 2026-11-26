package com.jhonny.mustmanager;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Captura de la app ENTERA (no solo lo que se ve en pantalla) para compartirla: dibuja toda la página
 * del WebView en una imagen PNG a la resolución de la pantalla, la guarda en Imágenes/MustManager y
 * abre el menú de compartir de Android. Necesita WebView.enableSlowWholeDocumentDraw() antes de crear
 * el WebView (si no, solo se dibuja la parte visible).
 */
final class Screenshot {

    /** Más de esto (≈ 60 MB de memoria) se reduce de tamaño para no quedarse sin memoria. */
    private static final long MAX_PIXELS = 15_000_000L;

    private Screenshot() {
    }

    /** Llamar en el hilo de la interfaz. */
    static void share(final Activity act, WebView web) {
        int w = web.getWidth();
        int h = (int) Math.ceil(web.getContentHeight() * act.getResources().getDisplayMetrics().density);
        if (w <= 0 || h <= 0) {
            Toast.makeText(act, "No se pudo hacer la captura", Toast.LENGTH_SHORT).show();
            return;
        }
        float scale = 1f;
        if ((long) w * h > MAX_PIXELS) scale = (float) Math.sqrt(MAX_PIXELS / ((double) w * h));
        final Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(Math.round(w * scale), Math.round(h * scale), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.scale(scale, scale);
            web.draw(c);
        } catch (OutOfMemoryError e) {
            Toast.makeText(act, "No hay memoria suficiente para la captura", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(act, "Preparando la captura…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri uri;
                String err = null;
                Uri u = null;
                try {
                    u = save(act, bmp);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                } finally {
                    bmp.recycle();
                }
                uri = u;
                final String error = err;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            Toast.makeText(act, "No se pudo guardar la captura: " + error, Toast.LENGTH_LONG).show();
                        } else if (uri == null) {
                            // Android 7–9: sin MediaStore no se puede compartir sin FileProvider; queda guardada.
                            Toast.makeText(act, "Captura guardada en la carpeta de la app (Android/data/…/Capturas)", Toast.LENGTH_LONG).show();
                        } else {
                            Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            act.startActivity(Intent.createChooser(send, "Compartir captura"));
                        }
                    }
                });
            }
        }, "captura").start();
    }

    /** Guarda el PNG en Imágenes/MustManager (Android 10+) o en la carpeta de la app (Android 7–9, sin Uri). */
    private static Uri save(Activity act, Bitmap bmp) throws Exception {
        String name = "MustManager_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MustManager");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = act.getContentResolver();
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("no se pudo crear el archivo");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new Exception("no se pudo abrir el archivo");
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            } finally {
                os.close();
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            return uri;
        }
        File dir = new File(act.getExternalFilesDir(null), "Capturas");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("no se pudo crear la carpeta");
        FileOutputStream os = new FileOutputStream(new File(dir, name));
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            os.close();
        }
        return null;
    }
}
