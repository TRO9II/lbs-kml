import urllib.request, urllib.parse, math, time, json
def bbox(x, y, z):
    n = 2 ** z
    lon1 = x / n * 360 - 180; lon2 = (x + 1) / n * 360 - 180
    lat1 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n)))); lat2 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    return lat2, lon1, lat1, lon2
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
B = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|track|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
A = "motorway|trunk|primary|motorway_link|trunk_link|primary_link"
for ep in ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"):
    for (lat, lon, nm) in ((48.0159, 37.8028, "Donetsk"), (48.30, 37.60, "rural")):
        for z, cls in ((12, B), (9, A)):
            s, w, n, e = bbox(*tile(lat, lon, z), z)
            q = f'[out:json][timeout:90];way["highway"~"^({cls})$"]({s:.5f},{w:.5f},{n:.5f},{e:.5f});out geom qt;'
            t = time.time()
            try:
                req = urllib.request.Request(ep, data=urllib.parse.urlencode({"data": q}).encode(), headers={"User-Agent": "ru.lbs.kml (https://github.com/TRO9II/lbs-kml)"})
                b = urllib.request.urlopen(req, timeout=120).read()
                d = json.loads(b)
                pts = sum(len(el.get("geometry", [])) for el in d["elements"])
                print(ep.split('/')[2], nm, "z", z, "ways", len(d["elements"]), "pts", pts, "KB", len(b) // 1024, "s", round(time.time() - t, 1))
            except Exception as ex:
                print(ep.split('/')[2], nm, "z", z, "ERR", str(ex)[:60], round(time.time() - t, 1))
