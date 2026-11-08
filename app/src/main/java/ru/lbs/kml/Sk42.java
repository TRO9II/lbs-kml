package ru.lbs.kml;

/**
 * Пересчёт WGS-84 ⇄ СК-42 (Пулково-1942, эллипсоид Красовского) и проекция Гаусса-Крюгера (6° зоны).
 * Параметры перехода — ГОСТ Р 51794-2001 (как в +towgs84=23.92,-141.27,-80.9,0,0.35,0.82,-0.12).
 * Проекция — ряды Крюгера (точность до миллиметров в пределах зоны).
 * X — на север (м), Y — на восток с номером зоны впереди: Y = зона·10⁶ + 500 000 + смещение.
 */
final class Sk42 {

    private Sk42() {}

    // эллипсоиды
    private static final double A_W = 6378137.0, F_W = 1 / 298.257223563;
    private static final double A_K = 6378245.0, F_K = 1 / 298.3;

    // СК-42 → WGS-84, вектор положения (секунды → радианы, ppm → доли)
    private static final double TX = 23.92, TY = -141.27, TZ = -80.9;
    private static final double SEC = Math.PI / 180 / 3600;
    private static final double RX = 0 * SEC, RY = 0.35 * SEC, RZ = 0.82 * SEC;
    private static final double S = -0.12e-6;

    // ---------- геоцентрические координаты ----------

    private static double[] toXyz(double latDeg, double lonDeg, double a, double f) {
        double e2 = f * (2 - f);
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        double sl = Math.sin(la);
        double n = a / Math.sqrt(1 - e2 * sl * sl);
        return new double[]{n * Math.cos(la) * Math.cos(lo), n * Math.cos(la) * Math.sin(lo), n * (1 - e2) * sl};
    }

    private static double[] fromXyz(double x, double y, double z, double a, double f) {
        double e2 = f * (2 - f);
        double p = Math.hypot(x, y);
        double lon = Math.atan2(y, x);
        double lat = Math.atan2(z, p * (1 - e2));
        for (int i = 0; i < 8; i++) {
            double sl = Math.sin(lat);
            double n = a / Math.sqrt(1 - e2 * sl * sl);
            lat = Math.atan2(z + e2 * n * sl, p);
        }
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    /** Широта/долгота WGS-84 → широта/долгота СК-42. */
    static double[] wgsToSk(double lat, double lon) {
        double[] w = toXyz(lat, lon, A_W, F_W);
        // обратное преобразование Гельмерта (малые углы)
        double x = (w[0] - TX) / (1 + S), y = (w[1] - TY) / (1 + S), z = (w[2] - TZ) / (1 + S);
        double xs = x + RZ * y - RY * z;
        double ys = -RZ * x + y + RX * z;
        double zs = RY * x - RX * y + z;
        return fromXyz(xs, ys, zs, A_K, F_K);
    }

    /** Широта/долгота СК-42 → широта/долгота WGS-84. */
    static double[] skToWgs(double lat, double lon) {
        double[] k = toXyz(lat, lon, A_K, F_K);
        double xw = TX + (1 + S) * (k[0] - RZ * k[1] + RY * k[2]);
        double yw = TY + (1 + S) * (RZ * k[0] + k[1] - RX * k[2]);
        double zw = TZ + (1 + S) * (-RY * k[0] + RX * k[1] + k[2]);
        return fromXyz(xw, yw, zw, A_W, F_W);
    }

    // ---------- Гаусс-Крюгер на эллипсоиде Красовского ----------

    private static final double N = F_K / (2 - F_K);
    private static final double E = 2 * Math.sqrt(N) / (1 + N);
    private static final double AA = A_K / (1 + N) * (1 + N * N / 4 + N * N * N * N / 64);
    private static final double[] ALPHA = {
            N / 2 - 2 * N * N / 3 + 5 * N * N * N / 16 + 41 * Math.pow(N, 4) / 180,
            13 * N * N / 48 - 3 * N * N * N / 5 + 557 * Math.pow(N, 4) / 1440,
            61 * N * N * N / 240 - 103 * Math.pow(N, 4) / 140,
            49561 * Math.pow(N, 4) / 161280};
    private static final double[] BETA = {
            N / 2 - 2 * N * N / 3 + 37 * N * N * N / 96 - Math.pow(N, 4) / 360,
            N * N / 48 + N * N * N / 15 - 437 * Math.pow(N, 4) / 1440,
            17 * N * N * N / 480 - 37 * Math.pow(N, 4) / 840,
            4397 * Math.pow(N, 4) / 161280};
    private static final double[] DELTA = {
            2 * N - 2 * N * N / 3 - 2 * N * N * N + 116 * Math.pow(N, 4) / 45,
            7 * N * N / 3 - 8 * N * N * N / 5 - 227 * Math.pow(N, 4) / 45,
            56 * N * N * N / 15 - 136 * Math.pow(N, 4) / 35,
            4279 * Math.pow(N, 4) / 630};

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }

