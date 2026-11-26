package com.jhonny.mustmanager;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conexión BLE con el BMS.
 * 1) discover(): busca y avisa de cada BMS compatible encontrado (el usuario elige en la lista).
 * 2) connectTo(dirección): conecta con el elegido, activa notificaciones y pregunta en bucle.
 * Si se pierde la conexión, reintenta SOLO con ese mismo dispositivo.
 * Todo se ejecuta en el hilo principal (los callbacks de BLE se reenvían con el Handler).
 */
@SuppressLint("MissingPermission")
public class BmsClient {

    public interface Listener {
        /** state: scanning | select | connecting | connected | reconnecting | disconnected | error */
        void onState(String state, String message);

        /** BMS compatible encontrado durante la búsqueda. */
        void onDeviceFound(String name, String address, int rssi, boolean lastUsed);

        /** key 0x0001 = versión de software/hardware; 0x0004 / 0x0005 = números de serie. */
        void onDeviceText(int key, String text);

        void onBasic(BmsProtocol.Basic basic);

        void onCells(BmsProtocol.Cells cells);

        void onStatusBytes(byte[] data);

        /** Nº de baterías en paralelo según el resumen (0A 00). La app solo muestra la batería 1. */
        void onPackCount(int packs);
    }

    private static final UUID SERVICE = UUID.fromString(BmsProtocol.SERVICE_UUID);
    private static final UUID NOTIFY = UUID.fromString(BmsProtocol.NOTIFY_UUID);
    private static final UUID WRITE = UUID.fromString(BmsProtocol.WRITE_UUID);
    private static final UUID CCCD = UUID.fromString(BmsProtocol.CCCD_UUID);

    private static final long DISCOVER_TIMEOUT_MS = 12000;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long RESPONSE_TIMEOUT_MS = 2500;
    private static final long POLL_PERIOD_MS = 3000;
    private static final long NO_DATA_TIMEOUT_MS = 15000;
    private static final long RECONNECT_DELAY_MS = 5000;

    /** Ciclo de preguntas: solo comandos de la lista blanca. */
    private static final byte[][] CYCLE = {
        BmsProtocol.CMD_PACK_BASIC, BmsProtocol.CMD_PACK_CELLS, BmsProtocol.CMD_PACK_STATUS
    };

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final BmsProtocol.Reassembler rx = new BmsProtocol.Reassembler();
    private final SharedPreferences prefs;
    private final Map<String, String> foundNames = new HashMap<String, String>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar;

    /** true mientras hay una sesión activa con un dispositivo elegido (incluye reintentos). */
    private boolean running;
    private boolean scanning;
    /** true = búsqueda para la lista; false = buscando al dispositivo elegido para reconectar. */
    private boolean discoverMode;
    private String targetAddress;
    private String targetName;
    private boolean polling;
    private int cycleIndex;
    /** Preguntas que se hacen una sola vez al conectar, antes del ciclo (como BMS tools). */
    private static final byte[][] START = { BmsProtocol.CMD_DEVICE_INFO, BmsProtocol.CMD_SUMMARY };
    private int startIndex;
    private int waitingKey = -1;
    private long lastGoodFrameAt;
    private boolean everGotData;

    public BmsClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.prefs = this.ctx.getSharedPreferences("mustmanager", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ API pública

    /** Busca BMS compatibles y los va enviando a la lista. No conecta con ninguno. */
    public void discover() {
        if (!ensureAdapter()) return;
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        foundNames.clear();
        discoverMode = true;
        if (startScan()) {
            listener.onState("scanning", "Buscando dispositivos… Pulsa «Conectar» junto a tu BMS.");
            h.postDelayed(scanTimeout, DISCOVER_TIMEOUT_MS);
        }
    }

    /** Conecta con el dispositivo elegido por el usuario. */
    public void connectTo(String address) {
        if (!ensureAdapter()) return;
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        targetAddress = address;
        targetName = foundNames.containsKey(address) ? foundNames.get(address) : address;
        prefs.edit().putString("lastAddress", address).apply();
        running = true;
        everGotData = false;
        connect(adapter.getRemoteDevice(address), false);
    }

    public void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        listener.onState("disconnected", "Desconectado. El BMS queda libre para BMS tools.");
    }

    // ------------------------------------------------------------------ búsqueda

