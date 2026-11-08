import math, urllib.request, hashlib
pts = {"Donetsk area": (47.8427, 37.8440), "Bakhmut": (48.5950, 38.0010), "Zaporozhye front": (47.45, 35.85), "Kursk": (51.73, 36.19)}
srcs = {
 "esri": "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
 "clarity": "https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
}
def tile(lat, lon, z):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
    return x, y
for name, (lat, lon) in pts.items():
    for s, url in srcs.items():
        row = []
        for z in (17, 18, 19, 20):
            x, y = tile(lat, lon, z)
            try:
                r = urllib.request.urlopen(urllib.request.Request(url.format(z=z, x=x, y=y), headers={"User-Agent": "ru.lbs.kml"}), timeout=20)
                b = r.read()
                row.append(f"z{z}:{len(b)//1024}k:{hashlib.md5(b).hexdigest()[:6]}")
            except Exception as e:
                row.append(f"z{z}:ERR {str(e)[:30]}")
        print(name, s, " ".join(row))
# imagery dates / resolution via identify on wayback-like metadata layer
for name, (lat, lon) in pts.items():
    for svc in ("World_Imagery",):
        u = f"https://services.arcgisonline.com/arcgis/rest/services/{svc}/MapServer/identify?geometry={lon},{lat}&geometryType=esriGeometryPoint&sr=4326&layers=all&tolerance=1&mapExtent={lon-0.01},{lat-0.01},{lon+0.01},{lat+0.01}&imageDisplay=400,400,96&returnGeometry=false&f=json"
        try:
            import json
            d = json.load(urllib.request.urlopen(u, timeout=20))
            for res in d.get("results", [])[:3]:
                a = res.get("attributes", {})
                print(name, "meta:", a.get("SRC_DATE2") or a.get("SRC_DATE"), a.get("SRC_RES"), a.get("NICE_DESC") or a.get("SRC_DESC"), "lvl", res.get("layerName"))
        except Exception as e:
            print(name, "meta ERR", e)
