import sys
from pyproj import Transformer
worst = 0
for line in open(sys.argv[1]):
    lat, lon, zone, x, y, rt = line.split()
    lat, lon, zone, x, y, rt = float(lat), float(lon), int(zone), float(x), float(y), float(rt)
    crs = (f"+proj=tmerc +lat_0=0 +lon_0={zone*6-3} +k=1 +x_0={zone*1000000+500000} +y_0=0 "
           "+ellps=krass +towgs84=23.92,-141.27,-80.9,0,0.35,0.82,-0.12 +units=m +no_defs")
    t = Transformer.from_crs("EPSG:4326", crs, always_xy=True)
    e, n = t.transform(lon, lat)
    d = ((n - x) ** 2 + (e - y) ** 2) ** 0.5
    worst = max(worst, d)
    print(f"{lat:.4f},{lon:.4f} zone {zone}: mine X={x:.2f} Y={y:.2f} | pyproj X={n:.2f} Y={e:.2f} | diff {d:.3f} m | roundtrip {rt:.4f} m")
print("WORST DIFF (m):", round(worst, 3))