    private boolean ensureAdapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            listener.onState("error", "Este dispositivo no tiene Bluetooth.");
            return false;
        }
        if (!adapter.isEnabled()) {
            listener.onState("error", "El Bluetooth está apagado. Enciéndelo y vuelve a pulsar Escanear.");
            return false;
        }
        return true;
    }

    private boolean startScan() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("No puedo buscar dispositivos. ¿Está encendido el Bluetooth?");
            return false;
        }
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        scanner.startScan(null, settings, scanCallback);
        return true;
    }

    private void stopScan() {
        h.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private final Runnable scanTimeout = new Runnable() {
        @Override
        public void run() {
            stopScan();
            if (discoverMode) {
                if (foundNames.isEmpty()) {
                    String extra = Build.VERSION.SDK_INT < 31 ? " En este Android también hace falta tener activada la Ubicación." : "";
                    listener.onState("error", "No encuentro ningún BMS. Acércate a la batería y comprueba que BMS tools "
                        + "está cerrada (el BMS solo admite una conexión a la vez)." + extra);
                } else {
                    listener.onState("select", "Búsqueda terminada. Pulsa «Conectar» junto a tu BMS.");
                }
            } else {
                retryOrFail("No encuentro " + targetName + ".");
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, final ScanResult result) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (!scanning || !looksLikeBms(result)) return;
                    String address = result.getDevice().getAddress();
                    String name = nameOf(result);
                    if (discoverMode) {
                        foundNames.put(address, name != null ? name : address);
                        String last = prefs.getString("lastAddress", null);
                        listener.onDeviceFound(name, address, result.getRssi(), address.equals(last));
                    }
                }
            });
        }

        @Override
        public void onScanFailed(final int errorCode) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    scanning = false;
                    if (errorCode == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                        fail("Android limita las búsquedas seguidas. Espera 30 segundos y vuelve a pulsar Escanear.");
                    } else {
                        fail("Error al buscar dispositivos Bluetooth (código " + errorCode + "). Apaga y enciende el Bluetooth.");
                    }
                }
            });
        }
    };

    /** El BMS se anuncia como "PC-XXXX" (XXXX = final de la MAC). También vale si anuncia el servicio FFF0. */
    private static boolean looksLikeBms(ScanResult r) {
        String name = nameOf(r);
        if (name != null && name.startsWith("PC-")) return true;
        ScanRecord rec = r.getScanRecord();
        if (rec != null) {
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null && uuids.contains(new ParcelUuid(SERVICE))) return true;
        }
        return false;
    }

    private static String nameOf(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        String name = rec != null ? rec.getDeviceName() : null;
        if (name == null) {
            try {
                name = r.getDevice().getName();
            } catch (SecurityException ignored) {
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ conexión

    /**
     * auto = false: conexión directa (el usuario acaba de elegir el BMS), con tiempo límite.
     * auto = true: reconexión. Android espera a ese dispositivo concreto y conecta en cuanto aparece,
     * sin búsquedas (que Android limita a 5 cada 30 s y bloquea con la pantalla apagada) y sin tiempo límite.
     */
    private void connect(BluetoothDevice device, boolean auto) {
        rx.reset();
        gatt = device.connectGatt(ctx, auto, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) { // p. ej. Bluetooth apagado: se reintenta sin abandonar
            retryOrFail("El Bluetooth no está disponible.");
            return;
        }
        if (auto) {
            listener.onState("reconnecting", "Esperando a " + targetName + ": se conectará en cuanto esté disponible…");
        } else {
            listener.onState("connecting", "Conectando con " + targetName + "…");
            h.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        }
    }

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            closeGatt();
            retryOrFail(targetName + " no acepta la conexión. ¿Está BMS tools abierta en algún móvil o tablet?");
        }
    };

    private void closeGatt() {
        polling = false;
        waitingKey = -1;
        txChar = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        listener.onState("connecting", "Conectado a " + targetName + ". Preparando la comunicación…");
                        // Pedimos un MTU grande: así cada respuesta llega en una sola notificación.
                        if (!g.requestMtu(247)) g.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        h.removeCallbacks(connectTimeout);
                        closeGatt();
                        retryOrFail("Se perdió la conexión con " + targetName + ".");
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(final BluetoothGatt g, int mtu, int status) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt) g.discoverServices();
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    h.removeCallbacks(connectTimeout);
                    BluetoothGattService s = g.getService(SERVICE);
                    BluetoothGattCharacteristic rxChar = s == null ? null : s.getCharacteristic(NOTIFY);
                    txChar = s == null ? null : s.getCharacteristic(WRITE);
                    BluetoothGattDescriptor cccd = rxChar == null ? null : rxChar.getDescriptor(CCCD);
                    if (cccd == null || txChar == null) {
                        running = false;
                        closeGatt();
                        listener.onState("error", "Este dispositivo no parece un BMS compatible "
                            + "(no tiene el servicio Bluetooth esperado). No se mostrarán datos.");
                        return;
                    }
                    g.setCharacteristicNotification(rxChar, true);
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(cccd);
                    }
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeGatt();
                        retryOrFail("El BMS no aceptó activar las notificaciones (estado " + status + ").");
                        return;
                    }
                    listener.onState("connected", "Conectado a " + targetName + ". Leyendo datos…");
                    lastGoodFrameAt = System.currentTimeMillis();
                    polling = true;
                    cycleIndex = 0;
                    startIndex = 0;
                    // Al activar las notificaciones el BMS manda solo su información; esperamos un poco.
                    h.postDelayed(pollNext, 800);
                    h.postDelayed(watchdog, 5000);
                }
            });
        }

        // Android 13+ llama a esta versión (y no a la antigua, porque no llamamos a super).
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onChunk(g, value);
        }

        // Android 12 y anteriores.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onChunk(g, c.getValue());
        }
    };

    private void onChunk(final BluetoothGatt g, byte[] value) {
        if (value == null) return;
        final byte[] copy = value.clone();
        h.post(new Runnable() {
            @Override
            public void run() {
                if (g != gatt) return;
                for (BmsProtocol.Frame f : rx.feed(copy)) handleFrame(f);
            }
        });
    }

    /** Reintenta solo con el dispositivo elegido. Nunca salta a otro BMS. */
    private void retryOrFail(String message) {
        if (!running) return;
        stopScan();
        listener.onState("reconnecting", message + " Reintentando en 5 s…");
        h.removeCallbacks(retry);
        h.postDelayed(retry, RECONNECT_DELAY_MS);
    }

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (!running || gatt != null || targetAddress == null) return;
            connect(adapter.getRemoteDevice(targetAddress), true);
        }
    };

    private void fail(String message) {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        listener.onState("error", message);
    }

    // ------------------------------------------------------------------ preguntas y respuestas

    private final Runnable pollNext = new Runnable() {
        @Override
        public void run() {
            if (!polling || gatt == null || txChar == null) return;
            // Al conectar, primero se piden una vez el número de serie y el resumen (nº de baterías).
            byte[] cmd = startIndex < START.length ? START[startIndex] : CYCLE[cycleIndex];
            waitingKey = BmsProtocol.keyOf(cmd);
            send(cmd);
            h.postDelayed(responseTimeout, RESPONSE_TIMEOUT_MS);
        }
    };

    private final Runnable responseTimeout = new Runnable() {
        @Override
        public void run() {
            advance();
        }
    };

    private void advance() {
        h.removeCallbacks(responseTimeout);
        h.removeCallbacks(pollNext);
        waitingKey = -1;
        if (startIndex < START.length) {
            startIndex++;
            h.postDelayed(pollNext, 150);
            return;
        }
        cycleIndex++;
        if (cycleIndex >= CYCLE.length) {
            cycleIndex = 0;
            h.postDelayed(pollNext, POLL_PERIOD_MS);
        } else {
            h.postDelayed(pollNext, 150);
        }
    }

    @SuppressWarnings("deprecation")
    private void send(byte[] cmd) {
        // Garantía de solo lectura: nada fuera de la lista blanca sale hacia el BMS.
        if (!BmsProtocol.isWhitelisted(cmd)) throw new IllegalStateException("Comando no permitido");
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(txChar, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            txChar.setValue(cmd);
            gatt.writeCharacteristic(txChar);
        }
    }

    private void handleFrame(BmsProtocol.Frame f) {
        try {
            switch (f.key) {
                case BmsProtocol.KEY_PACK_BASIC:
                    listener.onBasic(BmsProtocol.parseBasic(f.data));
                    break;
                case BmsProtocol.KEY_PACK_CELLS:
                    listener.onCells(BmsProtocol.parseCells(f.data));
                    break;
                case BmsProtocol.KEY_PACK_STATUS:
                    listener.onStatusBytes(f.data);
                    break;
                case BmsProtocol.KEY_SUMMARY:
                    int packs = BmsProtocol.parsePackCount(f.data);
                    if (packs > 0) listener.onPackCount(packs);
                    break;
                default:
                    if ((f.key >> 8) == 0x00) {
                        String text = BmsProtocol.parseText(f.data);
                        if (text.length() > 0) listener.onDeviceText(f.key, text);
                    }
                    break;
            }
            lastGoodFrameAt = System.currentTimeMillis();
            if (!everGotData && (f.key >> 8) == 0x0A) {
                everGotData = true;
                listener.onState("connected", "Conectado a " + targetName);
            }
        } catch (IllegalArgumentException e) {
            // Trama con CRC correcto pero contenido inesperado: puede ser otro firmware. No mostramos basura.
            listener.onState("connected", "Aviso: respuesta del BMS con formato inesperado (" + e.getMessage() + ").");
        }
        if (f.key == waitingKey) advance();
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            if (System.currentTimeMillis() - lastGoodFrameAt > NO_DATA_TIMEOUT_MS) {
                String msg = everGotData
                    ? "El BMS ha dejado de responder."
                    : "El BMS no responde como se esperaba. Puede que este modelo o firmware no sea compatible.";
                closeGatt();
                retryOrFail(msg);
                return;
            }
            h.postDelayed(this, 5000);
        }
    };
}
