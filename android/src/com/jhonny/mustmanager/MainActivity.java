package com.jhonny.mustmanager;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pantalla principal: un WebView con app/index.html (la interfaz).
 * El Bluetooth y el registro viven en MonitorService (siguen con la pantalla apagada);
 * esta actividad se engancha al servicio y reenvía sus eventos a la página.
 * Java → página: window.bms({type: ..., ...})
 * Página → Java: Android.connect() (busca), Android.connectTo(dirección), Android.disconnect(),
 *                Android.version(), Android.getHistory(desde, hasta, intervalo)
 */
public class MainActivity extends Activity implements BmsClient.Listener {

    private static final int REQ_PERMS = 1;
    private WebView web;
    private MonitorService service;
    private boolean pageReady;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MonitorService.LocalBinder) binder).get();
            attach();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Mientras la app está en pantalla, la pantalla no se apaga: es un monitor.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                attach();
            }
        });
        web.setBackgroundColor(0xFF0F1418);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");

        bindService(new Intent(this, MonitorService.class), connection, Context.BIND_AUTO_CREATE);
    }

    /** Cuando la página y el servicio están listos, el servicio repinta el estado actual. */
    private void attach() {
        if (service != null && pageReady) service.setUi(this);
    }

    @Override
    protected void onDestroy() {
        // Cerrar la pantalla NO detiene la monitorización: eso solo lo hace "Desconectar".
        if (service != null) service.setUi(null);
        unbindService(connection);
        web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ puente con la página

    private class Bridge {
        @JavascriptInterface
        public void connect() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Se pide lo que falte: Bluetooth (imprescindible) y, una sola vez, notificaciones.
                    if (!hasPermissions() || shouldAskNotifications()) {
                        getSharedPreferences("mustmanager", MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
                        requestPermissions(requestedPermissions(), REQ_PERMS);
                    } else if (service != null) {
                        service.discover();
                    }
                }
            });
        }

        @JavascriptInterface
        public void connectTo(final String address) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (hasPermissions() && service != null) service.connectTo(address);
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (service != null) service.stopMonitoring();
                }
            });
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "?";
            }
        }

        @JavascriptInterface
        public String getSettings() {
            return Settings.load(MainActivity.this).toJson().toString();
        }

        /** Guarda los ajustes (validados) y devuelve cómo han quedado. */
        @JavascriptInterface
        public String setSettings(String json) {
            Settings s = Settings.fromJson(json);
            s.save(MainActivity.this);
            return s.toJson().toString();
        }

        @JavascriptInterface
        public void testAlert() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (service != null) service.testAlert();
                }
            });
        }

        /** Historial agrupado (ver ReadingStore.buckets). Los números de JS llegan como double. */
        @JavascriptInterface
        public String getHistory(double from, double to, double bucketMs) {
            return ReadingStore.get(MainActivity.this).buckets((long) from, (long) to, (long) bucketMs);
        }
    }

    // ------------------------------------------------------------------ permisos

    /** Imprescindibles: Bluetooth. */
    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    /** Lo que se pide: Bluetooth + notificaciones (opcional: sin él se monitoriza igual, sin notificación visible). */
    private String[] requestedPermissions() {
        String[] base = neededPermissions();
        if (Build.VERSION.SDK_INT < 33) return base;
        String[] all = new String[base.length + 1];
        System.arraycopy(base, 0, all, 0, base.length);
        all[base.length] = Manifest.permission.POST_NOTIFICATIONS;
        return all;
    }

    /** Notificaciones (Android 13+): se piden una vez; si el usuario dice que no, no insistimos. */
    private boolean shouldAskNotifications() {
        return Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !getSharedPreferences("mustmanager", MODE_PRIVATE).getBoolean("askedNotifications", false);
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        if (hasPermissions()) {
            if (service != null) service.discover();
        } else {
            onState("error", "Sin el permiso de \"Dispositivos cercanos\" no puedo conectar con el BMS. "
                + "Actívalo en Ajustes → Aplicaciones → Must Manager → Permisos.");
        }
    }

    // ------------------------------------------------------------------ eventos → página

    private void send(JSONObject msg) {
        if (pageReady) web.evaluateJavascript("window.bms && window.bms(" + msg.toString() + ")", null);
    }

    private static Object num(double v) {
        return Double.isNaN(v) ? JSONObject.NULL : (Object) v;
    }

    @Override
    public void onState(String state, String message) {
        try {
            send(new JSONObject().put("type", "state").put("state", state).put("message", message));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onDeviceFound(String name, String address, int rssi, boolean lastUsed) {
        try {
            send(new JSONObject().put("type", "found").put("name", name == null ? JSONObject.NULL : name)
                .put("address", address).put("rssi", rssi).put("last", lastUsed));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onDeviceText(int key, String text) {
        try {
            send(new JSONObject().put("type", "device").put("key", key).put("text", text));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onBasic(BmsProtocol.Basic b) {
        try {
            send(new JSONObject()
                .put("type", "basic")
                .put("currentA", b.currentA)
                .put("voltageV", b.voltageV)
                .put("remainingAh", b.remainingAh)
                .put("fullAh", b.fullAh)
                .put("designAh", b.designAh)
                .put("soc", b.soc)
                .put("soh", b.soh)
                .put("cycles", b.cycles)
                .put("tempMosC", b.tempMosC)
                .put("tempAmbientC", b.tempAmbientC)
                .put("cellMaxIndex", b.cellMaxIndex)
                .put("cellMaxMv", b.cellMaxMv)
                .put("cellMinIndex", b.cellMinIndex)
                .put("cellMinMv", b.cellMinMv));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onCells(BmsProtocol.Cells c) {
        try {
            JSONArray mv = new JSONArray();
            JSONArray temps = new JSONArray();
            for (int i = 0; i < c.mv.length; i++) {
                mv.put(c.mv[i]);
                temps.put(num(c.tempC[i]));
            }
            send(new JSONObject().put("type", "cells").put("packVoltageV", c.packVoltageV).put("mv", mv).put("tempC", temps));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onStatusBytes(byte[] data) {
        try {
            send(new JSONObject().put("type", "status").put("hex", BmsProtocol.toHex(data)));
        } catch (JSONException ignored) {
        }
    }
}
