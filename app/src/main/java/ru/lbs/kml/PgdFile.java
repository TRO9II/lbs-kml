package ru.lbs.kml;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Растровая карта AlpineQuest (.pgd): картинка, порезанная на тайлы 256×256 (WebP) с пирамидой уровней,
 * и привязка по четырём углам. Файл читается прямо с диска, без распаковки — в память попадают только
 * тайлы, видимые на экране.
 *
 * Устройство файла (разобрано по образцу): записи с меткой 0x5555; уровень пирамиды описан строкой
 * «PGD_CB» = «0,0,столбцов,строк», за ней — дерево-словарь, где номер столбца и строки записан
 * десятичными цифрами от младшей к старшей; лист указывает на запись с WebP.
 */
final class PgdFile {

    static final class Level {
        int cols, rows;
        final Map<Long, Long> tiles = new HashMap<>(); // (col<<32|row) → смещение записи
    }

    final List<Level> levels = new ArrayList<>(); // от мелкого к крупному
    double[] lon = new double[4], lat = new double[4]; // углы: левый верх, правый верх, правый низ, левый низ
    int width, height;
    /** Проективная привязка AlpineQuest: пиксель полной картинки → Меркатор (X, −Y). null — нет, берём углы. */
    double[] hom;
    private final RandomAccessFile raf;
    private final long size;

    PgdFile(File f) throws Exception {
        raf = new RandomAccessFile(f, "r");
        size = raf.length();
        byte[] head = new byte[(int) Math.min(size, 256 * 1024)];
        raf.readFully(head);
        if (!(head[0] == 'P' && head[1] == 'G' && head[2] == 'D' && head[3] == ':')) throw new Exception("это не файл PGD");
        parseCalibration(head);
        width = (int) intProp(head, "image_width_px");
        height = (int) intProp(head, "image_height_px");
        // уровни пирамиды
        byte[] cb = "PGD_CB".getBytes(StandardCharsets.US_ASCII);
        for (int i = indexOf(head, cb, 0); i >= 0; i = indexOf(head, cb, i + 6)) {
            int len = u32(head, i + 6);
            String s = new String(head, i + 10, len, StandardCharsets.US_ASCII);
            String[] p = s.split(",");
            Level l = new Level();
            l.cols = Integer.parseInt(p[2].trim());
            l.rows = Integer.parseInt(p[3].trim());
            int root = indexOf(head, new byte[]{0, 4, 0x55, 0x55}, i);
            if (root < 0) continue;
            walk(root, new ArrayList<>(), l, 0);
            levels.add(l);
        }
        if (levels.isEmpty() || width <= 0 || height <= 0) throw new Exception("не найдены тайлы или размеры картинки");
        hom = homography(head, cb);
        levels.sort((a, b) -> Integer.compare(a.cols, b.cols));
    }

    private void parseCalibration(byte[] h) throws Exception {
        int i = indexOf(h, "PGD_CO".getBytes(StandardCharsets.US_ASCII), 0);
        if (i < 0) throw new Exception("нет привязки к местности");
        int j = i + 6;
        // после метки — длина и строка «lon,lat lon,lat …»; ищем первую цифру
        while (j < h.length && !(h[j] >= '0' && h[j] <= '9') && h[j] != '-') j++;
        int e = j;
        while (e < h.length && h[e] >= 0x20 && h[e] < 0x7f) e++;
        String[] pts = new String(h, j, e - j, StandardCharsets.US_ASCII).trim().split("\\s+");
        if (pts.length < 4) throw new Exception("привязка неполная");
        for (int k = 0; k < 4; k++) {
            String[] c = pts[k].split(",");
            lon[k] = Double.parseDouble(c[0]);
            lat[k] = Double.parseDouble(c[1]);
        }
    }

    /** Матрица 3×3 после «EPSG:NONE» у самого крупного уровня (в файле она есть у каждого уровня). */
    private double[] homography(byte[] h, byte[] cb) {
        int last = -1;
        for (int i = indexOf(h, cb, 0); i >= 0; i = indexOf(h, cb, i + 6)) last = i;
        if (last < 0) return null;
        int e = indexOf(h, "EPSG:NONE".getBytes(StandardCharsets.US_ASCII), last);
        if (e < 0 || e + 13 + 72 > h.length) return null;
        double[] m = new double[9];
        int p = e + 9 + 4;
        for (int k = 0; k < 9; k++) {
            m[k] = Double.longBitsToDouble(u64(h, p + 8 * k));
            if (Double.isNaN(m[k]) || Double.isInfinite(m[k])) return null;
        }
        if (Math.abs(m[8] - 1) > 1e-6) return null;
        // проверка: угол (0,0) должен попасть в первую точку привязки (с точностью ~1 км)
        double x = m[2] / m[8], lonc = Math.toDegrees(x / 6378137.0);
        if (Math.abs(lonc - lon[0]) > 0.02) return null;
        return m;
    }

