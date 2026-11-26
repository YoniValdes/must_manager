package com.jhonny.mustmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Apagón: desde cuándo no hay corriente de la calle (o cuándo volvió).
 *
 * Con el inversor en UTI, mientras hay red la batería nunca se descarga: el paso a descargar marca el
 * apagón. La detección (media de 1 min sostenida 1 min) la hace AlertManager, la misma que la alerta
 * "Se fue la corriente"; aquí solo se guardan las horas en SharedPreferences para que sobrevivan a
 * cerrar la app, y se calcula qué se puede afirmar cuando hubo un hueco sin lecturas.
 *
 * Estados (los que espera index.html en {type:"grid"}):
 *   ""        nada sabido todavía (recién instalada)
 *   "on"      hay corriente
 *   "out"     apagón desde since; lastSeen > 0 si cayó durante un hueco (app cerrada): entre lastSeen y since
 *   "back"    volvió en back tras el apagón que empezó en since (se enseña unas horas)
 *   "unknown" hay apagón, pero no se puede saber desde cuándo
 */
final class GridWatch {

    private static final String PREFS = "grid";
    /** Más de esto sin lecturas = hueco: no sabemos qué pasó en medio. */
    static final long GAP_MS = 5 * 60000L;
    /** Cuánto se ve "Volvió la corriente" (la red suele durar unas 3 h). */
    static final long BACK_SHOW_MS = 3 * 3600000L;
    /** Con la red puesta la batería no se descarga: por encima de esto (A) la lectura cuenta como "con red". */
    private static final double DISCHARGING_A = -0.5;
    private static final long SAVE_EVERY_MS = 30000L;

    /** Hasta dónde se mira el historial si no se sabe nada (la red suele llegar cada ~20 h). */
    private static final long HISTORY_MS = 36 * 3600000L;

    private final SharedPreferences p;
    private final ReadingStore store;
    private final Settings settings;
    private String state;
    private long since, lastSeen, back, onSince, lastGridSeen, lastReading, savedAt;
    private int lastSoc, onSoc;

    GridWatch(Context ctx) {
        p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        store = ReadingStore.get(ctx);
        settings = Settings.load(ctx);
        state = p.getString("state", "");
        since = p.getLong("since", 0);
        lastSeen = p.getLong("lastSeen", 0);
        back = p.getLong("back", 0);
        onSince = p.getLong("onSince", 0);
        onSoc = p.getInt("onSoc", 0);
        lastGridSeen = p.getLong("lastGridSeen", 0);
        lastReading = p.getLong("lastReading", 0);
        lastSoc = p.getInt("lastSoc", 0);
    }

    /** ¿Hay corriente según lo último que se sabe? (AlertManager arranca con esto tras reiniciarse). */
    boolean gridOn() {
        return "on".equals(state) || "back".equals(state);
    }

    long onSince() {
        return onSince;
    }

    int onSoc() {
        return onSoc;
    }

    /** Cada lectura del BMS, antes de la detección. */
    void onReading(long now, int soc, double currentA) {
        boolean dirty = false;
        expireBack(now);
        if (lastReading > 0 && now - lastReading > GAP_MS && "out".equals(state) && soc >= lastSoc + 3) {
            // Durante el hueco la batería se cargó: pudo volver la red y volver a irse. La hora ya no vale.
            setUnknown();
            dirty = true;
        }
        // Última lectura con red. Si la app estuvo cerrada, la primera lectura al volver ya descarga y esto
        // se queda con la hora de antes del hueco: así se sabe que el corte cayó entre esa hora y ahora.
        if (gridOn() && currentA > DISCHARGING_A) lastGridSeen = now;
        lastReading = now;
        lastSoc = soc;
        if (dirty || now - savedAt >= SAVE_EVERY_MS) save(now);
    }

    /** Se fue la corriente: la batería descarga desde condStart (confirmado por AlertManager). */
    void onGridOff(long now, long condStart) {
        boolean gap = lastGridSeen == 0 || condStart - lastGridSeen > GAP_MS;
        state = "out";
        // Sin hueco, la última lectura con red es la hora más fiel (la media de 1 min llega algo tarde).
        since = gap ? condStart : lastGridSeen;
        lastSeen = gap && lastGridSeen > 0 ? lastGridSeen : 0;
        back = 0;
        save(now);
    }

    /** Llegó la corriente en t. */
    void onGridOn(long now, long t, int soc) {
        if ("out".equals(state)) {
            state = "back";
            back = t;
        } else {
            state = "on"; // tras "unknown" no hay duración que contar
            since = 0;
        }
        lastSeen = 0;
        onSince = t;
        onSoc = soc;
        lastGridSeen = now;
        save(now);
    }

    /** La batería descarga y no hay nada sabido de antes: se busca en el historial y, si no está, hora desconocida. */
    void onDischarging(long now) {
        if (state.isEmpty()) {
            if (!fromHistory(now)) setUnknown();
            save(now);
        }
    }

