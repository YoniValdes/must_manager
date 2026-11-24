package com.jhonny.mustmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Protocolo binario del BMS (ver docs/protocolo_bms.md).
 * No usa nada de Android para poder probarlo en el PC (tools/ProtocolTest.java).
 *
 * Trama: 9A | 00 00 | GRP | CMD | 00 | LEN_H LEN_L | DATOS | CRC_H CRC_L | 9D
 * CRC-16/MODBUS desde el 9A hasta el último byte de DATOS, en big-endian.
 */
public final class BmsProtocol {
    private BmsProtocol() {}

    public static final String SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String NOTIFY_UUID = "0000fff1-0000-1000-8000-00805f9b34fb";
    public static final String WRITE_UUID = "0000fff2-0000-1000-8000-00805f9b34fb";
    public static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    public static final int KEY_INFO_VERSION = 0x0001;
    public static final int KEY_SUMMARY = 0x0A00;
    public static final int KEY_PACK_BASIC = 0x0A01;
    public static final int KEY_PACK_CELLS = 0x0A02;
    public static final int KEY_PACK_STATUS = 0x0A03;

    // ---------------------------------------------------------------------------------
    // LISTA BLANCA. Son las únicas tramas que la app puede enviar al BMS: todas de lectura,
    // copiadas byte a byte de las que envía BMS tools. Nunca se construyen tramas nuevas.
    // ---------------------------------------------------------------------------------
    public static final byte[] CMD_DEVICE_INFO = hex("9A 00 00 00 04 00 00 00 28 C8 9D");
    public static final byte[] CMD_SUMMARY = hex("9A 00 00 0A 00 00 00 00 19 51 9D");
    public static final byte[] CMD_PACK_BASIC = hex("9A 00 00 0A 01 00 00 02 01 01 1B 9C 9D");
    public static final byte[] CMD_PACK_CELLS = hex("9A 00 00 0A 02 00 00 02 01 01 28 9C 9D");
    public static final byte[] CMD_PACK_STATUS = hex("9A 00 00 0A 03 00 00 02 01 01 F9 9D 9D");

    private static final byte[][] WHITELIST = {
        CMD_DEVICE_INFO, CMD_SUMMARY, CMD_PACK_BASIC, CMD_PACK_CELLS, CMD_PACK_STATUS
    };

    public static boolean isWhitelisted(byte[] cmd) {
        for (byte[] w : WHITELIST) {
            if (Arrays.equals(w, cmd)) return true;
        }
        return false;
    }

    /** Grupo y comando de una trama (por ejemplo 0x0A01). */
    public static int keyOf(byte[] frame) {
        return ((frame[3] & 0xFF) << 8) | (frame[4] & 0xFF);
    }

    public static int crc16Modbus(byte[] d, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= d[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc;
    }

    // ---------------------------------------------------------------------------------
    // Reensamblado: las notificaciones BLE pueden llegar troceadas o con basura.
    // Solo se aceptan tramas completas con CRC correcto.
    // ---------------------------------------------------------------------------------
    public static final class Frame {
        public final int key;
        public final byte[] data;

        Frame(int key, byte[] data) {
            this.key = key;
            this.data = data;
        }
    }

    public static final class Reassembler {
        private static final int MAX_DATA = 512;
        private byte[] buf = new byte[256];
        private int len = 0;
        public int rejected = 0;

        public void reset() {
            len = 0;
        }

        public List<Frame> feed(byte[] chunk) {
            List<Frame> out = new ArrayList<Frame>();
            if (len + chunk.length > 4096) len = 0; // algo va muy mal: empezar de cero
            if (len + chunk.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + chunk.length));
            System.arraycopy(chunk, 0, buf, len, chunk.length);
            len += chunk.length;

            while (true) {
                int start = -1;
                for (int i = 0; i < len; i++) {
                    if ((buf[i] & 0xFF) == 0x9A) {
                        start = i;
                        break;
                    }
                }
                if (start < 0) {
                    len = 0;
                    break;
                }
                if (start > 0) shift(start);
                if (len < 8) break;
                int dataLen = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
                if (dataLen > MAX_DATA) {
                    shift(1);
                    rejected++;
                    continue;
                }
                int total = 8 + dataLen + 3;
                if (len < total) break;
                int crc = ((buf[8 + dataLen] & 0xFF) << 8) | (buf[9 + dataLen] & 0xFF);
                boolean ok = (buf[total - 1] & 0xFF) == 0x9D && crc16Modbus(buf, 0, 8 + dataLen) == crc;
                if (ok) {
                    out.add(new Frame(keyOf(buf), Arrays.copyOfRange(buf, 8, 8 + dataLen)));
                    shift(total);
                } else {
                    shift(1);
                    rejected++;
                }
            }
            return out;
        }

        private void shift(int n) {
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }
    }