    /** Целое свойство «имя» → значение: после имени идёт тип (−2 — число) и 8 байт значения. */
    private static long intProp(byte[] h, String name) {
        int i = indexOf(h, name.getBytes(StandardCharsets.US_ASCII), 0);
        if (i < 0) return -1;
        int p = i + name.length() + 4;
        long v = 0;
        for (int k = 0; k < 8; k++) v = v << 8 | (h[p + k] & 0xFF);
        return v;
    }

    // ---------- дерево тайлов ----------

    private byte[] read(long off, int n) throws Exception {
        if (off < 0 || off + n > size) return null;
        byte[] b = new byte[n];
        synchronized (raf) {
            raf.seek(off);
            raf.readFully(b);
        }
        return b;
    }

    private void walk(long o, List<Integer> path, Level l, int depth) throws Exception {
        if (depth > 12) return;
        byte[] h = read(o, 12);
        if (h == null || h[2] != 0x55 || h[3] != 0x55 || h[1] != 4) return;
        int cap = u32(h, 4);
        if (cap <= 0 || cap > 64) return;
        byte[] t = read(o + 12, 24 * cap);
        if (t == null) return;
        // первая половина — дочерние узлы, вторая — данные (тайлы)
        for (int i = 0; i < cap; i++) {
            long p = u64(t, 12 * i);
            int k = u32(t, 12 * i + 8);
            if (p <= 0) continue;
            byte[] n1 = read(p, 24);
            if (n1 == null || n1[1] != 1 || n1[2] != 0x55 || n1[3] != 0x55) continue;
            long ch = u64(n1, 16);
            if (ch > 0) {
                path.add(k);
                walk(ch, path, l, depth + 1);
                path.remove(path.size() - 1);
            }
        }
        for (int i = 0; i < cap; i++) {
            long p = u64(t, 12 * cap + 12 * i);
            int k = u32(t, 12 * cap + 12 * i + 8);
            if (p <= 0) continue;
            path.add(k);
            long[] cr = decode(path);
            path.remove(path.size() - 1);
            if (cr != null && cr[0] < l.cols && cr[1] < l.rows) l.tiles.put(cr[0] << 32 | cr[1], p);
        }
    }

    /** Цифры от младшей: столбец заканчивается ключом 10+цифра, дальше — строка (последняя цифра — ключ данных). */
    private static long[] decode(List<Integer> path) {
        int i = 0;
        long col = 0, mul = 1;
        while (i < path.size() && path.get(i) < 10) { col += path.get(i) * mul; mul *= 10; i++; }
        if (i >= path.size()) return null;
        col += (path.get(i) - 10) * mul;
        i++;
        if (i >= path.size()) return null;
        long row = 0;
        mul = 1;
        for (; i < path.size(); i++) {
            int k = path.get(i);
            if (k >= 10) return null;
            row += k * mul;
            mul *= 10;
        }
        return new long[]{col, row};
    }

    /** Тайл уровня как картинка (или null, если его нет — пустое место). */
    Bitmap tile(Level l, int col, int row) {
        Long off = l.tiles.get((long) col << 32 | row);
        if (off == null) return null;
        try {
            byte[] h = read(off, 96);
            if (h == null) return null;
            int r = indexOf(h, new byte[]{'R', 'I', 'F', 'F'}, 0);
            if (r < 0) return null;
            int n = (h[r + 4] & 0xFF) | (h[r + 5] & 0xFF) << 8 | (h[r + 6] & 0xFF) << 16 | (h[r + 7] & 0xFF) << 24;
            if (n <= 0 || n > 4_000_000) return null;
            byte[] img = read(off + r, n + 8);
            return img == null ? null : BitmapFactory.decodeByteArray(img, 0, img.length);
        } catch (Exception e) {
            return null;
        }
    }

    void close() {
        try {
            raf.close();
        } catch (Exception ignored) {
        }
    }

    // ---------- байты ----------

    static int indexOf(byte[] a, byte[] p, int from) {
        outer:
        for (int i = Math.max(0, from); i <= a.length - p.length; i++) {
            for (int k = 0; k < p.length; k++) if (a[i + k] != p[k]) continue outer;
            return i;
        }
        return -1;
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF);
    }

    private static long u64(byte[] b, int o) {
        long v = 0;
        for (int k = 0; k < 8; k++) v = v << 8 | (b[o + k] & 0xFF);
        return v;
    }
}
