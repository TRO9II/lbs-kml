import math, urllib.request, datetime, json
def tile(lat, lon, z):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    r = math.radians(lat)
    y = int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n)
    return x, y
def get(u, hdr=None):
    try:
        req = urllib.request.Request(u, headers=hdr or {"User-Agent": "Mozilla/5.0 Rubezh"})
        r = urllib.request.urlopen(req, timeout=20)
        b = r.read()
        return r.status, len(b), r.headers.get("Content-Type"), b[:120]
    except urllib.error.HTTPError as e:
        return e.code, 0, "", e.read()[:200]
    except Exception as e:
        return "ERR", 0, "", str(e)[:200]
lat, lon = 48.28, 37.18
for z in (10, 12, 14, 17, 19):
    x, y = tile(lat, lon, z)
    print("GOOGLE", z, get(f"https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}")[:3])
for yr in (2024, 2023):
    x, y = tile(lat, lon, 13)
    print("EOX", yr, get(f"https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-{yr}_3857/default/g/13/{y}/{x}.jpg")[:3])
for d in range(1, 15):
    day = (datetime.date.today() - datetime.timedelta(days=d)).isoformat()
    for lay, lvl in (("HLS_S30_Nadir_BRDF_Adjusted_Reflectance", 12), ("HLS_L30_Nadir_BRDF_Adjusted_Reflectance", 12)):
        x, y = tile(lat, lon, 11)
        st = get(f"https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/{lay}/default/{day}/GoogleMapsCompatible_Level{lvl}/11/{y}/{x}.jpg")
        print("GIBS", lay[:7], day, st[:3])
# Copernicus Data Space public WMTS? (requires instance) — skip
