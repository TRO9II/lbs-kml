package ru.lbs.kml;
import java.nio.file.*;
public class T {
    public static void main(String[] a) throws Exception {
        for (Path p : Files.newDirectoryStream(Paths.get("out"))) {
            String[] s = p.getFileName().toString().replace(".pbf", "").split("_");
            java.util.List<Mvt.Line> ls = Mvt.roads(Files.readAllBytes(p), Integer.parseInt(s[1]), Integer.parseInt(s[2]), Integer.parseInt(s[3]));
            int[] c = new int[6]; int pts = 0;
            for (Mvt.Line l : ls) { c[l.cls]++; pts += l.lat.length; }
            Mvt.Line f = ls.isEmpty() ? null : ls.get(0);
            System.out.println(p.getFileName() + " lines " + ls.size() + " pts " + pts + " by class " + java.util.Arrays.toString(c)
                + (f == null ? "" : " first " + f.lat[0] + "," + f.lon[0]));
        }
    }
}
