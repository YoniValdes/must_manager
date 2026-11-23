package com.jhonny.mustmanager;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pantalla principal: un WebView con app/index.html (la interfaz) y el puente con BmsClient (el Bluetooth).
 * Java → página: window.bms({type: ..., ...})
 * Página → Java: Android.connect(), Android.disconnect(), Android.version()
 */
public class MainActivity extends Activity implements BmsClient.Listener {

    private static final int REQ_PERMS = 1;
    private WebView web;
    private BmsClient bms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Mientras la app está abierta la pantalla no se apaga: es un monitor.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(0xFF0F1418);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");

        bms = new BmsClient(this, this);
    }

    @Override
    protected void onDestroy() {
        bms.stop();
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
                    if (hasPermissions()) {
                        bms.start();
                    } else {
                        requestPermissions(neededPermissions(), REQ_PERMS);
                    }
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    bms.stop();
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
    }

    // ------------------------------------------------------------------ permisos

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
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
            bms.start();
        } else {
            onState("error", "Sin el permiso de \"Dispositivos cercanos\" no puedo conectar con el BMS. "
                + "Actívalo en Ajustes → Aplicaciones → Must Manager → Permisos.");
        }
    }

    // ------------------------------------------------------------------ BmsClient → página

    private void send(JSONObject msg) {
        web.evaluateJavascript("window.bms && window.bms(" + msg.toString() + ")", null);
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
