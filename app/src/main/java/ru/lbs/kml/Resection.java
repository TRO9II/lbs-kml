package ru.lbs.kml;

import java.util.List;

/**
 * Обратная засечка: где я стою, если известны ориентиры на карте и азимуты на них.
 * Каждый ориентир даёт линию «я где-то на ней»; ищем точку, ближайшую ко всем линиям
 * (метод наименьших квадратов на местной плоскости — для расстояний до десятков км этого хватает).
 */
final class Resection {

    static final class Mark {
        String name = "";
        double lat, lon;
        double az = Double.NaN; // истинный азимут от меня на ориентир, градусы
    }

    static final class Result {
        double lat, lon;
        double err;        // средний разброс, м (для двух ориентиров — 0)
        double minAngle;   // наименьший угол пересечения линий, градусы (хуже всего — около 0)
        boolean behind;    // какой-то ориентир оказался «позади» — азимут, скорее всего, ошибочен
        double[] dist;     // расстояния до ориентиров, м
    }

    private Resection() {}

    /** «45», «45.5», «7-50» (деления угломера) → градусы; NaN, если не разобрать. */
    static double parseAz(String s) {
        if (s == null) return Double.NaN;
        s = s.trim().replace(',', '.').replace('°', ' ').trim();
        if (s.isEmpty()) return Double.NaN;
        try {
            if (s.contains("-")) {
                String[] p = s.split("-");
                if (p.length != 2) return Double.NaN;
                int big = Integer.parseInt(p[0].trim()), small = Integer.parseInt(p[1].trim());
                if (small < 0 || small > 99 || big < 0 || big > 60) return Double.NaN;
                return norm((big * 100 + small) * 0.06); // 60-00 = 360°
            }
            double v = Double.parseDouble(s);
            if (v < 0 || v > 360) return Double.NaN;
            return norm(v);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    static String mils(double deg) {
        int m = (int) Math.round(norm(deg) / 0.06) % 6000;
        return String.format(java.util.Locale.US, "%d-%02d", m / 100, m % 100);
    }

    static double norm(double a) {
        a %= 360;
        return a < 0 ? a + 360 : a;
    }

    static Result solve(List<Mark> marks) {
        int n = 0;
        double lat0 = 0, lon0 = 0;
        for (Mark m : marks) if (!Double.isNaN(m.az)) { lat0 += m.lat; lon0 += m.lon; n++; }
        if (n < 2) return null;
        lat0 /= n;
        lon0 /= n;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat0)), ky = 110540.0;
        // сумма n·nᵀ и n·(n·L) по всем линиям, n — нормаль к направлению на ориентир
        double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
        for (Mark m : marks) {
            if (Double.isNaN(m.az)) continue;
            double x = (m.lon - lon0) * kx, y = (m.lat - lat0) * ky;
            double r = Math.toRadians(m.az), nx = Math.cos(r), ny = -Math.sin(r);
            double c = nx * x + ny * y;
            a11 += nx * nx;
            a12 += nx * ny;
            a22 += ny * ny;
            b1 += nx * c;
            b2 += ny * c;
        }
        double det = a11 * a22 - a12 * a12;
        if (Math.abs(det) < 1e-6) return null; // линии почти параллельны
        double px = (b1 * a22 - b2 * a12) / det, py = (a11 * b2 - a12 * b1) / det;
        Result res = new Result();
        res.lat = lat0 + py / ky;
        res.lon = lon0 + px / kx;
        res.dist = new double[marks.size()];
        double sq = 0;
        int k = 0;
        res.minAngle = 90;
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            if (Double.isNaN(m.az)) continue;
            double x = (m.lon - lon0) * kx - px, y = (m.lat - lat0) * ky - py;
            res.dist[i] = Math.hypot(x, y);
            double r = Math.toRadians(m.az), dx = Math.sin(r), dy = Math.cos(r);
            if (x * dx + y * dy < 0) res.behind = true;
            double off = x * Math.cos(r) - y * Math.sin(r);
            sq += off * off;
            k++;
            for (int j = i + 1; j < marks.size(); j++) {
                Mark o = marks.get(j);
                if (Double.isNaN(o.az)) continue;
                double d = Math.abs(norm(m.az - o.az));
                if (d > 180) d = 360 - d;
                if (d > 90) d = 180 - d;
                res.minAngle = Math.min(res.minAngle, d);
            }
        }
        res.err = k > 2 ? Math.sqrt(sq / (k - 2)) : 0;
        return res;
    }
}
