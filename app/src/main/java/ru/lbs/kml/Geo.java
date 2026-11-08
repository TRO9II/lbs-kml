package ru.lbs.kml;

import java.util.Locale;

/** Расстояние и азимут (Винсенти, WGS-84), дирекционный угол СК-42, форматирование. */
final class Geo {

    private Geo() {}

    /** {расстояние, м; истинный азимут, °} по эллипсоиду WGS-84 (формулы Винсенти). */
    static double[] inverse(double lat1, double lon1, double lat2, double lon2) {
        double a = 6378137.0, f = 1 / 298.257223563, b = a * (1 - f);
        double L = Math.toRadians(lon2 - lon1);
        double U1 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat1)));
        double U2 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat2)));
        double sinU1 = Math.sin(U1), cosU1 = Math.cos(U1), sinU2 = Math.sin(U2), cosU2 = Math.cos(U2);
        double lambda = L, lambdaP, sinSigma = 0, cosSigma = 0, sigma = 0, cosSqAlpha = 0, cos2SigmaM = 0;
        double sinLambda = 0, cosLambda = 0;
        int iter = 0;
        do {
            sinLambda = Math.sin(lambda);
            cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt((cosU2 * sinLambda) * (cosU2 * sinLambda)
                    + (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda) * (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda));
            if (sinSigma == 0) return new double[]{0, 0};
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSqAlpha != 0 ? cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha : 0;
            double C = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
            lambdaP = lambda;
            lambda = L + (1 - C) * f * sinAlpha
                    * (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        } while (Math.abs(lambda - lambdaP) > 1e-12 && ++iter < 200);
        double uSq = cosSqAlpha * (a * a - b * b) / (b * b);
        double A = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        double B = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        double dSigma = B * sinSigma * (cos2SigmaM + B / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                - B / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        double s = b * A * (sigma - dSigma);
        double az = Math.toDegrees(Math.atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda));
        return new double[]{s, (az + 360) % 360};
    }

    /** Дирекционный угол (°) по координатам СК-42 в зоне первой точки. */
    static double gridBearing(double lat1, double lon1, double lat2, double lon2) {
        double[] p1 = Sk42.fromWgs(lat1, lon1);
        double[] p2 = Sk42.fromWgsInZone(lat2, lon2, (int) p1[2]);
        double a = Math.toDegrees(Math.atan2(p2[1] - p1[1], p2[0] - p1[0]));
        return (a + 360) % 360;
    }

    /** Градусы → деления угломера (6000 в круге): 220.4° → "36-73". */
    static String mils(double deg) {
        long m = Math.round(deg * 6000.0 / 360.0) % 6000;
        return String.format(Locale.US, "%d-%02d", m / 100, m % 100);
    }

    /** 41810 → "41,81 км"; 850 → "850 м". */
    static String dist(double m) {
        if (m < 1000) return Math.round(m) + " м";
        if (m < 100000) return String.format(Locale.US, "%.2f км", m / 1000).replace('.', ',');
        return Math.round(m / 1000) + " км";
    }

    /** 5326882 → "5 326 882". */
    static String group(long v) {
        String s = Long.toString(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
            if (++k % 3 == 0 && i > 0) sb.append(' ');
        }
        if (v < 0) sb.append('-');
        return sb.reverse().toString();
    }
}
