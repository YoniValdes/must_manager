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
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Captura de la app ENTERA (no solo lo que se ve en pantalla) para compartirla. La página elige en la
 * ventana "Compartir captura" la calidad (alta / media / baja: menos resolución y más compresión, para
 * ahorrar datos móviles) y si va en una imagen o en 3 partes (cortes entre tarjetas que calcula la página).
 * Se dibuja toda la página del WebView (necesita WebView.enableSlowWholeDocumentDraw() antes de crearlo),
 * se guarda en Imágenes/MustManager como JPG y se abre el menú de compartir de Android.
 */
final class Screenshot {

    /** Más de esto (≈ 60 MB de memoria) se reduce de tamaño para no quedarse sin memoria. */
    private static final long MAX_PIXELS = 15_000_000L;

    private Screenshot() {
    }

    /** Llamar en el hilo de la interfaz. opts = {q:"alta"|"media"|"baja", cuts:[y en px CSS, …]}. */
    static void share(final Activity act, final WebView web, String opts) {
        String q = "media";
        final ArrayList<Integer> cutsCss = new ArrayList<Integer>();
        try {
            JSONObject o = new JSONObject(opts);
            q = o.optString("q", "media");
            JSONArray c = o.optJSONArray("cuts");
            if (c != null) for (int i = 0; i < c.length(); i++) cutsCss.add(c.getInt(i));
        } catch (Exception ignored) {
        }
        // Ancho máximo y compresión JPG de cada calidad (en el móvil la alta ya es más estrecha que 1200).
        final int maxW = "alta".equals(q) ? Integer.MAX_VALUE : "baja".equals(q) ? 720 : 1200;
        final int jpg = "alta".equals(q) ? 92 : "baja".equals(q) ? 65 : 80;
        Toast.makeText(act, "Preparando la captura…", Toast.LENGTH_SHORT).show();
        // Un instante para que se vea el aviso antes de dibujar (dibujar la página entera bloquea la pantalla ~1 s).
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                capture(act, web, maxW, jpg, cutsCss);
            }
        }, 150);
    }

    private static void capture(final Activity act, WebView web, int maxW, final int jpg, ArrayList<Integer> cutsCss) {
        float density = act.getResources().getDisplayMetrics().density;
        int w = web.getWidth();
        int h = (int) Math.ceil(web.getContentHeight() * density);
        if (w <= 0 || h <= 0) {
            Toast.makeText(act, "No se pudo hacer la captura", Toast.LENGTH_SHORT).show();
            return;
        }
        float scale = Math.min(1f, maxW / (float) w);
        if ((long) (w * scale) * (long) (h * scale) > MAX_PIXELS) scale = (float) Math.sqrt(MAX_PIXELS / ((double) w * h));
        final Bitmap full;
        try {
            full = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(full);
            c.scale(scale, scale);
            web.draw(c);
        } catch (OutOfMemoryError e) {
            Toast.makeText(act, "No hay memoria suficiente para la captura", Toast.LENGTH_LONG).show();
            return;
        }
        // Límites de cada parte en píxeles de la imagen (de px CSS: × densidad × escala).
        final ArrayList<Integer> ys = new ArrayList<Integer>();
        ys.add(0);
        for (int y : cutsCss) {
            int py = Math.round(y * density * scale);
            if (py > ys.get(ys.size() - 1) + 20 && py < full.getHeight() - 20) ys.add(py);
        }
        ys.add(full.getHeight());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Uri> uris = new ArrayList<Uri>();
                long bytes = 0;
                String err = null;
                String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
                try {
                    int parts = ys.size() - 1;
                    for (int i = 0; i < parts; i++) {
                        Bitmap part = parts == 1 ? full
                            : Bitmap.createBitmap(full, 0, ys.get(i), full.getWidth(), ys.get(i + 1) - ys.get(i));
                        String name = "MustManager_" + stamp + (parts == 1 ? "" : "_" + (i + 1)) + ".jpg";
                        long[] size = new long[1];
                        Uri u = save(act, part, name, jpg, size);
                        bytes += size[0];
                        if (u != null) uris.add(u);
                        if (part != full) part.recycle();
                    }
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                } finally {
                    full.recycle();
                }
                final String error = err;
                final String kb = bytes >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0).replace('.', ',')
                    : (bytes / 1024) + " KB";
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            Toast.makeText(act, "No se pudo guardar la captura: " + error, Toast.LENGTH_LONG).show();
                        } else if (uris.isEmpty()) {
                            // Android 7–9: sin MediaStore no se puede compartir sin FileProvider; queda guardada.
                            Toast.makeText(act, "Captura guardada en la carpeta de la app (Android/data/…/Capturas)", Toast.LENGTH_LONG).show();
                        } else {
                            Intent send;
                            if (uris.size() == 1) {
                                send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
                            } else {
                                send = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                            }
                            send.setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            String what = uris.size() == 1 ? "captura" : uris.size() + " imágenes";
                            act.startActivity(Intent.createChooser(send, "Compartir " + what + " (" + kb + ")"));
                        }
                    }
                });
            }
        }, "captura").start();
    }

    /** Guarda el JPG en Imágenes/MustManager (Android 10+) o en la carpeta de la app (Android 7–9, sin Uri). */
    private static Uri save(Activity act, Bitmap bmp, String name, int jpg, long[] size) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MustManager");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = act.getContentResolver();
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("no se pudo crear el archivo");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new Exception("no se pudo abrir el archivo");
            CountingStream cs = new CountingStream(os);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, jpg, cs);
            } finally {
                cs.close();
            }
            size[0] = cs.count;
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            return uri;
        }
        File dir = new File(act.getExternalFilesDir(null), "Capturas");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("no se pudo crear la carpeta");
        File f = new File(dir, name);
        FileOutputStream os = new FileOutputStream(f);
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, jpg, os);
        } finally {
            os.close();
        }
        size[0] = f.length();
        return null;
    }

    /** Cuenta los bytes escritos (para decir cuánto ocupa lo que se va a enviar). */
    private static final class CountingStream extends java.io.FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws java.io.IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
