package com.jhonny.mustmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.SparseArray;

/**
 * Servicio en primer plano: mantiene la conexión con el BMS y graba lecturas aunque la pantalla
 * esté apagada o la app cerrada. Android obliga a mostrar una notificación fija mientras funciona;
 * la aprovechamos para enseñar el SOC y la potencia.
 * La pantalla (MainActivity) se engancha con bind() y recibe los mismos eventos de BmsClient.
 */
public class MonitorService extends Service implements BmsClient.Listener {

    public static final String ACTION_STOP = "com.jhonny.mustmanager.STOP";
    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;
    private static final long SAVE_EVERY_MS = 10000;
    private static final long NOTIF_EVERY_MS = 15000;

    public class LocalBinder extends Binder {
        public MonitorService get() {
            return MonitorService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private BmsClient bms;
    private ReadingStore store;
    private AlertManager alerts;
    private SharedPreferences prefs;
    private BmsClient.Listener ui;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;

    // Último estado conocido, para repintar la pantalla al volver a abrir la app.
    private String lastState = "disconnected";
    private String lastMessage;
    private BmsProtocol.Basic lastBasic;
    private BmsProtocol.Cells lastCells;
    private byte[] lastStatus;
    private final SparseArray<String> texts = new SparseArray<String>();
    private long lastSavedAt;
    private long lastNotifAt;

    @Override
    public void onCreate() {
        super.onCreate();
        bms = new BmsClient(this, this);
        store = ReadingStore.get(this);
        alerts = new AlertManager(this);
        prefs = getSharedPreferences("mustmanager", Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Monitor de la batería", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Notificación fija mientras Must Manager está conectado al BMS");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        goForeground("Iniciando…");
        // intent == null: Android reinició el servicio tras cerrarlo. Si estábamos monitorizando, reconectamos.
        if (intent == null && prefs.getBoolean("monitoring", false)) {
            String addr = prefs.getString("lastAddress", null);
            if (addr != null) {
                acquireWakeLock();
                bms.connectTo(addr);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        bms.stop();
        releaseWakeLock();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ API para la pantalla

    public void setUi(BmsClient.Listener listener) {
        ui = listener;
        if (ui == null) return;
        if (lastMessage != null) ui.onState(lastState, lastMessage);
        if (isLive()) {
            for (int i = 0; i < texts.size(); i++) ui.onDeviceText(texts.keyAt(i), texts.valueAt(i));
            if (lastBasic != null) ui.onBasic(lastBasic);
            if (lastCells != null) ui.onCells(lastCells);
            if (lastStatus != null) ui.onStatusBytes(lastStatus);
        }
    }

    public void discover() {
        startSelfForeground();
        bms.discover();
    }

    public void connectTo(String address) {
        startSelfForeground();
        acquireWakeLock();
        prefs.edit().putBoolean("monitoring", true).apply();
        lastSavedAt = 0;
        bms.connectTo(address);
    }

    public void testAlert() {
        alerts.test();
    }

    public void stopMonitoring() {
        prefs.edit().putBoolean("monitoring", false).apply();
        alerts.onStopped();
        bms.stop();
        releaseWakeLock();
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
        stopSelf();
    }

    // ------------------------------------------------------------------ primer plano y notificación

    private void startSelfForeground() {
        if (foreground) return;
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void goForeground(String text) {
        Notification n = buildNotification(text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
        } catch (RuntimeException e) {
            // Android puede negarlo (por ejemplo, si falta el permiso de Bluetooth). Sin primer plano no seguimos.
            stopSelf();
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return nb.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Must Manager")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Desconectar", stopPi).build())
            .build();
    }

    private void updateNotification(String text, boolean force) {
        if (!foreground) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastNotifAt < NOTIF_EVERY_MS) return;
        lastNotifAt = now;
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification(text));
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mustmanager:monitor");
            wakeLock.setReferenceCounted(false);
        }
        // Mantiene la CPU despierta (no la pantalla) para que el sondeo siga con la tablet bloqueada.
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private boolean isLive() {
        return "connected".equals(lastState) || "reconnecting".equals(lastState) || "connecting".equals(lastState);
    }

    // ------------------------------------------------------------------ eventos de BmsClient

    @Override
    public void onState(String state, String message) {
        boolean changed = !state.equals(lastState);
        lastState = state;
        lastMessage = message;
        if ("disconnected".equals(state) || "error".equals(state)) {
            lastBasic = null;
            lastCells = null;
            lastStatus = null;
            texts.clear();
        }
        // Problema de conexión mientras se monitoriza (no cuando el usuario desconecta): alerta si dura más de 5 min.
        if (("reconnecting".equals(state) || "error".equals(state)) && prefs.getBoolean("monitoring", false)) {
            alerts.onConnectionProblem();
        }
        if (!"connected".equals(state) || lastBasic == null) updateNotification(message, changed);
        if (ui != null) ui.onState(state, message);
    }

    @Override
    public void onDeviceFound(String name, String address, int rssi, boolean lastUsed) {
        if (ui != null) ui.onDeviceFound(name, address, rssi, lastUsed);
    }

    @Override
    public void onDeviceText(int key, String text) {
        texts.put(key, text);
        if (ui != null) ui.onDeviceText(key, text);
    }

    @Override
    public void onBasic(BmsProtocol.Basic b) {
        lastBasic = b;
        long now = System.currentTimeMillis();
        if (now - lastSavedAt >= SAVE_EVERY_MS) {
            // dt: lo que representa esta fila. Tras un corte no inventamos datos: como mucho 30 s.
            double dt = lastSavedAt == 0 ? SAVE_EVERY_MS / 1000.0 : Math.min((now - lastSavedAt) / 1000.0, 30.0);
            try {
                store.insert(now, dt, b, lastCells);
            } catch (RuntimeException ignored) {
                // Un fallo al guardar no debe cortar la monitorización.
            }
            lastSavedAt = now;
        }
        double w = b.voltageV * b.currentA;
        String flow = Math.abs(b.currentA) < 0.3 ? "En reposo" : (b.currentA < 0 ? "Descargando " : "Cargando ")
            + Math.round(Math.abs(w)) + " W";
        updateNotification("SOC " + b.soc + " % · " + flow, false);
        alerts.onReading(b);
        if (ui != null) ui.onBasic(b);
    }

    @Override
    public void onCells(BmsProtocol.Cells c) {
        lastCells = c;
        if (ui != null) ui.onCells(c);
    }

    @Override
    public void onStatusBytes(byte[] data) {
        lastStatus = data;
        if (ui != null) ui.onStatusBytes(data);
    }
}