    /** Номер 6° зоны по долготе (СК-42). */
    static int zoneOf(double lonSk) {
        return (int) Math.floor(lonSk / 6.0) + 1;
    }

    /** СК-42 широта/долгота → {X, Y} в заданной зоне (Y с номером зоны). */
    static double[] gk(double latSk, double lonSk, int zone) {
        double lon0 = zone * 6 - 3;
        double phi = Math.toRadians(latSk), dl = Math.toRadians(lonSk - lon0);
        double sp = Math.sin(phi);
        double t = Math.sinh(atanh(sp) - E * atanh(E * sp));
        double xiP = Math.atan2(t, Math.cos(dl));
        double etaP = atanh(Math.sin(dl) / Math.sqrt(1 + t * t));
        double xi = xiP, eta = etaP;
        for (int j = 1; j <= 4; j++) {
            xi += ALPHA[j - 1] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
            eta += ALPHA[j - 1] * Math.cos(2 * j * xiP) * Math.sinh(2 * j * etaP);
        }
        return new double[]{AA * xi, zone * 1_000_000.0 + 500_000.0 + AA * eta};
    }

    /** {X, Y} (Y с номером зоны) → СК-42 широта/долгота. */
    static double[] gkInv(double x, double y) {
        int zone = (int) Math.floor(y / 1_000_000.0);
        double lon0 = zone * 6 - 3;
        double xi = x / AA, eta = (y - zone * 1_000_000.0 - 500_000.0) / AA;
        double xiP = xi, etaP = eta;
        for (int j = 1; j <= 4; j++) {
            xiP -= BETA[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
            etaP -= BETA[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
        }
        double chi = Math.asin(Math.sin(xiP) / Math.cosh(etaP));
        double phi = chi;
        for (int j = 1; j <= 4; j++) phi += DELTA[j - 1] * Math.sin(2 * j * chi);
        double lon = lon0 + Math.toDegrees(Math.atan2(Math.sinh(etaP), Math.cos(xiP)));
        return new double[]{Math.toDegrees(phi), lon};
    }

    // ---------- удобные обёртки ----------

    /** WGS-84 → {X, Y, зона} в своей зоне. */
    static double[] fromWgs(double lat, double lon) {
        double[] sk = wgsToSk(lat, lon);
        int z = zoneOf(sk[1]);
        double[] xy = gk(sk[0], sk[1], z);
        return new double[]{xy[0], xy[1], z};
    }

    /** WGS-84 → {X, Y} в указанной зоне (для сетки у границы зон). */
    static double[] fromWgsInZone(double lat, double lon, int zone) {
        double[] sk = wgsToSk(lat, lon);
        return gk(sk[0], sk[1], zone);
    }

    /** {X, Y} СК-42 → WGS-84 {lat, lon}. */
    static double[] toWgs(double x, double y) {
        double[] sk = gkInv(x, y);
        return skToWgs(sk[0], sk[1]);
    }

    /** "X 5 354 120  Y 7 412 860" */
    static String format(double lat, double lon) {
        double[] r = fromWgs(lat, lon);
        return "X " + group(Math.round(r[0])) + "   Y " + group(Math.round(r[1]));
    }

    private static String group(long v) {
        String s = Long.toString(v);
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
            if (++k % 3 == 0 && i > 0) sb.append(' ');
        }
        return sb.reverse().toString();
    }
}
