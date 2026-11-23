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

        start.invoke(x, "Lecturas", new String[]{"Fecha y hora", "SOC (%)", "Potencia (W)", "Estado", "Texto <&>"}, new int[]{20, 8, 12, 12, 12});
        long t0 = 1795435200000L; // 23-nov-2026 07:00 UTC
        for (int i = 0; i < 5; i++) {
            row.invoke(x, (Object) new Object[]{dk.newInstance(t0 + i * 10000L), 99 - i, -558.4 + i, "Descargando", "a<b & \"c\""});
        }
        row.invoke(x, (Object) new Object[]{dk.newInstance(t0), null, Double.NaN, "vacías", ""});
        start.invoke(x, "Por hora", new String[]{"Hora", "kWh"}, null);
        row.invoke(x, (Object) new Object[]{dk.newInstance(t0), 0.523});
        fin.invoke(x);
        out.close();
        System.out.println("generado " + args[0]);
    }
}
