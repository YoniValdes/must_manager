package com.jhonny.mustmanager;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Widget de la pantalla de inicio: SOC (anillo), potencia, autonomía hasta el corte y si llega al amanecer.
 * No lee el BMS: MonitorService le pasa cada lectura (onReading) o el cambio de conexión (onState).
 * Lo mostrado se guarda en SharedPreferences para repintarlo si Android recrea el widget.
 * Solo se actualiza cuando cambia lo que se ve, y como mucho cada 15 s, para no gastar batería.
 */
public class BatteryWidget extends AppWidgetProvider {

    private static final String PREFS = "widget";
    private static final long MIN_PUSH_MS = 15000;
    /** Si el servicio no manda nada en este tiempo, los datos se dan por viejos (gris). */
    private static final long STALE_MS = 5 * 60000L;
    private static final double IDLE_A = 0.3;

    // Colores de estado de la app (index.html, :root).
    static final int TEXT = 0xFFE6EDF3, MUTED = 0xFF8B98A5, LINE = 0xFF26313A;
    static final int GOOD = 0xFF22C55E, WARN = 0xFFF59E0B, BAD = 0xFFEF4444, CHARGE = 0xFF38BDF8;

    private static String lastKey;
    private static long lastPushAt;

    // ------------------------------------------------------------------ Android

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        render(ctx);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        render(ctx);
    }

    // ------------------------------------------------------------------ entradas desde MonitorService

    /**
     * Nueva lectura. avg = media de los últimos 30 min {corriente A, potencia W, ms cubiertos} o null
     * (la misma que usa la alerta del amanecer).
     */
    static void onReading(Context ctx, BmsProtocol.Basic b, double[] avg) {
        Settings s = Settings.load(ctx);
        long now = System.currentTimeMillis();
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

        double w = b.voltageV * b.currentA;
        String power;
        int powerColor;
        if (Math.abs(b.currentA) < IDLE_A) {
            power = "En reposo";
            powerColor = MUTED;
        } else if (b.currentA > 0) {
            power = "Cargando " + Math.round(w) + " W";
            powerColor = CHARGE;
        } else {
            power = "Consumo " + Math.round(-w) + " W";
            powerColor = TEXT;
        }

        String eta, line;
        int level;
        double usableAh = b.remainingAh - b.fullAh * s.cutoffSoc / 100.0;
        if (avg == null || avg[2] < 60000) {
            eta = "…";
            line = "Calculando la media…";
            level = MUTED;
        } else if (avg[0] <= -IDLE_A) {
            if (usableAh <= 0) {
                eta = "En el límite";
                line = "El inversor corta al " + s.cutoffSoc + " %";
                level = BAD;
            } else {
                double hours = usableAh / -avg[0];
                long reach = now + (long) (hours * 3600000L);
                double margin = (reach - s.nextSunrise(now)) / 3600000.0;
                eta = duration(hours);
                String until = "hasta las " + clock.format(new Date(reach));
                if (margin >= 0) {
                    level = margin >= 1 ? GOOD : WARN;
                    line = (margin >= 1 ? "✔ Llegas al amanecer · " : "⚠ Llegas justo · ") + until;
                } else {
                    level = BAD;
                    line = "⛔ No llegas al amanecer · " + until;
                }
            }
        } else if (avg[0] >= IDLE_A) {
            double toFull = Math.max(0, b.fullAh - b.remainingAh) / avg[0];
            level = CHARGE;
            eta = toFull > 0 ? duration(toFull) : "Llena";
            line = toFull > 0 ? "Cargando · llena hacia las " + clock.format(new Date(now + (long) (toFull * 3600000L)))
                : "La batería está llena";
        } else {
            eta = "En reposo";
            line = "Sin consumo apreciable";
            level = MUTED;
        }

        prefs(ctx).edit()
            .putString("state", "live").putLong("at", now)
            .putInt("soc", b.soc).putInt("socColor", b.soc <= s.socCritical ? BAD : b.soc <= s.socWarn ? WARN : GOOD)
            .putString("power", power).putInt("powerColor", powerColor)
            .putString("eta", eta).putInt("etaColor", level).putString("line", line)
            .apply();
        maybePush(ctx, false);
    }

    /** Cambio de conexión del servicio: "connected", "reconnecting", "disconnected", "error"… */
    static void onState(Context ctx, String state, String message) {
        if ("connected".equals(state)) return; // los datos llegan con la siguiente lectura
        SharedPreferences p = prefs(ctx);
        String cur = p.getString("state", "off");
        boolean hadData = "live".equals(cur) || "reconnecting".equals(cur);
        // Reconectando tras tener datos: se ven en gris. Cualquier otro caso (desconectado, error): sin conexión.
        boolean reconnecting = "reconnecting".equals(state) || "connecting".equals(state);
        String next = reconnecting && hadData ? "reconnecting" : "off";
        if (next.equals(cur)) return;
        p.edit().putString("state", next).apply();
        maybePush(ctx, true);
    }

    // ------------------------------------------------------------------ pintar

    private static void maybePush(Context ctx, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPushAt < MIN_PUSH_MS) return;
        // Solo lo que se ve (sin la hora exacta en ms): si no ha cambiado nada, no se molesta al launcher.
        SharedPreferences p = prefs(ctx);
        String key = p.getString("state", "") + "|" + p.getInt("soc", 0) + "|" + p.getString("power", "") + "|"
            + p.getString("eta", "") + "|" + p.getString("line", "") + "|" + p.getInt("etaColor", 0) + "|" + (p.getLong("at", 0) / 60000);
        if (!force && key.equals(lastKey)) return;
        lastKey = key;
        lastPushAt = now;
        push(ctx, build(ctx));
    }

    private static void render(Context ctx) {
        push(ctx, build(ctx));
    }

    private static void push(Context ctx, RemoteViews v) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName me = new ComponentName(ctx, BatteryWidget.class);
        if (mgr.getAppWidgetIds(me).length == 0) return; // nadie ha puesto el widget
        mgr.updateAppWidget(me, v);
    }

    private static RemoteViews build(Context ctx) {
        SharedPreferences p = prefs(ctx);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root,
            PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        String state = p.getString("state", "off");
        long at = p.getLong("at", 0);
        boolean stale = "live".equals(state) && System.currentTimeMillis() - at > STALE_MS;
        String time = at > 0 ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(at)) : "";

        if ("off".equals(state) || at == 0) {
            // Sin conexión: nunca se enseñan datos viejos como si fueran actuales.
            v.setImageViewBitmap(R.id.w_ring, ring(ctx, -1, MUTED));
            v.setTextViewText(R.id.w_power, "Must Manager");
            v.setTextColor(R.id.w_power, TEXT);
            v.setTextViewText(R.id.w_time, "");
            v.setTextViewText(R.id.w_eta, "Sin conexión");
            v.setTextColor(R.id.w_eta, MUTED);
            v.setTextViewText(R.id.w_line, "Toca para abrir y conectar");
            return v;
        }
        boolean grey = stale || "reconnecting".equals(state);
        v.setImageViewBitmap(R.id.w_ring, ring(ctx, p.getInt("soc", 0), grey ? MUTED : p.getInt("socColor", GOOD)));
        v.setTextViewText(R.id.w_power, p.getString("power", ""));
        v.setTextColor(R.id.w_power, grey ? MUTED : p.getInt("powerColor", TEXT));
        v.setTextViewText(R.id.w_time, time);
        v.setTextViewText(R.id.w_eta, p.getString("eta", "--"));
        v.setTextColor(R.id.w_eta, grey ? MUTED : p.getInt("etaColor", TEXT));
        v.setTextViewText(R.id.w_line, "reconnecting".equals(state) ? "Reconectando con el BMS…"
            : stale ? "Sin datos recientes · toca para abrir" : p.getString("line", ""));
        return v;
    }

    /** Anillo del SOC dibujado a mano (un widget no puede usar el HTML de la app). soc < 0 = sin datos. */
    private static Bitmap ring(Context ctx, int soc, int color) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int size = Math.round(58 * d);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float stroke = 6 * d;
        RectF r = new RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2);
        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(LINE);
        c.drawArc(r, 0, 360, false, arc);
        if (soc > 0) {
            arc.setColor(color);
            c.drawArc(r, -90, 360f * Math.min(soc, 100) / 100f, false, arc);
        }
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(soc < 0 ? MUTED : TEXT);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        String label = soc < 0 ? "--" : soc + "%";
        t.setTextSize((label.length() > 3 ? 14 : 16) * d);
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, t);
        return bmp;
    }

    // ------------------------------------------------------------------ utilidades

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String duration(double h) {
        long total = Math.round(h * 60), hh = total / 60, mm = total % 60;
        return hh > 0 ? hh + " h " + (mm < 10 ? "0" : "") + mm + " min" : mm + " min";
    }
}
