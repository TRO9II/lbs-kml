package ru.lbs.kml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Упаковка точки / маршрута / зоны в короткую строку для QR-кода и обратно.
 * Формат: "LBSQ1:" + base64url(deflate(текст)). Координаты — целые 1e-5° с разностями.
 */
final class QrCodec {

    static final String PREFIX = "LBSQ1:";
    /** Предел длины строки для уверенного считывания с экрана телефона. */
    private static final int MAX_LEN = 1800;

    static final class Item {
        UserPoints.Point point;
        Routes.Route route;
    }

    private QrCodec() {}

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    static String encodePoint(UserPoints.Point p) {
        String t = "P\t" + clean(p.name) + "\t" + clean(p.note) + "\t" + Integer.toHexString(p.color) + "\t"
                + clean(p.icon) + "\t" + Math.round(p.lat * 1e5) + "\t" + Math.round(p.lon * 1e5);
        return pack(t);
    }

    /** Маршрут; если не влезает — прореживается (концы сохраняются). */
    static String encodeRoute(Routes.Route r) {
        List<double[]> pts = r.pts;
        for (int step = 1; ; step++) {
            List<double[]> use = new ArrayList<>();
            for (int i = 0; i < pts.size(); i += step) use.add(pts.get(i));
            if (use.get(use.size() - 1) != pts.get(pts.size() - 1)) use.add(pts.get(pts.size() - 1));
            StringBuilder sb = new StringBuilder("R\t").append(clean(r.name)).append('\t')
                    .append(Integer.toHexString(r.color)).append('\t').append(r.area ? 1 : 0).append('\t');
            long pl = 0, pn = 0;
            for (int i = 0; i < use.size(); i++) {
                long la = Math.round(use.get(i)[0] * 1e5), lo = Math.round(use.get(i)[1] * 1e5);
                if (i > 0) sb.append(';');
                sb.append(la - pl).append(',').append(lo - pn);
                pl = la;
                pn = lo;
            }
            String s = pack(sb.toString());
            if (s.length() <= MAX_LEN || use.size() <= 3) return s;
        }
    }

    static Item decode(String s) throws Exception {
        if (s == null || !s.startsWith(PREFIX)) throw new Exception("это не QR-код Рубеж");
        String[] f = unpack(s.substring(PREFIX.length())).split("\t", -1);
        Item it = new Item();
        if (f[0].equals("P") && f.length >= 7) {
            UserPoints.Point p = new UserPoints.Point();
            p.name = f[1];
            p.note = f[2];
            p.color = (int) Long.parseLong(f[3], 16);
            p.icon = f[4];
            p.lat = Long.parseLong(f[5]) / 1e5;
            p.lon = Long.parseLong(f[6]) / 1e5;
            it.point = p;
        } else if (f[0].equals("R") && f.length >= 5) {
            Routes.Route r = new Routes.Route();
            r.name = f[1];
            r.color = (int) Long.parseLong(f[2], 16);
            r.area = f[3].equals("1");
            long la = 0, lo = 0;
            for (String pair : f[4].split(";")) {
                String[] q = pair.split(",");
                la += Long.parseLong(q[0]);
                lo += Long.parseLong(q[1]);
                r.pts.add(new double[]{la / 1e5, lo / 1e5});
            }
            it.route = r;
        } else {
            throw new Exception("неизвестный формат");
        }
        return it;
    }

    private static String pack(String text) {
        byte[] in = text.getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) bo.write(buf, 0, d.deflate(buf));
        d.end();
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bo.toByteArray());
    }

    private static String unpack(String b64) throws Exception {
        byte[] data = Base64.getUrlDecoder().decode(b64.trim());
        Inflater inf = new Inflater(true);
        inf.setInput(data);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!inf.finished()) {
            int k = inf.inflate(buf);
            if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            bo.write(buf, 0, k);
            if (bo.size() > 200000) throw new Exception("слишком большой");
        }
        inf.end();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** QR-картинка (чёрное на белом) через ZXing. */
    static android.graphics.Bitmap bitmap(String text, int size) throws Exception {
        java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
        hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L);
        hints.put(com.google.zxing.EncodeHintType.MARGIN, 2);
        com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints);
        int w = m.getWidth(), h = m.getHeight();
        int scale = Math.max(1, size / w);
        int[] px = new int[w * scale * h * scale];
        for (int y = 0; y < h * scale; y++)
            for (int x = 0; x < w * scale; x++)
                px[y * w * scale + x] = m.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF;
        return android.graphics.Bitmap.createBitmap(px, w * scale, h * scale, android.graphics.Bitmap.Config.ARGB_8888);
    }
}
