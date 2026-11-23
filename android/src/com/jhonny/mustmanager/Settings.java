package com.jhonny.mustmanager;

import android.content.Context;
import java.util.Calendar;
import org.json.JSONException;
import org.json.JSONObject;

/** Ajustes del usuario (alertas y horario), guardados como JSON en SharedPreferences. */
public final class Settings {
    public boolean socAlerts = true;
    public int socWarn = 30;
    public int socCritical = 20;
    public boolean gridAlerts = true;
    public boolean sunriseAlerts = true;
    public boolean connAlerts = true;
    public String sunrise = "07:00";
    public String sunset = "18:00";
    /** Ajuste 38 del inversor: corta la descarga a este SOC. */
    public int cutoffSoc = 15;

    private static final String KEY = "settings";

    public static Settings load(Context ctx) {
        String json = ctx.getSharedPreferences("mustmanager", Context.MODE_PRIVATE).getString(KEY, null);
        return json == null ? new Settings() : fromJson(json);
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
            s.socWarn = clamp(o.optInt("socWarn", s.socWarn), 5, 95);
            s.socCritical = clamp(o.optInt("socCritical", s.socCritical), 1, 90);
            if (s.socCritical >= s.socWarn) s.socCritical = Math.max(1, s.socWarn - 5);
            s.gridAlerts = o.optBoolean("gridAlerts", s.gridAlerts);
            s.sunriseAlerts = o.optBoolean("sunriseAlerts", s.sunriseAlerts);
            s.connAlerts = o.optBoolean("connAlerts", s.connAlerts);
            s.sunrise = time(o.optString("sunrise", s.sunrise), s.sunrise);
            s.sunset = time(o.optString("sunset", s.sunset), s.sunset);
            s.cutoffSoc = clamp(o.optInt("cutoffSoc", s.cutoffSoc), 0, 50);
        } catch (JSONException ignored) {
        }
        return s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("socAlerts", socAlerts).put("socWarn", socWarn).put("socCritical", socCritical)
                .put("gridAlerts", gridAlerts).put("sunriseAlerts", sunriseAlerts).put("connAlerts", connAlerts)
                .put("sunrise", sunrise).put("sunset", sunset).put("cutoffSoc", cutoffSoc);
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
