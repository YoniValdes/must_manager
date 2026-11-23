import com.jhonny.mustmanager.BmsProtocol;
import java.util.Arrays;
import java.util.List;

/**
 * Prueba BmsProtocol en el PC con tramas reales del HCI log del 23-nov-2026.
 * Valores esperados: docs/lectura_referencia_2026-11-23.md
 * Ejecutar: tools/test-protocol.ps1
 */
public class ProtocolTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK    " : "  FALLO ") + what);
        if (!ok) fails++;
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.005;
    }

    static byte[] hex(String s) {
        String[] p = s.trim().split("\\s+");
        byte[] o = new byte[p.length];
        for (int i = 0; i < p.length; i++) o[i] = (byte) Integer.parseInt(p[i], 16);
        return o;
    }

    /** Alimenta la trama en trozos de 20 bytes, como llegaría con el MTU mínimo de BLE. */
    static List<BmsProtocol.Frame> feedChunked(BmsProtocol.Reassembler r, byte[] frame) {
        List<BmsProtocol.Frame> out = new java.util.ArrayList<BmsProtocol.Frame>();
        for (int i = 0; i < frame.length; i += 20) {
            out.addAll(r.feed(Arrays.copyOfRange(frame, i, Math.min(frame.length, i + 20))));
        }
        return out;
    }

    public static void main(String[] args) {
        System.out.println("Lista blanca: CRC de los comandos");
        byte[][] cmds = {BmsProtocol.CMD_DEVICE_INFO, BmsProtocol.CMD_SUMMARY, BmsProtocol.CMD_PACK_BASIC,
            BmsProtocol.CMD_PACK_CELLS, BmsProtocol.CMD_PACK_STATUS};
        for (byte[] c : cmds) {
            BmsProtocol.Reassembler r = new BmsProtocol.Reassembler();
            check(r.feed(c).size() == 1, "CRC válido en " + BmsProtocol.toHex(c));
            check(BmsProtocol.isWhitelisted(c), "está en la lista blanca");
        }
        check(!BmsProtocol.isWhitelisted(hex("9A 00 00 0B 01 00 00 00 00 00 9D")), "una trama inventada NO está en la lista blanca");

        System.out.println("Datos básicos (0A 01), captura 14:12:08");
        byte[] basic = hex("9a 00 00 0a 01 00 00 2b 02 00 00 0a e5 6d 5a 6d 60 6d 60 64 64 00 d4 00 00 00 00 00 00 00 00 "
            + "0a f2 00 00 01 0b d7 02 0b d4 0b d8 0b f3 03 0d a2 01 0d 91 40 62 9d");
        BmsProtocol.Reassembler r = new BmsProtocol.Reassembler();
        List<BmsProtocol.Frame> f = feedChunked(r, basic);
        check(f.size() == 1 && f.get(0).key == BmsProtocol.KEY_PACK_BASIC, "reensamblada desde trozos de 20 bytes");
        BmsProtocol.Basic b = BmsProtocol.parseBasic(f.get(0).data);
        check(near(b.voltageV, 27.89), "voltaje 27,89 V (" + b.voltageV + ")");
        check(near(b.currentA, 0), "corriente 0 A (" + b.currentA + ")");
        check(near(b.remainingAh, 279.94), "restante 279,94 Ah (" + b.remainingAh + ")");
        check(near(b.fullAh, 280) && near(b.designAh, 280), "total y diseño 280 Ah");
        check(b.soc == 100 && b.soh == 100, "SOC y SOH 100 %");
        check(b.cycles == 212, "212 ciclos (" + b.cycles + ")");
        check(near(b.independentV, 28.02), "independent voltage 28,02 V (" + b.independentV + ")");
        check(b.tempMaxSensor == 1 && near(b.tempMaxC, 30.1), "temp. máx. sensor 1, 30,1 °C (" + b.tempMaxC + ")");
        check(b.tempMinSensor == 2 && near(b.tempMinC, 29.8), "temp. mín. sensor 2, 29,8 °C (" + b.tempMinC + ")");
        check(near(b.tempMosC, 30.2), "MOS 30,2 °C (" + b.tempMosC + ")");
        check(near(b.tempAmbientC, 32.9), "ambiente 32,9 °C (" + b.tempAmbientC + ")");
        check(b.cellMaxIndex == 3 && b.cellMaxMv == 3490, "celda máx. 3 → 3490 mV");
        check(b.cellMinIndex == 1 && b.cellMinMv == 3473, "celda mín. 1 → 3473 mV");

        System.out.println("Celdas (0A 02), captura 14:12:09");
        byte[] cells = hex("9a 00 00 0a 02 00 00 24 02 0a e5 08 0d 91 0b d7 0d 9e 0b d4 0d a2 00 00 0d 98 00 00 "
            + "0d 92 00 00 0d a1 00 00 0d a1 00 00 0d a0 00 00 05 f7 9d");
        f = feedChunked(r, cells);
        check(f.size() == 1 && f.get(0).key == BmsProtocol.KEY_PACK_CELLS, "reensamblada");
        BmsProtocol.Cells c = BmsProtocol.parseCells(f.get(0).data);
        check(Arrays.equals(c.mv, new int[]{3473, 3486, 3490, 3480, 3474, 3489, 3489, 3488}),
            "8 celdas = captura de pantalla " + Arrays.toString(c.mv));
        check(near(c.tempC[0], 30.1) && near(c.tempC[1], 29.8) && Double.isNaN(c.tempC[2]), "T1 30,1 · T2 29,8 · resto sin sensor");
        check(near(c.packVoltageV, 27.89), "voltaje del pack 27,89 V");

        System.out.println("Información del dispositivo (00 01)");
        byte[] info = hex("9a 00 00 00 01 00 00 43 00 15 50 38 53 32 30 30 41 2d 34 31 30 38 30 2d 31 2e 30 33 00 00 00 20 "
            + "50 38 53 32 30 30 41 2d 34 31 30 38 30 2d 31 2e 30 33 2d 30 30 32 00 00 00 00 00 00 00 00 00 00 0a "
            + "32 31 38 38 37 56 31 33 30 00 c8 fa 9d");
        f = r.feed(info);
        String text = BmsProtocol.parseText(f.get(0).data);
        check(text.contains("P8S200A-41080-1.03") && text.contains("21887V130"), "texto: " + text);

        System.out.println("Robustez");
        byte[] bad = basic.clone();
        bad[12] ^= 0x01; // un bit cambiado en el voltaje
        check(r.feed(bad).isEmpty(), "trama con un bit corrupto se descarta (CRC)");
        byte[] junk = hex("00 11 9a 22 33");
        byte[] both = new byte[junk.length + cells.length];
        System.arraycopy(junk, 0, both, 0, junk.length);
        System.arraycopy(cells, 0, both, junk.length, cells.length);
        BmsProtocol.Reassembler r2 = new BmsProtocol.Reassembler();
        check(r2.feed(both).size() == 1, "se recupera tras basura delante de la trama");

        System.out.println(fails == 0 ? "\nTODO CORRECTO" : "\n" + fails + " FALLOS");
        System.exit(fails == 0 ? 0 : 1);
    }
}
