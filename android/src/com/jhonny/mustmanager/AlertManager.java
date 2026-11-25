package com.jhonny.mustmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Alertas con sonido (canal propio, configurable en los ajustes de Android):
 * SOC bajo, llegó/se fue la corriente, no llegas al amanecer y conexión perdida.
 * Vive en MonitorService, así que funciona con la pantalla apagada.
 */
final class AlertManager {

    private static final String CHANNEL = "alertas";
    private static final int ID_SOC = 100, ID_GRID = 101, ID_SUNRISE = 102, ID_CONN = 103, ID_ALARM = 104, ID_TEST = 199;
    /** Media para la previsión del amanecer: 30 min (abarca un ciclo completo de la nevera, como la pantalla). */
    private static final long SUNRISE_AVG_MS = 30 * 60000L;
    private static final long ALARM_TEST_MS = 15000L;
    private static final long MIN = 60000L, HOUR = 3600000L;

    // Detección de la red: cuánto tiene que durar la condición y con qué potencia (W, media de 1 min).
    private static final long GRID_CONFIRM_MS = MIN;
    static final double GRID_ON_NIGHT_W = 150;   // de noche cualquier carga real es la red
    static final double GRID_ON_DAY_MARGIN = 1.03; // de día, algo más de lo que pueden dar los paneles
    private static final double GRID_OFF_W = -100;       // la batería vuelve a descargar → se fue la red
    private static final long CONN_LOST_MS = 5 * MIN;

    private final Context ctx;
    private final NotificationManager nm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final AlarmPlayer alarm;

    /** Ventana de 30 min de lecturas: {t, corriente A, potencia W}. */
    private final ArrayDeque<double[]> window = new ArrayDeque<double[]>();

    private boolean socWarned, socCriticalWarned;
    private boolean gridOn;
    private long gridSince, gridCondSince, dischargeSince;
    private int gridSoc0;
    /** Horas del apagón guardadas (sobreviven a cerrar la app); de aquí sale también gridOn al arrancar. */
    private final GridWatch grid;
    private boolean sunriseAlerted;
    private long sunriseAlertAt, sunriseReachAlerted;
    private boolean connPending, connAlerted;

