import math, urllib.request, hashlib, json, time
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
def fetch(u):
    try: return urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "ru.lbs.kml"}), timeout=20).read()
    except Exception as e: return None
cfg = json.load(urllib.request.urlopen("https://s3-us-west-2.amazonaws.com/config.maptiles.arcgis.com/waybackconfig.json", timeout=30))
k0, v0 = next(iter(cfg.items()))
print("sample entry:", k0, json.dumps(v0, ensure_ascii=False)[:600])
rel = sorted(cfg.items(), key=lambda kv: kv[1]["itemReleaseName"] if "itemReleaseName" in kv[1] else kv[1]["itemTitle"], reverse=True)
lat, lon = 48.5950, 38.0010
x, y = tile(lat, lon, 17)
cache = {}; calls = [0]
def h(i):
    if i not in cache:
        calls[0] += 1
        b = fetch(rel[i][1]["itemURL"].replace("{level}", "17").replace("{row}", str(y)).replace("{col}", str(x)))
        cache[i] = hashlib.md5(b).hexdigest()[:6] if b else None
    return cache[i]
changes = []
def bis(a, b):
    if h(a) == h(b): return
    if b - a == 1: changes.append(b); return
    m = (a + b) // 2
    bis(a, m); bis(m, b)
t = time.time(); bis(0, len(rel) - 1)
print("calls", calls[0], "secs", round(time.time() - t, 1))
groups = [0] + changes
for i in groups:
    v = rel[i][1]
    md = v.get("metadataLayerUrl")
    info = ""
    if md:
        u = md + f"/identify?geometry={lon},{lat}&geometryType=esriGeometryPoint&sr=4326&layers=all&tolerance=0&mapExtent={lon-0.001},{lat-0.001},{lon+0.001},{lat+0.001}&imageDisplay=100,100,96&returnGeometry=false&f=json"
        b = fetch(u)
        if b:
            d = json.loads(b)
            for r in d.get("results", [])[:4]:
                a = r["attributes"]; info += f" | L{r.get('layerId')} {a.get('SRC_DATE2') or a.get('SRC_DATE')} res={a.get('SAMP_RES') or a.get('SRC_RES')} {a.get('NICE_DESC') or a.get('SRC_DESC')}"
    print(i, v["itemTitle"], cache.get(i), info[:300])
