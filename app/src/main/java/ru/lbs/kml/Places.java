package ru.lbs.kml;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Населённые пункты зоны фронта (OSM) с русскими названиями: поиск и данные для подписей. */
final class Places {

    static final String[] RANK_NAMES = {"город", "город", "село", "хутор"};

    final int n;
    final int[] rank, pop, nearKm;
    final String[] name, alt, near;
    final double[] lat, lon;
    private final String[] keyName, keyAlt;

    private static Places inst;

    static synchronized Places get(Context c) throws Exception {
        if (inst == null) inst = load(c);
        return inst;
    }

    private Places(int n) {
        this.n = n;
        rank = new int[n];
        pop = new int[n];
        nearKm = new int[n];
        name = new String[n];
        alt = new String[n];
        near = new String[n];
        lat = new double[n];
        lon = new double[n];
        keyName = new String[n];
        keyAlt = new String[n];
    }

    private static Places load(Context c) throws Exception {
        List<String[]> rows = new ArrayList<>(30000);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(c.getAssets().open("places.tsv.gz")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length >= 6) rows.add(f);
            }
        }
        Places p = new Places(rows.size());
        for (int i = 0; i < p.n; i++) {
            String[] f = rows.get(i);
            p.rank[i] = parseInt(f[0]);
            p.name[i] = f[1];
            p.alt[i] = f[2];
            p.lat[i] = Double.parseDouble(f[3]);
            p.lon[i] = Double.parseDouble(f[4]);
            p.pop[i] = parseInt(f[5]);
            p.near[i] = f.length > 6 ? f[6] : "";
            p.nearKm[i] = f.length > 7 ? parseInt(f[7]) : 0;
            p.keyName[i] = norm(p.name[i]);
            p.keyAlt[i] = norm(p.alt[i]);
        }
        return p;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
                .trim();
    }

    /** Поиск: сначала начало названия, потом начало второго названия, потом вхождение. */
    List<Integer> search(String query, int limit) {
        String q = norm(query);
        List<Integer> a = new ArrayList<>(), b = new ArrayList<>(), c = new ArrayList<>();
        if (q.isEmpty()) return a;
        for (int i = 0; i < n && a.size() < limit; i++) {
            String kn = keyName[i], ka = keyAlt[i];
            if (kn.startsWith(q)) a.add(i);
            else if (!ka.isEmpty() && ka.startsWith(q)) b.add(i);
            else if (q.length() >= 3 && (kn.contains(q) || ka.contains(q))) c.add(i);
        }
        List<Integer> out = new ArrayList<>(limit);
        for (List<Integer> l : java.util.Arrays.asList(a, b, c))
            for (int i : l) {
                if (out.size() >= limit) return out;
                out.add(i);
            }
        return out;
    }

    /** "село · Покровск, 8 км" */
    String subtitle(int i) {
        StringBuilder sb = new StringBuilder(RANK_NAMES[Math.max(0, Math.min(3, rank[i]))]);
        if (!near[i].isEmpty() && nearKm[i] > 0) sb.append(" · ").append(near[i]).append(", ").append(nearKm[i]).append(" км");
        if (!alt[i].isEmpty()) sb.append(" · ").append(alt[i]);
        return sb.toString();
    }
}
