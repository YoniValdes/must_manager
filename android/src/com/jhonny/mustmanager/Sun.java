package com.jhonny.mustmanager;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Amanecer y atardecer sin internet, con la fórmula de la NOAA (la de su hoja de cálculo
 * "Solar Calculations"): posición del Sol para la fecha y, de ahí, cuándo cruza el horizonte
 * (90,833°: incluye la refracción de la atmósfera y el radio del disco solar). Precisión ~1 min.
 *
 * Todo se calcula en hora universal (UTC). La hora local la pone después el dispositivo con su
 * zona horaria, así no dependemos de las reglas de horario de verano (en Cuba cambian por decreto).
 */
final class Sun {
    private Sun() {
    }

    /**
     * Amanecer y atardecer (milisegundos UTC) del día local de {@code now}, o null si ese día
     * el Sol no sale o no se pone (solo pasa cerca de los polos).
     */
    static long[] times(double lat, double lon, long now) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(now);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        long midnightUtc = utc.getTimeInMillis();

        // Día juliano al mediodía solar aproximado de ese lugar (12 h − longitud/15, en UTC).
        double jd = midnightUtc / 86400000.0 + 2440587.5 + (12.0 - lon / 15.0) / 24.0;
        double t = (jd - 2451545.0) / 36525.0; // siglos julianos desde el año 2000

        double l0 = mod360(280.46646 + t * (36000.76983 + t * 0.0003032)); // longitud media
        double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);          // anomalía media
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);     // excentricidad de la órbita
        double mr = Math.toRadians(m);
        double c = Math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t))
            + Math.sin(2 * mr) * (0.019993 - 0.000101 * t) + Math.sin(3 * mr) * 0.000289;
        double trueLong = l0 + c;
        double omega = 125.04 - 1934.136 * t;
        double appLong = trueLong - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega));
        double meanObliq = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        double obliq = meanObliq + 0.00256 * Math.cos(Math.toRadians(omega));
        double decl = Math.asin(Math.sin(Math.toRadians(obliq)) * Math.sin(Math.toRadians(appLong))); // declinación (rad)

        // Ecuación del tiempo (minutos): cuánto adelanta o atrasa el Sol respecto al reloj.
        double y = Math.tan(Math.toRadians(obliq / 2));
        y *= y;
        double l0r = Math.toRadians(l0);
        double eqTime = 4 * Math.toDegrees(y * Math.sin(2 * l0r) - 2 * e * Math.sin(mr)
            + 4 * e * y * Math.sin(mr) * Math.cos(2 * l0r) - 0.5 * y * y * Math.sin(4 * l0r)
            - 1.25 * e * e * Math.sin(2 * mr));

        double latr = Math.toRadians(lat);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(latr) * Math.cos(decl))
            - Math.tan(latr) * Math.tan(decl);
        if (cosHa < -1 || cosHa > 1) return null;
        double ha = Math.toDegrees(Math.acos(cosHa)); // ángulo horario del amanecer (grados)

        double noon = 720 - 4 * lon - eqTime; // mediodía solar, minutos desde las 00:00 UTC
        long rise = midnightUtc + Math.round((noon - 4 * ha) * 60000);
        long set = midnightUtc + Math.round((noon + 4 * ha) * 60000);
        return new long[] {rise, set};
    }

    private static double mod360(double v) {
        v %= 360;
        return v < 0 ? v + 360 : v;
    }
}
