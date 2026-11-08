package ru.lbs.kml;

/**
 * Солнце и Луна: восход/заход, сумерки, фаза и освещённость Луны.
 * Алгоритмы — по формулам астрономических альманахов (как в библиотеке SunCalc), точность ~1–2 мин.
 */
final class Astro {

    private static final double RAD = Math.PI / 180, DAY_MS = 86400000.0, J1970 = 2440588, J2000 = 2451545;
    private static final double E = RAD * 23.4397;
    private static final double J0 = 0.0009;

    private Astro() {}

    private static double toJulian(long ms) { return ms / DAY_MS - 0.5 + J1970; }
    private static long fromJulian(double j) { return Math.round((j + 0.5 - J1970) * DAY_MS); }
    private static double toDays(long ms) { return toJulian(ms) - J2000; }

    private static double rightAscension(double l, double b) {
        return Math.atan2(Math.sin(l) * Math.cos(E) - Math.tan(b) * Math.sin(E), Math.cos(l));
    }

    private static double declination(double l, double b) {
        return Math.asin(Math.sin(b) * Math.cos(E) + Math.cos(b) * Math.sin(E) * Math.sin(l));
    }

    private static double altitude(double h, double phi, double dec) {
        return Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(h));
    }

    private static double siderealTime(double d, double lw) { return RAD * (280.16 + 360.9856235 * d) - lw; }

    private static double solarMeanAnomaly(double d) { return RAD * (357.5291 + 0.98560028 * d); }

    private static double eclipticLongitude(double m) {
        double c = RAD * (1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m));
        return m + c + RAD * 102.9372 + Math.PI;
    }

    /** {dec, ra} */
    private static double[] sunCoords(double d) {
        double l = eclipticLongitude(solarMeanAnomaly(d));
        return new double[]{declination(l, 0), rightAscension(l, 0)};
    }

    /** Высота Солнца над горизонтом, градусы. */
    static double sunAltitude(long ms, double lat, double lon) {
        double lw = RAD * -lon, phi = RAD * lat, d = toDays(ms);
        double[] c = sunCoords(d);
        return altitude(siderealTime(d, lw) - c[1], phi, c[0]) / RAD;
    }

    /**
     * Времена Солнца на сутки, содержащие момент dayNoonMs (лучше передавать местный полдень).
     * Возвращает {восход, заход, гражд. начало, гражд. конец, навиг. начало, навиг. конец, полдень}; -1 — не бывает.
     */
    static long[] sunTimes(long dayNoonMs, double lat, double lon) {
        double lw = RAD * -lon, phi = RAD * lat, d = toDays(dayNoonMs);
        double n = Math.round(d - J0 - lw / (2 * Math.PI));
        double ds = J0 + lw / (2 * Math.PI) + n;
        double m = solarMeanAnomaly(ds), l = eclipticLongitude(m), dec = declination(l, 0);
        double jNoon = J2000 + ds + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * l);
        double[] angles = {-0.833, -6, -12};
        long[] out = new long[7];
        for (int i = 0; i < angles.length; i++) {
            double h = angles[i] * RAD;
            double cosW = (Math.sin(h) - Math.sin(phi) * Math.sin(dec)) / (Math.cos(phi) * Math.cos(dec));
            if (cosW < -1 || cosW > 1) {
                out[i * 2] = -1;
                out[i * 2 + 1] = -1;
                continue;
            }
            double w = Math.acos(cosW);
            double a = J0 + (w + lw) / (2 * Math.PI) + n;
            double jSet = J2000 + a + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * l);
            double jRise = jNoon - (jSet - jNoon);
            out[i * 2] = fromJulian(jRise);
            out[i * 2 + 1] = fromJulian(jSet);
        }
        out[6] = fromJulian(jNoon);
        return out;
    }

    /** {ra, dec, dist км} */
    private static double[] moonCoords(double d) {
        double l = RAD * (218.316 + 13.176396 * d), m = RAD * (134.963 + 13.064993 * d), f = RAD * (93.272 + 13.229350 * d);
        double lon = l + RAD * 6.289 * Math.sin(m), lat = RAD * 5.128 * Math.sin(f);
        double dist = 385001 - 20905 * Math.cos(m);
        return new double[]{rightAscension(lon, lat), declination(lon, lat), dist};
    }

    /** Высота Луны (радианы) с учётом рефракции. */
    private static double moonAlt(long ms, double lat, double lon) {
        double lw = RAD * -lon, phi = RAD * lat, d = toDays(ms);
        double[] c = moonCoords(d);
        double h = altitude(siderealTime(d, lw) - c[0], phi, c[1]);
        double hh = h < 0 ? 0 : h;
        return h + 0.0002967 / Math.tan(hh + 0.00312536 / (hh + 0.08901179));
    }

    static double moonAltitudeDeg(long ms, double lat, double lon) {
        return moonAlt(ms, lat, lon) / RAD;
    }

    /** {освещённая доля 0..1, фаза 0..1 (0 — новолуние, 0.5 — полнолуние)} */
    static double[] moonIllumination(long ms) {
        double d = toDays(ms);
        double[] s = sunCoords(d), m = moonCoords(d);
        double sdist = 149598000;
        double phi = Math.acos(Math.sin(s[0]) * Math.sin(m[1]) + Math.cos(s[0]) * Math.cos(m[1]) * Math.cos(s[1] - m[0]));
        double inc = Math.atan2(sdist * Math.sin(phi), m[2] - sdist * Math.cos(phi));
        double angle = Math.atan2(Math.cos(s[0]) * Math.sin(s[1] - m[0]),
                Math.sin(s[0]) * Math.cos(m[1]) - Math.cos(s[0]) * Math.sin(m[1]) * Math.cos(s[1] - m[0]));
        return new double[]{(1 + Math.cos(inc)) / 2, 0.5 + 0.5 * inc * (angle < 0 ? -1 : 1) / Math.PI};
    }

    /** Восход и заход Луны в сутки, начинающиеся в dayStartMs (местная полночь). {восход, заход}; -1 — нет. */
    static long[] moonTimes(long dayStartMs, double lat, double lon) {
        double hc = 0.133 * RAD;
        double h0 = moonAlt(dayStartMs, lat, lon) - hc;
        double rise = Double.NaN, set = Double.NaN;
        for (int i = 1; i <= 24; i += 2) {
            double h1 = moonAlt(dayStartMs + i * 3600000L, lat, lon) - hc;
            double h2 = moonAlt(dayStartMs + (i + 1) * 3600000L, lat, lon) - hc;
            double a = (h0 + h2) / 2 - h1, b = (h2 - h0) / 2;
            double xe = -b / (2 * a), ye = (a * xe + b) * xe + h1, disc = b * b - 4 * a * h1;
            int roots = 0;
            double x1 = 0, x2 = 0;
            if (disc >= 0) {
                double dx = Math.sqrt(disc) / (Math.abs(a) * 2);
                x1 = xe - dx;
                x2 = xe + dx;
                if (Math.abs(x1) <= 1) roots++;
                if (Math.abs(x2) <= 1) roots++;
                if (x1 < -1) x1 = x2;
            }
            if (roots == 1) {
                if (h0 < 0) rise = i + x1;
                else set = i + x1;
            } else if (roots == 2) {
                rise = i + (ye < 0 ? x2 : x1);
                set = i + (ye < 0 ? x1 : x2);
            }
            if (!Double.isNaN(rise) && !Double.isNaN(set)) break;
            h0 = h2;
        }
        return new long[]{Double.isNaN(rise) ? -1 : dayStartMs + Math.round(rise * 3600000),
                Double.isNaN(set) ? -1 : dayStartMs + Math.round(set * 3600000)};
    }

    static String phaseName(double p) {
        if (p < 0.03 || p > 0.97) return "Новолуние";
        if (p < 0.22) return "Растущий серп";
        if (p < 0.28) return "Первая четверть";
        if (p < 0.47) return "Растущая луна";
        if (p < 0.53) return "Полнолуние";
        if (p < 0.72) return "Убывающая луна";
        if (p < 0.78) return "Последняя четверть";
        return "Убывающий серп";
    }
}
