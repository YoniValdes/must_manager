package com.jhonny.mustmanager;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Widget de la pantalla de inicio: SOC (anillo), potencia, autonomía hasta el corte y si llega al amanecer,
 * sobre el cielo de la app (alba, día, atardecer, noche) con colinas, nubes, estrellas, sol y luna.
 *
 * No lee el BMS: MonitorService le pasa cada lectura (onReading) o el cambio de conexión (onState).
 * Lo mostrado se guarda en SharedPreferences para repintarlo si Android recrea el widget.
 *
 * Un widget es una imagen que la app entrega al launcher, no una vista animada: el cielo se redibuja
 * en cada actualización (como mucho cada 15 s y al menos cada minuto mientras hay conexión) y las nubes
 * avanzan un poco cada vez, como un stop-motion lento. Animarlo en continuo gastaría mucha batería.
 *
 * Todo se dibuja al tamaño real de cada widget: al estirarlo crecen el anillo y las letras.
 */
public class BatteryWidget extends AppWidgetProvider {

    private static final String PREFS = "widget";
    private static final long MIN_PUSH_MS = 15000;
    /** Si el servicio no manda nada en este tiempo, los datos se dan por viejos (gris). */
    private static final long STALE_MS = 5 * 60000L;
    private static final double IDLE_A = 0.3;

    /** Tamaño de referencia (3×1 casillas) para el que están pensadas las letras base. */
    private static final float BASE_W_DP = 250, BASE_H_DP = 80;
    /** Lado mayor máximo de la imagen del cielo en píxeles (es plana: escalada se ve bien y pesa poco). */
    private static final float SKY_MAX_PX = 1000;

    // Colores de estado de la app (index.html, :root).
    static final int TEXT = 0xFFE6EDF3, MUTED = 0xFF9AA6B2, LINE = 0x66FFFFFF;
    static final int GOOD = 0xFF4ADE80, WARN = 0xFFFBBF24, BAD = 0xFFF87171, CHARGE = 0xFF38BDF8;

    private static String lastKey;
    private static long lastPushAt;

