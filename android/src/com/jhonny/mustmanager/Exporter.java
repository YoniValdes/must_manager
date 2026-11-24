package com.jhonny.mustmanager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Exporta el registro a Excel (.xlsx: Lecturas, Por hora, Estado del BMS) o CSV (Lecturas).
 * Se guarda en Descargas/MustManager (Android 10+, sin permisos) o en la carpeta de la app (Android 7–9).
 */
final class Exporter {

    static final class Result {
        Uri uri;
        String name;
        String location;
        String mime;
        int rows;
    }

    static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String MIME_CSV = "text/csv";
    /** Celdas por defecto si no hay lecturas con celdas (batería de 24 V). */
    private static final int DEFAULT_CELLS = 8;

    /** Nº de celdas de la batería: el de la lectura más reciente del periodo (8 en 24 V, 16 en 48 V). */
    private static int cellCount(SQLiteDatabase db, String[] args) {
        Cursor c = db.rawQuery("SELECT cells FROM readings WHERE ts >= ? AND ts < ? AND cells IS NOT NULL"
            + " ORDER BY ts DESC LIMIT 1", args);
        try {
            if (c.moveToFirst()) {
                int n = c.getString(0).split(",").length;
                if (n >= 1 && n <= 32) return n;
            }
        } finally {
            c.close();
        }
        return DEFAULT_CELLS;
    }

    private static int[] readingsWidths(int cells) {
        int[] head = {11, 9, 8, 11, 12, 12, 13, 12, 13, 13, 13, 15};
        int[] tail = {13, 13, 15, 15, 13};
        int[] w = new int[head.length + cells + tail.length];
        System.arraycopy(head, 0, w, 0, head.length);
        for (int i = 0; i < cells; i++) w[head.length + i] = 10;
        System.arraycopy(tail, 0, w, head.length + cells, tail.length);
        return w;
    }

    private static String[] buildReadingsHeader(int nCells) {
        String[] base = {"Fecha", "Hora", "SOC (%)", "Voltaje (V)", "Corriente (A)", "Potencia (W)", "Estado",
            "Restante (Ah)", "Capacidad (Ah)", "Celda mín (mV)", "Celda máx (mV)", "Diferencia (mV)"};
        String[] tail = {"Temp. máx (°C)", "Temp. MOS (°C)", "Temp. ambiente (°C)", "Duración (s)", "Energía (Wh)"};
        String[] h = new String[base.length + nCells + tail.length];
        System.arraycopy(base, 0, h, 0, base.length);
        for (int i = 0; i < nCells; i++) h[base.length + i] = "Celda " + (i + 1) + " (mV)";
        System.arraycopy(tail, 0, h, base.length + nCells, tail.length);
        return h;
    }

    static Result export(Context ctx, long from, long to, boolean xlsx) throws IOException {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
        Result r = new Result();
        r.name = "MustManager_" + stamp + (xlsx ? ".xlsx" : ".csv");
        r.mime = xlsx ? MIME_XLSX : MIME_CSV;
        SQLiteDatabase db = ReadingStore.get(ctx).getReadableDatabase();
        OutputStream out = open(ctx, r);
        try {
            int cells = cellCount(db, new String[]{Long.toString(from), Long.toString(to)});
            r.rows = xlsx ? writeXlsx(db, from, to, out, cells) : writeCsv(db, from, to, out, cells);
        } finally {
            out.close();
        }
        finishPending(ctx, r);
        return r;
    }

    // ------------------------------------------------------------------ destino del archivo