    // ---------------------------------------------------------------------------------
    // Decodificación de respuestas
    // ---------------------------------------------------------------------------------

    /** Voltaje de pack aceptado: de 8S casi vacía (~20 V) a 16S llena (~58,4 V), con margen. */
    public static final double MIN_PACK_V = 10, MAX_PACK_V = 70;

    /** 0A 01: datos básicos del pack. */
    public static final class Basic {
        public double currentA, voltageV, remainingAh, fullAh, designAh;
        public int soc, soh, cycles;
        public double independentV, independentA;
        public int tempMaxSensor, tempMinSensor;
        public double tempMaxC, tempMinC, tempMosC, tempAmbientC;
        public int cellMaxIndex, cellMaxMv, cellMinIndex, cellMinMv;
    }

    public static Basic parseBasic(byte[] d) {
        require(d, 43, "datos básicos");
        Basic b = new Basic();
        b.currentA = s16(d, 1) / 100.0;
        b.voltageV = u16(d, 3) / 100.0;
        b.remainingAh = u16(d, 5) / 100.0;
        b.fullAh = u16(d, 7) / 100.0;
        b.designAh = u16(d, 9) / 100.0;
        b.soc = d[11] & 0xFF;
        b.soh = d[12] & 0xFF;
        b.cycles = u16(d, 13);
        b.independentV = u16(d, 23) / 100.0;
        b.independentA = s16(d, 25) / 100.0;
        b.tempMaxSensor = d[27] & 0xFF;
        b.tempMaxC = kelvin(u16(d, 28));
        b.tempMinSensor = d[30] & 0xFF;
        b.tempMinC = kelvin(u16(d, 31));
        b.tempMosC = kelvin(u16(d, 33));
        b.tempAmbientC = kelvin(u16(d, 35));
        b.cellMaxIndex = d[37] & 0xFF;
        b.cellMaxMv = u16(d, 38);
        b.cellMinIndex = d[40] & 0xFF;
        b.cellMinMv = u16(d, 41);
        // Rango amplio: baterías de 24 V (8S, HBP3000 LV) y de 48 V (16S, HBP3300 TLV y similares).
        if (b.soc > 100) throw new IllegalArgumentException("SOC fuera de rango: " + b.soc + " %");
        if (b.voltageV < MIN_PACK_V || b.voltageV > MAX_PACK_V) {
            throw new IllegalArgumentException("voltaje fuera de rango: " + b.voltageV + " V");
        }
        return b;
    }

    /**
     * 0A 00: resumen del sistema. Solo se usa el byte 0, nº de baterías (packs) en paralelo.
     * Devuelve 0 si el valor no es razonable (en ese caso no se avisa de nada).
     */
    public static int parsePackCount(byte[] d) {
        if (d.length < 1) return 0;
        int n = d[0] & 0xFF;
        return n >= 1 && n <= 32 ? n : 0;
    }

    /** 0A 02: celdas y temperaturas del pack. */
    public static final class Cells {
        public double packVoltageV;
        public int[] mv;
        /** Temperatura asociada a cada celda, NaN si esa posición no tiene sensor. */
        public double[] tempC;
    }

    public static Cells parseCells(byte[] d) {
        require(d, 4, "celdas");
        int n = d[3] & 0xFF;
        if (n < 1 || n > 32) throw new IllegalArgumentException("número de celdas raro: " + n);
        require(d, 4 + 4 * n, "celdas");
        Cells c = new Cells();
        c.packVoltageV = u16(d, 1) / 100.0;
        c.mv = new int[n];
        c.tempC = new double[n];
        for (int i = 0; i < n; i++) {
            c.mv[i] = u16(d, 4 + 4 * i);
            int t = u16(d, 6 + 4 * i);
            c.tempC[i] = t == 0 ? Double.NaN : kelvin(t);
        }
        return c;
    }

    /** 00 01 / 00 04 / 00 05: cadenas de texto (versión de software, hardware, nº de serie). */
    public static String parseText(byte[] d) {
        StringBuilder all = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= d.length; i++) {
            int c = i < d.length ? d[i] & 0xFF : 0;
            if (c >= 0x20 && c < 0x7F) {
                run.append((char) c);
            } else {
                String s = run.toString().trim();
                if (s.length() >= 4) {
                    if (all.length() > 0) all.append(" · ");
                    all.append(s);
                }
                run.setLength(0);
            }
        }
        return all.toString();
    }

    public static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", d[i] & 0xFF));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------

    private static double kelvin(int raw) {
        return (raw - 2730) / 10.0;
    }

    private static int u16(byte[] d, int o) {
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    private static int s16(byte[] d, int o) {
        return (short) u16(d, o);
    }

    private static void require(byte[] d, int n, String what) {
        if (d.length < n) throw new IllegalArgumentException("trama de " + what + " demasiado corta: " + d.length);
    }

    static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }
}
