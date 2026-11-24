import java.io.FileOutputStream;

/** Genera un .xlsx de prueba con XlsxWriter para revisarlo en el PC. Ejecutar: tools/test-xlsx.ps1 */
public class XlsxTest {
    public static void main(String[] args) throws Exception {
        FileOutputStream out = new FileOutputStream(args[0]);
        Class<?> c = Class.forName("com.jhonny.mustmanager.XlsxWriter");
        java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor(java.io.OutputStream.class);
        k.setAccessible(true);
        Object x = k.newInstance(out);
        java.lang.reflect.Method start = c.getDeclaredMethod("startSheet", String.class, String[].class, int[].class);
        java.lang.reflect.Method row = c.getDeclaredMethod("row", Object[].class);
        java.lang.reflect.Method fin = c.getDeclaredMethod("finish");
        start.setAccessible(true); row.setAccessible(true); fin.setAccessible(true);
        Class<?> dc = Class.forName("com.jhonny.mustmanager.XlsxWriter$Date");
        java.lang.reflect.Constructor<?> dk = dc.getDeclaredConstructor(long.class);
        dk.setAccessible(true);
        // Fecha sola (3), hora sola (4) y hora sin segundos (5): los tipos de XlsxWriter.Date.
        java.lang.reflect.Constructor<?> dk2 = dc.getDeclaredConstructor(long.class, int.class);
        dk2.setAccessible(true);

        start.invoke(x, "Lecturas", new String[]{"Fecha", "Hora", "SOC (%)", "Potencia (W)", "Estado", "Texto <&>"}, new int[]{11, 9, 8, 12, 12, 12});
        long t0 = 1795435200000L; // 23-nov-2026 07:00 UTC
        for (int i = 0; i < 5; i++) {
            long t = t0 + i * 10000L;
            row.invoke(x, (Object) new Object[]{dk2.newInstance(t, 3), dk2.newInstance(t, 4), 99 - i, -558.4 + i, "Descargando", "a<b & \"c\""});
        }
        row.invoke(x, (Object) new Object[]{dk2.newInstance(t0, 3), dk2.newInstance(t0, 4), null, Double.NaN, "vacías", ""});
        start.invoke(x, "Por hora", new String[]{"Fecha", "Hora", "kWh", "Fecha y hora"}, null);
        row.invoke(x, (Object) new Object[]{dk2.newInstance(t0, 3), dk2.newInstance(t0, 5), 0.523, dk.newInstance(t0)});
        fin.invoke(x);
        out.close();
        System.out.println("generado " + args[0]);
    }
}
