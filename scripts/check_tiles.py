"""Проверка источников спутниковых снимков: доступность и размер тайлов (больше — детальнее)."""
import json, math, urllib.request

LAT, LON = 48.2830, 37.1760  # Покровск
UA = {"User-Agent": "lbs-kml tile check (https://github.com/TRO9II/lbs-kml)"}


def tile(z):
    n = 2 ** z
    x = int((LON + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(LAT))) / math.pi) / 2 * n)
    return x, y


def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
            b = r.read()
            return r.status, len(b), r.headers.get("Content-Type", "")
    except Exception as e:  # noqa
        return getattr(e, "code", "ERR"), 0, str(e)[:80]


SRC = {
    "esri": "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    "clarity": "https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    "esri_services": "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
}
for name, tpl in SRC.items():
    for z in (15, 17, 18, 19):
        x, y = tile(z)
        print(name, z, get(tpl.format(z=z, x=x, y=y)))

# Esri Wayback: последние выпуски снимков
try:
    with urllib.request.urlopen(urllib.request.Request(
            "https://s3-us-west-2.amazonaws.com/config.maptiles.arcgis.com/waybackconfig.json", headers=UA), timeout=30) as r:
        cfg = json.load(r)
    rel = sorted(cfg.items(), key=lambda kv: kv[1].get("itemTitle", ""), reverse=True)[:3]
    for k, v in rel:
        print("wayback", k, v.get("itemTitle"), v.get("itemURL"))
    k = rel[0][0]
    for z in (17, 18, 19):
        x, y = tile(z)
        url = ("https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/"
               f"{k}/{z}/{y}/{x}")
        print("wayback_latest", z, get(url))
except Exception as e:  # noqa
    print("wayback ERR", e)

# метаданные даты снимка Esri в точке
try:
    url = ("https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/identify?"
           f"geometry={LON},{LAT}&geometryType=esriGeometryPoint&sr=4326&layers=all&tolerance=1"
           "&mapExtent=37,48,37.5,48.5&imageDisplay=400,400,96&returnGeometry=false&f=json")
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        print("identify", r.read()[:600])
except Exception as e:  # noqa
    print("identify ERR", e)
