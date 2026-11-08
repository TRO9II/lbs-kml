package ru.lbs.kml;
public class Sk42Test {
  public static void main(String[] a) {
    double[][] pts = {{48.2830,37.1760},{47.8388,35.1396},{46.6354,32.6169},{50.0050,36.2310},{50.9077,34.7981},{48.5960,38.0010},{47.0950,37.5410},{51.5,33.9},{48.0,36.0},{48.0,35.999}};
    for (double[] p : pts) {
      double[] r = Sk42.fromWgs(p[0], p[1]);
      double[] back = Sk42.toWgs(r[0], r[1]);
      double dlat = (back[0]-p[0])*111320, dlon=(back[1]-p[1])*111320*Math.cos(Math.toRadians(p[0]));
      System.out.printf(java.util.Locale.US, "%.6f %.6f %d %.3f %.3f %.4f%n", p[0], p[1], (int)r[2], r[0], r[1], Math.hypot(dlat,dlon));
    }
  }
}