    AlertManager(Context ctx) {
        this.ctx = ctx;
        this.nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        this.alarm = new AlarmPlayer(ctx);
        this.grid = new GridWatch(ctx);
        grid.recoverFromHistory(System.currentTimeMillis()); // recién instalada: lo que diga la gráfica
        gridOn = grid.gridOn();
        gridSince = grid.onSince();
        gridSoc0 = grid.onSoc();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Alertas de la batería", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Batería baja, llegada y corte de la corriente, previsión del amanecer y conexión");
            nm.createNotificationChannel(ch);
        }
    }

    // ------------------------------------------------------------------ entradas

    void onReading(BmsProtocol.Basic b) {
        long now = System.currentTimeMillis();
        Settings s = Settings.load(ctx);
        // Tras un hueco sin lecturas, lo que se estaba confirmando antes ya no vale.
        if (!window.isEmpty() && now - window.peekLast()[0] > GridWatch.GAP_MS) {
            gridCondSince = 0;
            dischargeSince = 0;
        }
        window.addLast(new double[]{now, b.currentA, b.voltageV * b.currentA});
        while (!window.isEmpty() && now - window.peekFirst()[0] > SUNRISE_AVG_MS) window.removeFirst();

        connectionOk();
        if (s.socAlerts) checkSoc(b, s);
        grid.onReading(now, b.soc, b.currentA);
        checkGrid(b, s, now, s.gridAlerts);
        if (s.sunriseAlerts) checkSunrise(b, s, now);
        else sunriseAlerted = false;
    }

    /** Media de los últimos 30 min {corriente A, potencia W, ms cubiertos}, o null. La usa también el widget. */
    double[] average30() {
        return average(System.currentTimeMillis(), SUNRISE_AVG_MS);
    }

    /** El BMS no responde o se perdió la conexión (y el usuario no pidió desconectar). */
    void onConnectionProblem() {
        if (connPending || connAlerted || !Settings.load(ctx).connAlerts) return;
        connPending = true;
        h.postDelayed(connLost, CONN_LOST_MS);
    }

    /** El usuario pulsó Desconectar: nada de avisos de conexión ni estados a medias. */
    void onStopped() {
        h.removeCallbacks(connLost);
        connPending = false;
        connAlerted = false;
        nm.cancel(ID_CONN);
        stopAlarm();
        window.clear();
        gridCondSince = 0;
        dischargeSince = 0;
    }

    void test() {
        alert(ID_TEST, "🔔 Prueba de alerta", "Así sonarán las alertas de Must Manager. "
            + "Puedes cambiar el sonido en Ajustes → Notificaciones → Must Manager → Alertas de la batería.");
    }

    /** Prueba de la alarma: suena 15 s con su notificación y su botón Detener. */
    void testAlarm() {
        alarmNotification("⏰ Prueba de alarma", "Así sonará la alarma de batería baja: empieza bajito y sube poco a poco. "
            + "Usa el sonido de alarma de tu reloj de Android.");
        alarm.stop();
        alarm.start(ALARM_TEST_MS);
    }

    /** Botón "Detener alarma" de la notificación, deslizarla, o el usuario pulsó Desconectar. */
    void stopAlarm() {
        alarm.stop();
        nm.cancel(ID_ALARM);
    }

    // ------------------------------------------------------------------ SOC bajo

    private void checkSoc(BmsProtocol.Basic b, Settings s) {
        if (b.soc <= s.socCritical && !socCriticalWarned) {
            socCriticalWarned = true;
            socWarned = true;
            String text = "Nivel crítico: el inversor cortará al " + s.cutoffSoc + " %. Apaga lo que no sea imprescindible.";
            if (s.socAlarm) {
                // Alarma suave en vez del pitido del equipo: notificación con "Detener alarma" y sonido en bucle.
                alarmNotification("🪫 Batería al " + b.soc + " %", text);
                alarm.start(AlarmPlayer.MAX_MS);
            } else {
                alert(ID_SOC, "🪫 Batería al " + b.soc + " %", text);
            }
        } else if (b.soc <= s.socWarn && !socWarned) {
            socWarned = true;
            alert(ID_SOC, "🔋 Batería al " + b.soc + " %", "Ha bajado del " + s.socWarn + " %. Consumo actual: "
                + Math.round(Math.abs(b.voltageV * b.currentA)) + " W.");
        }
        // Se rearman al recuperar 5 puntos, para no repetir el aviso con cada oscilación.
        if (b.soc >= s.socWarn + 5) socWarned = false;
        if (b.soc >= s.socCritical + 5) socCriticalWarned = false;
        // Si la batería se recupera (llegó la red o el sol), la alarma ya no tiene sentido.
        if (b.soc > s.socCritical && b.currentA > 0.3 && alarm.isPlaying()) stopAlarm();
    }

    // ------------------------------------------------------------------ red eléctrica

    /**
     * Detecta la llegada y el corte de la red siempre (el apagón de la pantalla y el widget lo necesitan);
     * la notificación solo sale si el usuario tiene activada la alerta.
     */
    private void checkGrid(BmsProtocol.Basic b, Settings s, long now, boolean notify) {
        double[] avg = average(now, MIN);
        if (avg == null || avg[2] < 50000) return; // menos de ~1 min de datos
        double w = avg[1];
        // Sin red conocida y descargando: si no se sabe nada de antes (recién instalada), apagón de hora desconocida.
        if (!gridOn && w < GRID_OFF_W) {
            if (dischargeSince == 0) dischargeSince = now;
            if (now - dischargeSince >= GRID_CONFIRM_MS) grid.onDischarging(now);
        } else {
            dischargeSince = 0;
        }
        boolean cond = gridOn ? w < GRID_OFF_W : w > (s.isNight(now) ? GRID_ON_NIGHT_W : s.panelsW * GRID_ON_DAY_MARGIN);
        if (!cond) {
            gridCondSince = 0;
            return;
        }
        if (gridCondSince == 0) gridCondSince = now;
        if (now - gridCondSince < GRID_CONFIRM_MS) return;
        long condStart = gridCondSince;
        gridCondSince = 0;
        if (!gridOn) {
            gridOn = true;
            gridSince = now - GRID_CONFIRM_MS;
            gridSoc0 = b.soc;
            grid.onGridOn(now, gridSince, b.soc);
            if (notify) alert(ID_GRID, "⚡ Llegó la corriente", "Cargando a " + Math.round(w) + " W · SOC " + b.soc + " %");
        } else {
            gridOn = false;
            grid.onGridOff(now, condStart);
            if (notify) alert(ID_GRID, "🔌 Se fue la corriente", "Estuvo " + duration(now - gridSince) + " · SOC " + gridSoc0
                + " → " + b.soc + " %. Ahora consumiendo " + Math.round(-w) + " W de la batería.");
        }
    }

    // ------------------------------------------------------------------ amanecer

    private void checkSunrise(BmsProtocol.Basic b, Settings s, long now) {
        if (!s.isNight(now)) {
            sunriseAlerted = false;
            return;
        }
        double[] avg = average(now, SUNRISE_AVG_MS);
        if (avg == null || avg[2] < 4 * MIN || avg[0] > -0.3) return; // necesita al menos ~4 min descargando
        double usableAh = b.remainingAh - b.fullAh * s.cutoffSoc / 100.0;
        if (usableAh <= 0) return; // de eso ya avisa el SOC crítico
        long reach = now + (long) (usableAh / -avg[0] * HOUR);
        long sunrise = s.nextSunrise(now);
        if (reach < sunrise) {
            boolean first = !sunriseAlerted;
            boolean worse = reach < sunriseReachAlerted - 30 * MIN && now - sunriseAlertAt >= 30 * MIN;
            if (first || worse) {
                double hoursLeft = (sunrise - now) / (double) HOUR;
                long maxW = Math.round(usableAh * b.voltageV / hoursLeft);
                alert(ID_SUNRISE, "🌙 No llegas al amanecer", "A este ritmo la batería llega al " + s.cutoffSoc + " % a las "
                    + clock.format(new Date(reach)) + ". Para llegar a las " + s.sunrise + ", baja a unos " + maxW
                    + " W de media (ahora " + Math.round(-avg[1]) + " W).");
                sunriseAlerted = true;
                sunriseAlertAt = now;
                sunriseReachAlerted = reach;
            }
        } else if (sunriseAlerted && reach > sunrise + 15 * MIN) {
            sunriseAlerted = false; // ya llega con margen: se rearma por si vuelve a empeorar
        }
    }

    // ------------------------------------------------------------------ conexión

    private final Runnable connLost = new Runnable() {
        @Override
        public void run() {
            connPending = false;
            connAlerted = true;
            alert(ID_CONN, "📡 Sin conexión con el BMS", "Hace más de 5 min que no llegan datos: no se está grabando. "
                + "Acércate a la batería o comprueba que BMS tools está cerrada.");
        }
    };

    private void connectionOk() {
        if (connPending) {
            h.removeCallbacks(connLost);
            connPending = false;
        }
        if (connAlerted) {
            connAlerted = false;
            alert(ID_CONN, "📡 Conexión recuperada", "Vuelven a llegar datos del BMS.");
        }
    }

    // ------------------------------------------------------------------ utilidades

    /** Media en los últimos spanMs: {corriente A, potencia W, ms cubiertos}, o null si no hay datos. */
    private double[] average(long now, long spanMs) {
        double a = 0, w = 0, first = now;
        int n = 0;
        Iterator<double[]> it = window.descendingIterator();
        while (it.hasNext()) {
            double[] r = it.next();
            if (now - r[0] > spanMs) break;
            a += r[1];
            w += r[2];
            first = r[0];
            n++;
        }
        return n == 0 ? null : new double[]{a / n, w / n, now - first};
    }

    private static String duration(long ms) {
        long m = Math.round(ms / (double) MIN);
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    /** Notificación de la alarma: botón "Detener alarma" y, al deslizarla, también se detiene. */
    private void alarmNotification(String title, String text) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        Intent stop = new Intent(ctx, MonitorService.class).setAction(MonitorService.ACTION_STOP_ALARM);
        PendingIntent stopPi = PendingIntent.getService(ctx, ID_ALARM, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(ctx, ID_ALARM, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(ID_ALARM, nb.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setDeleteIntent(stopPi)
            .addAction(new Notification.Action.Builder(null, "Detener alarma", stopPi).build())
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build());
    }

    @SuppressWarnings("deprecation")
    private void alert(int id, String title, String text) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        if (Build.VERSION.SDK_INT < 26) {
            nb.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(id, nb.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build());
    }
}
