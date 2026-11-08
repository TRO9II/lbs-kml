import urllib.request, json, math, gzip, os, time
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
H = {"User-Agent": "ru.lbs.kml (https://github.com/TRO9II/lbs-kml)"}
tj = json.load(urllib.request.urlopen(urllib.request.Request("https://tiles.openfreemap.org/planet", headers=H), timeout=20))
print("tiles:", tj.get("tiles"), "maxzoom", tj.get("maxzoom"))
tpl = tj["tiles"][0]
os.makedirs("out", exist_ok=True)
for (lat, lon, nm) in ((48.0159, 37.8028, "donetsk"), (48.30, 37.60, "rural")):
    for z in (10, 12, 14):
        x, y = tile(lat, lon, z)
        t = time.time()
        r = urllib.request.urlopen(urllib.request.Request(tpl.format(z=z, x=x, y=y), headers=H), timeout=20)
        b = r.read()
        raw = gzip.decompress(b) if b[:2] == b"\x1f\x8b" else b
        open(f"out/{nm}_{z}_{x}_{y}.pbf", "wb").write(raw)
        print(nm, z, "enc", r.headers.get("Content-Encoding"), "len", len(b), "raw", len(raw), "s", round(time.time() - t, 2))
