import math, urllib.request, hashlib, json
pts = {"Donetsk": (47.8427, 37.8440), "Bakhmut": (48.5950, 38.0010), "Zaporozhye": (47.45, 35.85)}
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
def get(u):
    try:
        b = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "ru.lbs.kml"}), timeout=20).read()
        return f"{len(b)//1024}k:{hashlib.md5(b).hexdigest()[:5]}"
    except Exception as e:
        return "ERR" + str(e)[11:15]
cfg = json.load(urllib.request.urlopen("https://s3-us-west-2.amazonaws.com/config.maptiles.arcgis.com/waybackconfig.json", timeout=30))
rel = sorted(cfg.items(), key=lambda kv: kv[1].get("itemReleaseName", ""), reverse=True)
print("releases:", len(rel), rel[0][1].get("itemTitle"), "...", rel[-1][1].get("itemTitle"))
print("url tpl:", rel[0][1].get("itemURL"))
for name, (lat, lon) in pts.items():
    seen = set()
    for k, v in rel[:60:3]:
        u = v["itemURL"]
        row = []
        for z in (18, 19):
            x, y = tile(lat, lon, z)
            row.append(f"z{z}:" + get(u.replace("{level}", str(z)).replace("{row}", str(y)).replace("{col}", str(x))))
        print(name, v.get("itemTitle", k)[-25:], " ".join(row))
# other free sources
others = {
 "eox-s2-2023": "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg",
}
for s, url in others.items():
    for name, (lat, lon) in pts.items():
        print(s, name, " ".join(f"z{z}:" + get(url.format(z=z, x=tile(lat, lon, z)[0], y=tile(lat, lon, z)[1])) for z in (14, 15, 16)))
