package com.jhonny.mustmanager;

import android.content.Context;
import java.util.Calendar;
import org.json.JSONException;
import org.json.JSONObject;

/** Ajustes del usuario (alertas y horario), guardados como JSON en SharedPreferences. */
public final class Settings {
    public boolean socAlerts = true;
    /** El aviso urgente de batería baja suena como una alarma (melodía que sube poco a poco). */
    public boolean socAlarm = true;
    public int socWarn = 30;
    public int socCritical = 20;
    public boolean gridAlerts = true;
    public boolean sunriseAlerts = true;
    public boolean connAlerts = true;
    public String sunrise = "07:00";
    public String sunset = "18:00";
    /**
     * Lugar para calcular el amanecer y el atardecer de cada día (clave de PLACES); "" = horas manuales.
     * Con un lugar, sunrise/sunset se recalculan al cargar los ajustes (ver applySun).
     */
    public String place = "habana";

    /**
     * Capitales de provincia de Cuba: clave, nombre, latitud, longitud (oeste negativa).
     * Dentro de una provincia la diferencia es de pocos minutos. Si se cambia, cambiar también
     * la lista <select id="sPlace"> de app/index.html.
     */
    static final Object[][] PLACES = {
        {"pinar", "Pinar del Río", 22.417, -83.698},
        {"artemisa", "Artemisa", 22.813, -82.763},
        {"habana", "La Habana", 23.133, -82.383},
        {"mayabeque", "Mayabeque", 22.961, -82.151},
        {"matanzas", "Matanzas", 23.041, -81.577},
        {"cienfuegos", "Cienfuegos", 22.146, -80.436},
        {"villaclara", "Villa Clara", 22.406, -79.965},
        {"sancti", "Sancti Spíritus", 21.930, -79.443},
        {"ciego", "Ciego de Ávila", 21.840, -78.762},
        {"camaguey", "Camagüey", 21.381, -77.917},
        {"tunas", "Las Tunas", 20.962, -76.951},
        {"holguin", "Holguín", 20.887, -76.263},
        {"granma", "Granma", 20.379, -76.643},
        {"santiago", "Santiago de Cuba", 20.024, -75.821},
        {"guantanamo", "Guantánamo", 20.144, -75.209},
        {"isla", "Isla de la Juventud", 21.884, -82.801},
    };
    /** Ajuste 38 del inversor: corta la descarga a este SOC. */
    public int cutoffSoc = 15;
    /** Potencia máxima del inversor en W. 0 = automático según la batería (24 V → 3000 W, 48 V → 6000 W). */
    public int inverterW = 0;
    /** Potencia de los paneles en W (de día, cargar por encima de esto solo puede ser la red). */
    public int panelsW = 1020;

    private static final String KEY = "settings";

    public static Settings load(Context ctx) {
        String json = ctx.getSharedPreferences("mustmanager", Context.MODE_PRIVATE).getString(KEY, null);
        Settings s = json == null ? new Settings() : fromJson(json);
        s.applySun(System.currentTimeMillis());
        return s;
    }

    /** Con un lugar elegido, pone en sunrise/sunset las horas de hoy (hora local del dispositivo). */
    public void applySun(long now) {
        Object[] p = findPlace(place);
        if (p == null) return;
        long[] t = Sun.times((Double) p[2], (Double) p[3], now);
        if (t == null) return;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        sunrise = f.format(new java.util.Date(t[0]));
        sunset = f.format(new java.util.Date(t[1]));
    }

    static Object[] findPlace(String key) {
        if (key == null || key.isEmpty()) return null;
        for (Object[] p : PLACES) if (p[0].equals(key)) return p;
        return null;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences("mustmanager", Context.MODE_PRIVATE).edit().putString(KEY, toJson().toString()).apply();
    }

    /** Lee y valida (valores fuera de rango → por defecto o recortados). */
    public static Settings fromJson(String json) {
        Settings s = new Settings();
        try {
            JSONObject o = new JSONObject(json);
            s.socAlerts = o.optBoolean("socAlerts", s.socAlerts);
            s.socAlarm = o.optBoolean("socAlarm", s.socAlarm);
            s.socWarn = clamp(o.optInt("socWarn", s.socWarn), 5, 95);
            s.socCritical = clamp(o.optInt("socCritical", s.socCritical), 1, 90);
            if (s.socCritical >= s.socWarn) s.socCritical = Math.max(1, s.socWarn - 5);
            s.gridAlerts = o.optBoolean("gridAlerts", s.gridAlerts);
            s.sunriseAlerts = o.optBoolean("sunriseAlerts", s.sunriseAlerts);
            s.connAlerts = o.optBoolean("connAlerts", s.connAlerts);
            s.sunrise = time(o.optString("sunrise", s.sunrise), s.sunrise);
            s.sunset = time(o.optString("sunset", s.sunset), s.sunset);
            if (o.has("place")) {
                String pl = o.optString("place", "");
                s.place = findPlace(pl) != null ? pl : "";
            } else {
                // Ajustes de antes de esta opción: con las horas de fábrica, La Habana; si las cambió, manual.
                s.place = "07:00".equals(s.sunrise) && "18:00".equals(s.sunset) ? "habana" : "";
            }
            s.cutoffSoc = clamp(o.optInt("cutoffSoc", s.cutoffSoc), 0, 50);
            int inv = o.optInt("inverterW", s.inverterW);
            s.inverterW = inv <= 0 ? 0 : clamp(inv, 500, 20000);
            s.panelsW = clamp(o.optInt("panelsW", s.panelsW), 100, 30000);
        } catch (JSONException ignored) {
        }
        return s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("socAlerts", socAlerts).put("socAlarm", socAlarm).put("socWarn", socWarn).put("socCritical", socCritical)
                .put("gridAlerts", gridAlerts).put("sunriseAlerts", sunriseAlerts).put("connAlerts", connAlerts)
                .put("sunrise", sunrise).put("sunset", sunset).put("place", place).put("cutoffSoc", cutoffSoc)
                .put("inverterW", inverterW).put("panelsW", panelsW);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** De noche = entre el atardecer y el amanecer (no hay sol: lo que carga solo puede ser la red). */
    public boolean isNight(long now) {
        int m = minutesOfDay(now);
        int rise = toMinutes(sunrise), set = toMinutes(sunset);
        return m >= set || m < rise;
    }

    public long nextSunrise(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int rise = toMinutes(sunrise);
        c.set(Calendar.HOUR_OF_DAY, rise / 60);
        c.set(Calendar.MINUTE, rise % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static int minutesOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static String time(String v, String def) {
        return v != null && v.matches("([01]\\d|2[0-3]):[0-5]\\d") ? v : def;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