    // ------------------------------------------------------------------ Android

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        pushAll(ctx);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        // Cambió de tamaño: se redibuja a la nueva medida.
        mgr.updateAppWidget(id, build(ctx, mgr, id));
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
        // Solo lo que se ve (la hora, por minutos): si no ha cambiado nada, no se molesta al launcher.
        SharedPreferences p = prefs(ctx);
        String key = p.getString("state", "") + "|" + p.getInt("soc", 0) + "|" + p.getString("power", "") + "|"
            + p.getString("eta", "") + "|" + p.getString("line", "") + "|" + p.getInt("etaColor", 0) + "|" + (now / 60000)
            + "|" + ctx.getSharedPreferences("grid", Context.MODE_PRIVATE).getString("state", "");
        if (!force && key.equals(lastKey)) return;
        lastKey = key;
        lastPushAt = now;
        pushAll(ctx);
    }

    private static void pushAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, BatteryWidget.class));
        // Cada widget con su propio tamaño (puede haber varios en la pantalla de inicio).
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, mgr, id));
    }

    /** Medida del widget en dp {ancho, alto}, según la orientación actual de la pantalla. */
    private static float[] sizeDp(Context ctx, AppWidgetManager mgr, int id) {
        Bundle o = mgr.getAppWidgetOptions(id);
        boolean land = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        // Convención de Android: en vertical vale el ancho mínimo y el alto máximo; en horizontal, al revés.
        int w = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int h = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        return new float[]{w > 0 ? w : BASE_W_DP, h > 0 ? h : BASE_H_DP};
    }

    private static RemoteViews build(Context ctx, AppWidgetManager mgr, int id) {
        SharedPreferences p = prefs(ctx);
        Settings s = Settings.load(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        float[] size = sizeDp(ctx, mgr, id);
        float wDp = size[0], hDp = size[1];

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root,
            PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        // Escala: crece con el alto (y sin pasarse del ancho, para que el texto quepa).
        float k = Math.max(0.85f, Math.min(3f, Math.min(hDp / BASE_H_DP, wDp / BASE_W_DP * 1.15f)));
        float ringDp = Math.max(44, Math.min(Math.min(hDp * 0.72f, wDp * 0.34f), 58 * k * 1.1f));
        int pad = Math.round(12 * k * density);
        v.setViewPadding(R.id.w_content, pad, 0, Math.round(14 * k * density), 0);
        v.setTextViewTextSize(R.id.w_power, TypedValue.COMPLEX_UNIT_DIP, 13 * k);
        v.setTextViewTextSize(R.id.w_time, TypedValue.COMPLEX_UNIT_DIP, 11 * k);
        v.setTextViewTextSize(R.id.w_eta, TypedValue.COMPLEX_UNIT_DIP, 22 * k);
        v.setTextViewTextSize(R.id.w_line, TypedValue.COMPLEX_UNIT_DIP, 12 * k);

        long now = System.currentTimeMillis();
        v.setImageViewBitmap(R.id.w_sky, sky(s, now, wDp, hDp, density));

        String state = p.getString("state", "off");
        long at = p.getLong("at", 0);
        boolean stale = "live".equals(state) && now - at > STALE_MS;
        String time = at > 0 ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(at)) : "";
        int ringPx = Math.round(ringDp * density);

        if ("off".equals(state) || at == 0) {
            // Sin conexión: nunca se enseñan datos viejos como si fueran actuales.
            v.setImageViewBitmap(R.id.w_ring, ring(ringPx, -1, MUTED));
            v.setTextViewText(R.id.w_power, "Must Manager");
            v.setTextColor(R.id.w_power, TEXT);
            v.setTextViewText(R.id.w_time, "");
            v.setTextViewText(R.id.w_eta, "Sin conexión");
            v.setTextColor(R.id.w_eta, TEXT);
            v.setTextViewText(R.id.w_line, "Toca para abrir y conectar");
            return v;
        }
        boolean grey = stale || "reconnecting".equals(state);
        v.setImageViewBitmap(R.id.w_ring, ring(ringPx, p.getInt("soc", 0), grey ? MUTED : p.getInt("socColor", GOOD)));
        v.setTextViewText(R.id.w_power, p.getString("power", ""));
        v.setTextColor(R.id.w_power, grey ? MUTED : p.getInt("powerColor", TEXT));
        v.setTextViewText(R.id.w_time, time);
        v.setTextViewText(R.id.w_eta, p.getString("eta", "--"));
        v.setTextColor(R.id.w_eta, grey ? MUTED : p.getInt("etaColor", TEXT));
        CharSequence line = "reconnecting".equals(state) ? "Reconectando con el BMS…"
            : stale ? "Sin datos recientes · toca para abrir" : p.getString("line", "");
        // Apagón: segunda línea en rojo ("Sin corriente desde…") o en azul ("Volvió la corriente…").
        Object[] gl = grey ? null : GridWatch.widgetLine(ctx, now);
        if (gl != null) {
            String extra = (String) gl[0];
            SpannableString sp = new SpannableString(line + "\n" + extra);
            sp.setSpan(new ForegroundColorSpan(((Integer) gl[1]) == 1 ? BAD : CHARGE), sp.length() - extra.length(), sp.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line = sp;
        }
        v.setTextViewText(R.id.w_line, line);
        return v;
    }

    /** Anillo del SOC (un widget no puede usar el HTML de la app). soc < 0 = sin datos. */
    private static Bitmap ring(int size, int soc, int color) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float stroke = size * 0.105f;
        RectF r = new RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(0x59050812); // velo detrás del número para leerlo sobre cualquier cielo
        c.drawCircle(size / 2f, size / 2f, size / 2f - stroke / 2, disc);
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
        t.setShadowLayer(size * 0.05f, 0, size * 0.015f, 0xB3000000);
        String label = soc < 0 ? "--" : soc + "%";
        t.setTextSize(size * (label.length() > 3 ? 0.24f : 0.28f));
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, t);
        return bmp;
    }

    // ------------------------------------------------------------------ cielo (el mismo de la app)

    /**
     * Fotogramas clave del cielo, copiados de SKY_KEYS de index.html: minutos respecto al amanecer (0)
     * o al atardecer (1), y colores de cielo arriba/abajo, colinas lejos/medio/cerca, nubes y su opacidad (%).
     */
    private static final int[][] SKY_KEYS = {
        {0, -70, 0x0b1433, 0x2c3f86, 0x24357a, 0x1a2760, 0x101940, 0x7d8fd6, 25}, // noche
        {0, -20, 0x5b47b8, 0xd4b0ea, 0x9b7fdb, 0x7a5cc8, 0x4e3699, 0xf3e9ff, 70}, // alba violeta
        {0, 45, 0x3f8fd8, 0xe5ee9c, 0xcbdc6e, 0x9bbf4e, 0x5f8f33, 0xffffff, 85},  // mañana prado
        {0, 150, 0x2a78de, 0xa6d8f6, 0x93d48c, 0x52af6a, 0x2b7a4c, 0xffffff, 90}, // día azul
        {1, -110, 0x2a78de, 0xa6d8f6, 0x93d48c, 0x52af6a, 0x2b7a4c, 0xffffff, 90}, // tarde
        {1, -15, 0xe45a6c, 0xf8b58b, 0xf6a06d, 0xe57d4c, 0xbd5235, 0xffe3d6, 80}, // atardecer coral
        {1, 30, 0x6e52c9, 0xd2b3ec, 0xa487de, 0x8263cc, 0x563c9f, 0xefe4ff, 60},  // crepúsculo violeta
        {1, 85, 0x0b1433, 0x2c3f86, 0x24357a, 0x1a2760, 0x101940, 0x7d8fd6, 25},  // noche
    };

    /** Nubes {x, y de la base, ancho, segundos de una ida} en el lienzo de 1000 × 200 de la app. */
    private static final float[][] CLOUDS = {
        {550, 60, 70, 300}, {780, 40, 86, 420}, {295, 34, 50, 360}, {925, 72, 46, 480},
    };

    private static Bitmap sky(Settings s, long now, float wDp, float hDp, float density) {
        float px = Math.min(density, SKY_MAX_PX / Math.max(wDp, hDp));
        int W = Math.max(1, Math.round(wDp * px)), H = Math.max(1, Math.round(hDp * px));
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // Bordes redondeados (el launcher no recorta la imagen).
        Path clip = new Path();
        float rad = 22 * px;
        clip.addRoundRect(new RectF(0, 0, W, H), rad, rad, Path.Direction.CW);
        c.clipPath(clip);

        // ---- colores del momento del día (interpolados entre fotogramas clave, como en la app)
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        float m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f;
        int R = Settings.toMinutes(s.sunrise), S = Settings.toMinutes(s.sunset);
        if (S <= R) S += 1440;
        int n = SKY_KEYS.length;
        float[] km = new float[n + 2];
        int[][] kv = new int[n + 2][];
        for (int i = 0; i < n; i++) {
            km[i + 1] = (SKY_KEYS[i][0] == 0 ? R : S) + SKY_KEYS[i][1];
            kv[i + 1] = SKY_KEYS[i];
        }
        km[0] = km[n] - 1440;
        kv[0] = kv[n];
        km[n + 1] = km[1] + 1440;
        kv[n + 1] = kv[1];
        if (m < km[0]) m += 1440;
        int[] col = new int[6];
        float co = kv[0][8] / 100f;
        for (int i = 0; i < n + 1; i++) {
            if (m >= km[i] && m <= km[i + 1]) {
                float t = (m - km[i]) / Math.max(1, km[i + 1] - km[i]);
                for (int f = 0; f < 6; f++) col[f] = mix(kv[i][2 + f], kv[i + 1][2 + f], t);
                co = (kv[i][8] + (kv[i + 1][8] - kv[i][8]) * t) / 100f;
                break;
            }
        }
        int top = col[0], bottom = col[1], far = col[2], mid = col[3], near = col[4], cloud = col[5];

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, H, opaque(top), opaque(bottom), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        // Lienzo de la app (1000 × 200) estirado al widget: X a lo ancho; Y y tamaños con el alto.
        float sx = W / 1000f, sy = H / 200f, u = sy;

        // ---- sol o luna (arco por la mitad derecha) y estrellas
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int r = mod(R), st = mod(S);
        int dayLen = mod(st - r), sinceRise = mod(mins - r);
        boolean isDay = sinceRise <= dayLen;
        float prog = isDay ? sinceRise / (float) Math.max(1, dayLen) : mod(mins - st) / (float) Math.max(1, mod(r - st));
        float lift = (float) Math.sin(prog * Math.PI);
        float bx = (470 + prog * 480) * sx, by = (178 - lift * 140) * sy;

        float night = isDay ? 0 : Math.min(1, Math.min(mod(mins - st) / 60f, mod(r - mins) / 45f));
        if (night > 0) {
            long seed = 7;
            Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < 70; i++) {
                seed = (seed * 9301 + 49297) % 233280;
                float x = seed / 233280f * 1000;
                seed = (seed * 9301 + 49297) % 233280;
                float y = seed / 233280f * 140;
                seed = (seed * 9301 + 49297) % 233280;
                float rr = 0.5f + seed / 233280f * 1.1f;
                // Titileo: cada estrella con su fase, cambia en cada actualización.
                float tw = 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.sin(now / 3500.0 + i * 1.7));
                star.setColor(Color.argb(Math.round(255 * night * tw), 255, 255, 255));
                c.drawCircle(x * sx, y * sy, Math.max(0.6f, rr * u), star);
            }
        }

        if (isDay) {
            float g = 1 + (1 - lift) * 0.45f; // cerca del horizonte el resplandor crece
            p.setShader(new RadialGradient(bx, by, 46 * u * g,
                new int[]{0xFFFFF7D6, 0xD9FDE68A, 0x00FBBF24}, new float[]{0, 0.3f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, 46 * u * g, p);
            p.setShader(null);
            p.setColor(0xFFFFF4C2);
            c.drawCircle(bx, by, 14 * u, p);
        } else {
            p.setShader(new RadialGradient(bx, by, 34 * u, 0x80DBE4FF, 0x00DBE4FF, Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, 34 * u, p);
            p.setShader(null);
            Path moon = new Path(), cut = new Path();
            moon.addCircle(bx, by, 12 * u, Path.Direction.CW);
            cut.addCircle(bx + 6 * u, by - 4 * u, 11 * u, Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
            p.setColor(0xFFF1F5FF);
            c.drawPath(moon, p);
        }

        // ---- nubes: vaivén de ida y vuelta (±70), cada una a su ritmo; avanzan en cada actualización
        p.setColor(opaque(cloud));
        p.setAlpha(Math.round(255 * co));
        for (float[] cl : CLOUDS) {
            double phase = (now / 1000.0) / cl[3] * Math.PI;
            float off = 70 * (float) Math.sin(phase);
            drawCloud(c, p, (cl[0] + off) * sx, cl[1] * sy, cl[2] * u);
        }
        p.setAlpha(255);

        // ---- colinas en tres capas y arbolitos (mismas curvas que la app)
        p.setColor(opaque(far));
        c.drawPath(hill(sx, sy, H, 150, 150, 132, 310, 142, 470, 148, 630, 154, 800, 128, 1000, 142), p);
        p.setColor(opaque(mid));
        c.drawPath(hill(sx, sy, H, 165, 180, 151, 370, 167, 560, 158, 750, 149, 860, 148, 1000, 158), p);
        p.setColor(opaque(mix(mid, 0x000000, 0.35f)));
        float[][] trees = {{640.1f, 145, 4.2f, 7.5f, 639, 148, 11}, {654.1f, 147.5f, 3.5f, 6.2f, 653, 150, 9}, {667.1f, 146, 4, 7, 666, 149, 10}};
        for (float[] t : trees) {
            c.drawRect(t[4] * sx, t[5] * sy, t[4] * sx + 2.2f * u, (t[5] + t[6]) * sy, p);
            c.drawOval(new RectF(t[0] * sx - t[2] * u, t[1] * sy - t[3] * u, t[0] * sx + t[2] * u, t[1] * sy + t[3] * u), p);
        }
        p.setColor(opaque(near));
        c.drawPath(hill(sx, sy, H, 181, 220, 170, 430, 187, 650, 177, 870, 167, 900, 168, 1000, 176), p);

        // ---- velo oscuro abajo y a la izquierda para leer el texto (como .eta::after de la app)
        p.setShader(new LinearGradient(0, H, 0, 0, new int[]{0x9E050812, 0x38050812, 0x00050812},
            new float[]{0, 0.26f, 0.45f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(new LinearGradient(0, 0, W, 0, new int[]{0x8C050812, 0x47050812, 0x00050812},
            new float[]{0, 0.45f, 0.72f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        return bmp;
    }

    /** Colina: M0 y0 C c1 c2 p1 C c3 c4 p2 L1000 200 L0 200 Z (la "S" del SVG ya va convertida en C). */
    private static Path hill(float sx, float sy, float H, float y0, float... k) {
        Path h = new Path();
        h.moveTo(0, y0 * sy);
        h.cubicTo(k[0] * sx, k[1] * sy, k[2] * sx, k[3] * sy, k[4] * sx, k[5] * sy);
        h.cubicTo(k[6] * sx, k[7] * sy, k[8] * sx, k[9] * sy, k[10] * sx, k[11] * sy);
        h.lineTo(1000 * sx, H);
        h.lineTo(0, H);
        h.close();
        return h;
    }

    /** Nube plana: base recta con tres bultos, en un solo trazo para que la transparencia sea uniforme. */
    private static void drawCloud(Canvas c, Paint p, float x, float base, float w) {
        Path cl = new Path();
        cl.addRoundRect(new RectF(x, base - 0.2f * w, x + w, base), 0.1f * w, 0.1f * w, Path.Direction.CW);
        Path b = new Path();
        b.addCircle(x + 0.3f * w, base - 0.2f * w, 0.19f * w, Path.Direction.CW);
        cl.op(b, Path.Op.UNION);
        b.reset();
        b.addCircle(x + 0.56f * w, base - 0.27f * w, 0.26f * w, Path.Direction.CW);
        cl.op(b, Path.Op.UNION);
        b.reset();
        b.addCircle(x + 0.8f * w, base - 0.15f * w, 0.15f * w, Path.Direction.CW);
        cl.op(b, Path.Op.UNION);
        b.reset();
        b.addRect(x - w, base, x + 2 * w, base + w, Path.Direction.CW); // base plana
        cl.op(b, Path.Op.DIFFERENCE);
        c.drawPath(cl, p);
    }

    // ------------------------------------------------------------------ utilidades

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }

    private static int mod(int v) {
        return ((v % 1440) + 1440) % 1440;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String duration(double h) {
        long total = Math.round(h * 60), hh = total / 60, mm = total % 60;
        return hh > 0 ? hh + " h " + (mm < 10 ? "0" : "") + mm + " min" : mm + " min";
    }
}
