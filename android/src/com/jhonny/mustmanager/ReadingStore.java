package com.jhonny.mustmanager;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Registro local de lecturas (SQLite, incluido en Android). Una fila cada ~10 s.
 * Columna dt = segundos que representa esa fila, para integrar energía (Wh = W × dt / 3600)
 * sin contar los huecos en los que no hubo conexión.
 */
public final class ReadingStore extends SQLiteOpenHelper {

    private static ReadingStore instance;

    public static synchronized ReadingStore get(Context ctx) {
        if (instance == null) instance = new ReadingStore(ctx.getApplicationContext());
        return instance;
    }

    private ReadingStore(Context ctx) {
        super(ctx, "lecturas.db", null, 2);
    }

    /** v2: registro de la trama de estado (0A 03) cada vez que cambia, para descifrar sus bits. */
    private static final String CREATE_STATUS = "CREATE TABLE IF NOT EXISTS status_log ("
        + "ts INTEGER PRIMARY KEY, hex TEXT, soc INTEGER, a REAL)";

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings ("
            + "ts INTEGER PRIMARY KEY,"   // hora en ms (epoch)
            + "dt REAL,"                  // segundos que representa la fila
            + "soc INTEGER,"
            + "v REAL,"                   // voltaje del pack
            + "a REAL,"                   // corriente (negativa = descarga)
            + "w REAL,"                   // potencia = v × a
            + "rem_ah REAL,"
            + "full_ah REAL,"
            + "cell_min INTEGER,"
            + "cell_max INTEGER,"
            + "delta_mv INTEGER,"
            + "cells TEXT,"               // mV de cada celda separados por comas
            + "t_max REAL,"
            + "t_mos REAL,"
            + "t_amb REAL)");
        db.execSQL(CREATE_STATUS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL(CREATE_STATUS);
    }

    public void insertStatus(long ts, String hex, BmsProtocol.Basic b) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("hex", hex);
        if (b != null) {
            v.put("soc", b.soc);
            v.put("a", b.currentA);
        }
        getWritableDatabase().insertWithOnConflict("status_log", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void insert(long ts, double dtSeconds, BmsProtocol.Basic b, BmsProtocol.Cells c) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("dt", dtSeconds);
        v.put("soc", b.soc);
        v.put("v", b.voltageV);
        v.put("a", b.currentA);
        v.put("w", b.voltageV * b.currentA);
        v.put("rem_ah", b.remainingAh);
        v.put("full_ah", b.fullAh);
        int min = b.cellMinMv, max = b.cellMaxMv;
        if (c != null && c.mv.length > 0) {
            StringBuilder sb = new StringBuilder();
            min = Integer.MAX_VALUE;
            max = Integer.MIN_VALUE;
            for (int i = 0; i < c.mv.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(c.mv[i]);
                min = Math.min(min, c.mv[i]);
                max = Math.max(max, c.mv[i]);
            }
            v.put("cells", sb.toString());
        }
        v.put("cell_min", min);
        v.put("cell_max", max);
        v.put("delta_mv", max - min);
        v.put("t_max", b.tempMaxC);
        v.put("t_mos", b.tempMosC);
        v.put("t_amb", b.tempAmbientC);
        getWritableDatabase().insertWithOnConflict("readings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Lecturas desde from {ts, a, w}, de la más nueva a la más vieja (desc) o al revés. Hay que cerrar el cursor. */
    public Cursor flow(long from, boolean desc) {
        return getReadableDatabase().rawQuery("SELECT ts, a, w FROM readings WHERE ts >= ? ORDER BY ts " + (desc ? "DESC" : "ASC"),
            new String[]{Long.toString(from)});
    }

    public int count(long from, long to) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM readings WHERE ts >= ? AND ts < ?",
            new String[]{Long.toString(from), Long.toString(to)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * Historial agrupado en intervalos de bucketMs, como JSON:
     * [[t, soc, w_media, wh_descarga, wh_carga, delta_max, w_min, w_max], ...]
     */
    public String buckets(long from, long to, long bucketMs) {
        if (bucketMs < 1000) bucketMs = 1000;
        String b = Long.toString(bucketMs);
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT (ts / " + b + ") * " + b + " AS t, AVG(soc), AVG(w),"
                + " SUM(CASE WHEN w < 0 THEN -w * dt ELSE 0 END) / 3600.0,"
                + " SUM(CASE WHEN w > 0 THEN w * dt ELSE 0 END) / 3600.0,"
                + " MAX(delta_mv), MIN(w), MAX(w)"
                + " FROM readings WHERE ts >= ? AND ts < ? GROUP BY t ORDER BY t",
            new String[]{Long.toString(from), Long.toString(to)});
        StringBuilder sb = new StringBuilder("[");
        try {
            while (c.moveToNext()) {
                if (sb.length() > 1) sb.append(',');
                sb.append('[').append(c.getLong(0));
                sb.append(',').append(round(c.getDouble(1), 1));
                sb.append(',').append(round(c.getDouble(2), 1));
                sb.append(',').append(round(c.getDouble(3), 3));
                sb.append(',').append(round(c.getDouble(4), 3));
                sb.append(',').append(c.isNull(5) ? "null" : Integer.toString(c.getInt(5)));
                sb.append(',').append(round(c.getDouble(6), 1));
                sb.append(',').append(round(c.getDouble(7), 1));
                sb.append(']');
            }
        } finally {
            c.close();
        }
        return sb.append(']').toString();
    }

    private static String round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        double p = Math.pow(10, decimals);
        return Double.toString(Math.round(v * p) / p);
    }
}
