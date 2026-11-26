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
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
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
 * Captura de la app ENTERA (no solo lo que se ve en pantalla). La página elige en la ventana
 * "Compartir captura" la calidad (alta / media / baja: menos resolución y más compresión, para ahorrar
 * datos móviles) y si va en una imagen o en 3 partes (cortes entre tarjetas que calcula la página).
 * Luego se comparte (se guarda en Imágenes/MustManager y se abre el menú de compartir) o se guarda donde
 * el usuario elija con el selector de Android (Descargas, etc.; sin permisos).
 * Dibujar toda la página necesita WebView.enableSlowWholeDocumentDraw() antes de crear el WebView.
 */
final class Screenshot {

    /** Más de esto (≈ 60 MB de memoria) se reduce de tamaño para no quedarse sin memoria. */
    private static final long MAX_PIXELS = 15_000_000L;
    /** Códigos de las pantallas de Android para "Guardar" (elegir archivo o carpeta). */
    static final int REQ_SAVE_FILE = 41, REQ_SAVE_DIR = 42;
    /** Imágenes ya preparadas esperando a que el usuario elija dónde guardarlas. */
    private static ArrayList<byte[]> pendingData;
    private static ArrayList<String> pendingNames;

    private Screenshot() {
    }

    /**
     * Llamar en el hilo de la interfaz. opts = {q:"alta"|"media"|"baja", cuts:[y en px CSS, …]}.
     * save = false: compartir; save = true: elegir dónde guardarla.
     */
    static void share(final Activity act, final WebView web, String opts, final boolean save) {
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
                capture(act, web, maxW, jpg, cutsCss, save);
            }
        }, 150);
    }

    private static void capture(final Activity act, WebView web, int maxW, final int jpg, ArrayList<Integer> cutsCss, final boolean save) {
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
                // 1) Comprimir cada parte a JPG en memoria
                final ArrayList<byte[]> data = new ArrayList<byte[]>();
                final ArrayList<String> names = new ArrayList<String>();
                String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
                long bytes = 0;
                try {
                    int parts = ys.size() - 1;
                    for (int i = 0; i < parts; i++) {
                        Bitmap part = parts == 1 ? full
                            : Bitmap.createBitmap(full, 0, ys.get(i), full.getWidth(), ys.get(i + 1) - ys.get(i));
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        part.compress(Bitmap.CompressFormat.JPEG, jpg, bos);
                        data.add(bos.toByteArray());
                        bytes += bos.size();
                        names.add("MustManager_" + stamp + (parts == 1 ? "" : "_" + (i + 1)) + ".jpg");
                        if (part != full) part.recycle();
                    }
                } catch (OutOfMemoryError e) {
                    data.clear();
                } finally {
                    full.recycle();
                }
                if (data.isEmpty()) {
                    toast(act, "No hay memoria suficiente para la captura");
                    return;
                }
                final String size = bytes >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0).replace('.', ',')
                    : (bytes / 1024) + " KB";
                if (save) askWhereToSave(act, data, names);
                else shareNow(act, data, names, size);
            }
        }, "captura").start();
    }

    /** Guardar: el selector de Android elige un archivo (una imagen) o una carpeta (varias). */
    private static void askWhereToSave(final Activity act, final ArrayList<byte[]> data, final ArrayList<String> names) {
        pendingData = data;
        pendingNames = names;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (data.size() == 1) {
                        act.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("image/jpeg")
                            .putExtra(Intent.EXTRA_TITLE, names.get(0)), REQ_SAVE_FILE);
                    } else {
                        Toast.makeText(act, "Elige la carpeta donde guardar las " + data.size() + " imágenes", Toast.LENGTH_LONG).show();
                        act.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_DIR);
                    }
                } catch (Exception e) {
                    pendingData = null;
                    pendingNames = null;
                    Toast.makeText(act, "Este dispositivo no permite elegir dónde guardar", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Respuesta del selector de "Guardar" (la pasa MainActivity.onActivityResult). */
    static void onResult(final Activity act, int requestCode, int resultCode, Intent result) {
        final ArrayList<byte[]> data = pendingData;
        final ArrayList<String> names = pendingNames;
        pendingData = null;
        pendingNames = null;
        if (data == null || resultCode != Activity.RESULT_OK || result == null || result.getData() == null) return;
        final Uri target = result.getData();
        final boolean dir = requestCode == REQ_SAVE_DIR;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentResolver cr = act.getContentResolver();
                    if (!dir) {
                        write(cr, target, data.get(0));
                        toast(act, "Captura guardada");
                    } else {
                        Uri parent = DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target));
                        for (int i = 0; i < data.size(); i++) {
                            Uri doc = DocumentsContract.createDocument(cr, parent, "image/jpeg", names.get(i));
                            if (doc == null) throw new Exception("no se pudo crear " + names.get(i));
                            write(cr, doc, data.get(i));
                        }
                        toast(act, data.size() + " imágenes guardadas en la carpeta elegida");
                    }
                } catch (Exception e) {
                    toast(act, "No se pudo guardar: " + e.getMessage());
                }
            }
        }, "guardar-captura").start();
    }

    /** Compartir: se guarda en Imágenes/MustManager (hace falta un Uri) y se abre el menú de compartir. */
    private static void shareNow(final Activity act, ArrayList<byte[]> data, ArrayList<String> names, final String size) {
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        String err = null;
        try {
            for (int i = 0; i < data.size(); i++) {
                Uri u = saveToPictures(act, data.get(i), names.get(i));
                if (u != null) uris.add(u);
            }
        } catch (Exception e) {
            err = String.valueOf(e.getMessage());
        }
        final String error = err;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (error != null) {
                    Toast.makeText(act, "No se pudo preparar la captura: " + error, Toast.LENGTH_LONG).show();
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
                    act.startActivity(Intent.createChooser(send, "Compartir " + what + " (" + size + ")"));
                    Toast.makeText(act, "También queda en Galería → MustManager", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Guarda el JPG en Imágenes/MustManager (Android 10+) o en la carpeta de la app (Android 7–9, sin Uri). */
    private static Uri saveToPictures(Activity act, byte[] bytes, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MustManager");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = act.getContentResolver();
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("no se pudo crear el archivo");
            write(cr, uri, bytes);
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            return uri;
        }
        File dir = new File(act.getExternalFilesDir(null), "Capturas");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("no se pudo crear la carpeta");
        FileOutputStream os = new FileOutputStream(new File(dir, name));
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
        return null;
    }

    private static void write(ContentResolver cr, Uri uri, byte[] bytes) throws Exception {
        OutputStream os = cr.openOutputStream(uri);
        if (os == null) throw new Exception("no se pudo abrir el archivo");
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }

    private static void toast(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