    private static OutputStream open(Context ctx, Result r) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, r.name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, r.mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MustManager");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = ctx.getContentResolver();
            r.uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (r.uri == null) throw new IOException("No se pudo crear el archivo en Descargas");
            r.location = "Descargas/MustManager";
            OutputStream os = cr.openOutputStream(r.uri);
            if (os == null) throw new IOException("No se pudo abrir el archivo");
            return os;
        }
        // Android 7–9: carpeta propia de la app (no necesita permisos). Accesible por USB.
        File dir = new File(ctx.getExternalFilesDir(null), "Exportaciones");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear la carpeta");
        File f = new File(dir, r.name);
        r.location = "Android/data/" + ctx.getPackageName() + "/files/Exportaciones";
        return new FileOutputStream(f);
    }

    private static void finishPending(Context ctx, Result r) {
        if (Build.VERSION.SDK_INT >= 29 && r.uri != null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(r.uri, cv, null, null);
        }
    }

    // ------------------------------------------------------------------ Excel

    private static int writeXlsx(SQLiteDatabase db, long from, long to, OutputStream out, int cells) throws IOException {
        XlsxWriter x = new XlsxWriter(out);
        String[] args = {Long.toString(from), Long.toString(to)};

        String[] head = buildReadingsHeader(cells);
        x.startSheet("Lecturas", head, readingsWidths(cells));
        int rows = 0;
        Cursor c = db.rawQuery(READINGS_SQL, args);
        try {
            while (c.moveToNext()) {
                x.row(readingRow(c, true, head.length, cells));
                rows++;
            }
        } finally {
            c.close();
        }

        // Todo en positivo: consumo = lo que sale de la batería; carga = lo que entra (sol o red).
        x.startSheet("Por hora", new String[]{"Fecha", "Hora", "Descargado (kWh)", "Cargado (kWh)", "Consumo medio (W)",
                "Pico de consumo (W)", "Carga media (W)", "SOC mín (%)", "SOC máx (%)", "Diferencia celdas máx (mV)", "Lecturas"},
            new int[]{11, 7, 17, 15, 18, 19, 16, 12, 12, 24, 10});
        c = db.rawQuery("SELECT (ts / 3600000) * 3600000 AS h,"
            + " SUM(CASE WHEN w < 0 THEN -w * dt ELSE 0 END),"
            + " SUM(CASE WHEN w > 0 THEN w * dt ELSE 0 END),"
            + " SUM(CASE WHEN w < 0 THEN dt ELSE 0 END),"
            + " SUM(CASE WHEN w > 0 THEN dt ELSE 0 END),"
            + " MIN(w), MIN(soc), MAX(soc), MAX(delta_mv), COUNT(*)"
            + " FROM readings WHERE ts >= ? AND ts < ? GROUP BY h ORDER BY h", args);
        try {
            while (c.moveToNext()) {
                double disWs = c.getDouble(1), chgWs = c.getDouble(2), disS = c.getDouble(3), chgS = c.getDouble(4);
                double peak = c.getDouble(5);
                x.row(XlsxWriter.Date.day(c.getLong(0)), XlsxWriter.Date.hourMin(c.getLong(0)), round(disWs / 3600000.0, 3), round(chgWs / 3600000.0, 3),
                    disS > 0 ? round(disWs / disS, 0) : null, peak < 0 ? round(-peak, 0) : null,
                    chgS > 0 ? round(chgWs / chgS, 0) : null, c.getInt(6), c.getInt(7),
                    c.isNull(8) ? null : (Object) c.getInt(8), c.getInt(9));
            }
        } finally {
            c.close();
        }

        x.startSheet("Estado del BMS", new String[]{"Fecha", "Hora", "Carga permitida", "Descarga permitida",
                "Batería llena", "SOC (%)", "Corriente (A)", "Trama (hex)"},
            new int[]{11, 9, 16, 18, 14, 9, 13, 44});
        c = db.rawQuery("SELECT ts, hex, soc, a FROM status_log WHERE ts >= ? AND ts < ? ORDER BY ts", args);
        try {
            while (c.moveToNext()) {
                String hex = c.getString(1);
                int b2 = byteAt(hex, 2), b3 = byteAt(hex, 3);
                x.row(XlsxWriter.Date.day(c.getLong(0)), XlsxWriter.Date.time(c.getLong(0)), yesNo(b3 & 0x02), yesNo(b3 & 0x04), yesNo(b2 & 0x80),
                    c.isNull(2) ? null : (Object) c.getInt(2), c.isNull(3) ? null : (Object) c.getDouble(3), hex);
            }
        } finally {
            c.close();
        }
        x.finish();
        return rows;
    }

    // ------------------------------------------------------------------ CSV (formato estándar: comas y punto decimal)

    private static int writeCsv(SQLiteDatabase db, long from, long to, OutputStream out, int cells) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
        w.write('﻿'); // BOM: Excel reconoce así las tildes
        String[] head = buildReadingsHeader(cells);
        w.write(join(head));
        w.write("\r\n");
        int rows = 0;
        Cursor c = db.rawQuery(READINGS_SQL, new String[]{Long.toString(from), Long.toString(to)});
        try {
            while (c.moveToNext()) {
                w.write(join(readingRow(c, false, head.length, cells)));
                w.write("\r\n");
                rows++;
            }
        } finally {
            c.close();
        }
        w.flush();
        return rows;
    }

    // ------------------------------------------------------------------ filas

    private static final String READINGS_SQL = "SELECT ts, soc, v, a, w, rem_ah, full_ah, cell_min, cell_max, delta_mv,"
        + " cells, t_max, t_mos, t_amb, dt FROM readings WHERE ts >= ? AND ts < ? ORDER BY ts";

    private static Object[] readingRow(Cursor c, boolean excelDate, int columns, int nCells) {
        long ts = c.getLong(0);
        double a = c.getDouble(3), w = c.getDouble(4), dt = c.getDouble(14);
        Object[] row = new Object[columns];
        int i = 0;
        // Fecha y hora en columnas separadas (más cómodo para filtrar por día en Excel).
        row[i++] = excelDate ? XlsxWriter.Date.day(ts) : DAY.get().format(new Date(ts));
        row[i++] = excelDate ? XlsxWriter.Date.time(ts) : TIME.get().format(new Date(ts));
        row[i++] = c.getInt(1);
        row[i++] = round(c.getDouble(2), 2);
        row[i++] = round(a, 2);
        row[i++] = round(w, 1);
        row[i++] = Math.abs(a) < 0.3 ? "Reposo" : (a < 0 ? "Descargando" : "Cargando");
        row[i++] = round(c.getDouble(5), 2);
        row[i++] = round(c.getDouble(6), 2);
        row[i++] = c.getInt(7);
        row[i++] = c.getInt(8);
        row[i++] = c.getInt(9);
        String[] cells = c.isNull(10) ? new String[0] : c.getString(10).split(",");
        for (int k = 0; k < nCells; k++) row[i++] = k < cells.length ? (Object) Integer.valueOf(cells[k].trim()) : null;
        row[i++] = round(c.getDouble(11), 1);
        row[i++] = round(c.getDouble(12), 1);
        row[i++] = round(c.getDouble(13), 1);
        row[i++] = round(dt, 1);
        row[i++] = round(w * dt / 3600.0, 3); // energía del intervalo (negativa = descarga)
        return row;
    }

    // CSV: fecha en formato ISO (aaaa-mm-dd), que cualquier programa entiende sin confundir día y mes.
    private static final ThreadLocal<SimpleDateFormat> DAY = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        }
    };
    private static final ThreadLocal<SimpleDateFormat> TIME = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US);
        }
    };

    private static String join(Object[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            Object v = cells[i];
            if (v == null) continue;
            if (v instanceof Double) sb.append(XlsxWriter.num((Double) v));
            else sb.append(v.toString().replace(',', ' '));
        }
        return sb.toString();
    }

    private static int byteAt(String hex, int index) {
        String[] p = hex.trim().split("\\s+");
        return index < p.length ? Integer.parseInt(p[index], 16) : 0;
    }

    private static String yesNo(int bit) {
        return bit != 0 ? "Sí" : "No";
    }

    private static Double round(double v, int decimals) {
        double p = Math.pow(10, decimals);
        return Math.round(v * p) / p;
    }
}
