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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.List;
import java.util.UUID;

/**
 * Conexión BLE con el BMS: busca, conecta, activa notificaciones y pregunta en bucle.
 * Todo se ejecuta en el hilo principal (los callbacks de BLE se reenvían con el Handler),
 * así no hay problemas de concurrencia.
 */
@SuppressLint("MissingPermission")
public class BmsClient {

    public interface Listener {
        /** state: scanning | connecting | connected | reconnecting | disconnected | error */
        void onState(String state, String message);

        /** key 0x0001 = versión de software/hardware; 0x0004 / 0x0005 = números de serie. */
        void onDeviceText(int key, String text);

        void onBasic(BmsProtocol.Basic basic);

        void onCells(BmsProtocol.Cells cells);

        void onStatusBytes(byte[] data);
    }

    private static final UUID SERVICE = UUID.fromString(BmsProtocol.SERVICE_UUID);
    private static final UUID NOTIFY = UUID.fromString(BmsProtocol.NOTIFY_UUID);
    private static final UUID WRITE = UUID.fromString(BmsProtocol.WRITE_UUID);
    private static final UUID CCCD = UUID.fromString(BmsProtocol.CCCD_UUID);

    private static final long SCAN_TIMEOUT_MS = 15000;
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

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar;
    private boolean running;
    private boolean scanning;
    private boolean polling;
    private int cycleIndex;
    private boolean serialRequested;
    private int waitingKey = -1;
    private long lastGoodFrameAt;
    private boolean everGotData;

    public BmsClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ inicio / parada

    public void start() {
        if (running) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            listener.onState("error", "Este dispositivo no tiene Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            listener.onState("error", "El Bluetooth está apagado. Enciéndelo y vuelve a pulsar Conectar.");
            return;
        }
        running = true;
        everGotData = false;
        scan();
    }

    public void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        listener.onState("disconnected", "Desconectado. El BMS queda libre para BMS tools.");
    }

    // ------------------------------------------------------------------ búsqueda

    private void scan() {
        if (!running) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("No puedo buscar dispositivos. ¿Está encendido el Bluetooth?");
            return;
        }
        listener.onState("scanning", "Buscando el BMS…");
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        scanner.startScan(null, settings, scanCallback);
        h.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
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
            String extra = Build.VERSION.SDK_INT < 31 ? " En este Android también hace falta tener activada la Ubicación." : "";
            fail("No encuentro el BMS. Acércate a la batería y comprueba que BMS tools está cerrada "
                + "(el BMS solo admite una conexión a la vez)." + extra);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, final ScanResult result) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (!scanning || !looksLikeBms(result)) return;
                    stopScan();
                    connect(result.getDevice(), nameOf(result));
                }
            });
        }

        @Override
        public void onScanFailed(final int errorCode) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    scanning = false;
                    fail("Error al buscar dispositivos Bluetooth (código " + errorCode + "). Apaga y enciende el Bluetooth.");
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

    private void connect(BluetoothDevice device, String name) {
        listener.onState("connecting", "Conectando con " + (name != null ? name : device.getAddress()) + "…");
        rx.reset();
        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        h.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            closeGatt();
            retryOrFail("El BMS no acepta la conexión. ¿Está BMS tools abierta en algún móvil o tablet?");
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
                        listener.onState("connecting", "Conectado. Preparando la comunicación…");
                        // Pedimos un MTU grande: así cada respuesta llega en una sola notificación.
                        if (!g.requestMtu(247)) g.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        h.removeCallbacks(connectTimeout);
                        closeGatt();
                        retryOrFail("Se perdió la conexión con el BMS.");
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
                    listener.onState("connected", "Conectado. Leyendo datos…");
                    lastGoodFrameAt = System.currentTimeMillis();
                    polling = true;
                    cycleIndex = 0;
                    serialRequested = false;
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
            if (running && gatt == null) scan();
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
            // Al conectar, primero se pide una vez el número de serie (como BMS tools).
            byte[] cmd = serialRequested ? CYCLE[cycleIndex] : BmsProtocol.CMD_DEVICE_INFO;
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
        if (!serialRequested) {
            serialRequested = true;
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
                listener.onState("connected", "Conectado");
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