    /** Sin nada sabido o con hora desconocida (p. ej. recién instalada): intenta sacarlo del historial. */
    void recoverFromHistory(long now) {
        if ((state.isEmpty() || "unknown".equals(state)) && fromHistory(now)) save(now);
    }

    /**
     * Busca en las lecturas guardadas (36 h) la última vez que la red cargó la batería, con el mismo criterio
     * que la detección en vivo (de noche > 150 W; de día, más de lo que pueden dar los paneles), y la primera
     * descarga después: ahí se fue la corriente. Así no hace falta esperar al siguiente apagón.
     */
    private boolean fromHistory(long now) {
        if (store == null || settings == null) return false;
        long evidence = 0;
        Cursor c = null;
        try {
            c = store.flow(now - HISTORY_MS, true);
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                double w = c.getDouble(2);
                if (w > (settings.isNight(ts) ? AlertManager.GRID_ON_NIGHT_W : settings.panelsW * AlertManager.GRID_ON_DAY_MARGIN)) {
                    evidence = ts;
                    break;
                }
            }
            c.close();
            if (evidence == 0) return false;
            // Desde esa carga, hacia delante: la última lectura sin descargar (con red) y la primera descargando.
            long lastGrid = evidence, firstDis = 0;
            c = store.flow(evidence, false);
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                if (c.getDouble(1) < DISCHARGING_A) {
                    firstDis = ts;
                    break;
                }
                lastGrid = ts;
            }
            if (firstDis == 0) {
                state = "on"; // desde entonces no ha descargado: sigue habiendo red
                since = 0;
                lastSeen = 0;
                onSince = evidence;
                lastGridSeen = lastGrid;
                return true;
            }
            boolean gap = firstDis - lastGrid > GAP_MS;
            state = "out";
            since = gap ? firstDis : lastGrid;
            lastSeen = gap ? lastGrid : 0;
            back = 0;
            lastGridSeen = lastGrid;
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Estado para la página: {type:"grid", state, since, lastSeen, back}. Lee lo guardado (sin escribir nada,
     * para no pisar lo que lleva el servicio).
     */
    static JSONObject json(Context ctx, long now) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String st = p.getString("state", "");
        long back = p.getLong("back", 0);
        if (st.isEmpty() || ("back".equals(st) && now - back > BACK_SHOW_MS)) st = "on";
        JSONObject o = new JSONObject();
        try {
            o.put("type", "grid").put("state", st).put("since", p.getLong("since", 0))
                .put("lastSeen", p.getLong("lastSeen", 0)).put("back", back);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** "Ya volvió" con el servicio parado (no hay nadie que lleve el estado en memoria): se escribe directamente. */
    static void markBackOffline(Context ctx, long now) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String st = p.getString("state", "");
        if ("on".equals(st) || "back".equals(st)) return;
        p.edit().putString("state", "out".equals(st) ? "back" : "on").putLong("back", now).putLong("lastSeen", 0)
            .putLong("onSince", now).putLong("lastGridSeen", now).apply();
    }

    /** Línea corta para el widget, o null si no hay nada que contar. {texto, 1 = rojo / 0 = azul}. */
    static Object[] widgetLine(Context ctx, long now) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String st = p.getString("state", "");
        long since = p.getLong("since", 0), lastSeen = p.getLong("lastSeen", 0), back = p.getLong("back", 0);
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
        if ("unknown".equals(st)) return new Object[]{"⚡ Sin corriente · hora desconocida", 1};
        if ("out".equals(st) && since > 0) {
            String dur = duration(now - since);
            return new Object[]{lastSeen > 0 ? "⚡ Sin corriente · al menos " + dur
                : "⚡ Sin corriente desde las " + clock.format(new Date(since)) + " · " + dur, 1};
        }
        if ("back".equals(st) && back > 0 && now - back <= BACK_SHOW_MS) {
            return new Object[]{"🔌 Volvió la corriente a las " + clock.format(new Date(back)), 0};
        }
        return null;
    }

    // ------------------------------------------------------------------ utilidades

    private void setUnknown() {
        state = "unknown";
        since = 0;
        lastSeen = 0;
        back = 0;
    }

    private void expireBack(long now) {
        if ("back".equals(state) && now - back > BACK_SHOW_MS) {
            state = "on";
            since = 0;
            back = 0;
            save(now);
        }
    }

    private void save(long now) {
        savedAt = now;
        p.edit().putString("state", state).putLong("since", since).putLong("lastSeen", lastSeen).putLong("back", back)
            .putLong("onSince", onSince).putInt("onSoc", onSoc).putLong("lastGridSeen", lastGridSeen)
            .putLong("lastReading", lastReading).putInt("lastSoc", lastSoc).apply();
    }

    private static String duration(long ms) {
        long m = Math.max(0, Math.round(ms / 60000.0));
        return m >= 60 ? (m / 60) + " h " + (m % 60 < 10 ? "0" : "") + (m % 60) + " min" : m + " min";
    }
}
