package ru.lbs.kml;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор координат из строки поиска:
 *  • СК-42: «X 5326882 Y 7395828», «5326882 7395828», «5 326 882  7 395 828»;
 *  • градусы: «48.0666, 37.6007», «48,0666 37,6007», «N48.0666 E37.6007»;
 *  • градусы-минуты-секунды: «48°03'59.8"N 37°36'02.6"E», «48 03 59.8 37 36 02.6».
 * Возвращает {lat, lon} в WGS-84 и подпись формата, либо null.
 */
final class CoordParse {

    static final class Result {
        double lat, lon;
        String kind;
    }

    private CoordParse() {}

    static Result parse(String input) {
        if (input == null) return null;
        String s = input.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty() || !s.matches(".*\\d.*")) return null;

        // --- СК-42 (прямоугольные): убираем пробелы-разделители тысяч
        String compact = s.replaceAll("(?<=\\d)[ \\u00A0](?=\\d{3}(\\D|$))", "");
        Matcher m = Pattern.compile("(\\d{7})(?:[.,]\\d+)?\\D+(\\d{7,8})(?:[.,]\\d+)?").matcher(compact);
        if (m.find() && !s.contains("°")) {
            double x = Double.parseDouble(m.group(1)), y = Double.parseDouble(m.group(2));
            int zone = (int) Math.floor(y / 1_000_000.0);
            if (x > 4_000_000 && x < 7_500_000 && zone >= 4 && zone <= 16) {
                double[] ll = Sk42.toWgs(x, y);
                Result r = new Result();
                r.lat = ll[0];
                r.lon = ll[1];
                r.kind = "СК-42, зона " + zone;
                return r;
            }
        }

        // --- числа (запятая как десятичный разделитель — если за ней цифры, а не пробел)
        List<Double> nums = new ArrayList<>();
        Matcher nm = Pattern.compile("[-+]?\\d+(?:[.,]\\d+)?").matcher(s);
        while (nm.find()) nums.add(Double.parseDouble(nm.group().replace(',', '.')));
        boolean south = s.matches(".*\\bS\\b.*|.*\\dS.*|.*Ю\\.?Ш.*"), west = s.matches(".*\\bW\\b.*|.*\\dW.*|.*З\\.?Д.*");

        double lat, lon;
        String kind;
        if (nums.size() == 2) {
            lat = nums.get(0); lon = nums.get(1); kind = "градусы";
        } else if (nums.size() == 4) {
            lat = nums.get(0) + nums.get(1) / 60; lon = nums.get(2) + nums.get(3) / 60; kind = "градусы, минуты";
        } else if (nums.size() == 6) {
            lat = nums.get(0) + nums.get(1) / 60 + nums.get(2) / 3600;
            lon = nums.get(3) + nums.get(4) / 60 + nums.get(5) / 3600;
            kind = "градусы, минуты, секунды";
        } else {
            return null;
        }
        if (south) lat = -Math.abs(lat);
        if (west) lon = -Math.abs(lon);
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        // долгота раньше широты? (типично для Украины: lat 44–53, lon 22–41)
        if (Math.abs(lat) > 30 && Math.abs(lat) < 42 && Math.abs(lon) > 43 && Math.abs(lon) < 54) {
            double t = lat; lat = lon; lon = t;
        }
        // просто два целых числа (например «5 7») — не координаты: нужны десятичные, ° или N/E
        boolean hasDec = s.matches(".*\\d[.,]\\d.*"), hasMark = s.contains("°") || s.matches(".*[NSEW].*");
        if (nums.size() == 2 && !hasDec && !hasMark) return null;
        Result r = new Result();
        r.lat = lat;
        r.lon = lon;
        r.kind = "WGS-84, " + kind;
        return r;
    }
}
