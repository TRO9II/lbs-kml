"""Проверка источников высот/рельефа и точности декодирования Terrarium против Open-Meteo (Copernicus DEM)."""
import json, math, struct, urllib.request, zlib

UA = {"User-Agent": "lbs-kml check (https://github.com/TRO9II/lbs-kml)"}
PTS = [(48.2830, 37.1760, "Покровск"), (47.6790, 37.0270, "Запорожская"), (49.5880, 37.7640, "Купянск"),
       (48.5960, 38.0010, "Бахмут"), (50.9077, 34.7981, "Сумы")]


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return r.status, r.read(), r.headers.get("Content-Type", "")


def tile(lat, lon, z):
    n = 2 ** z
    xf = (lon + 180) / 360 * n
    yf = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
    return int(xf), int(yf), (xf - int(xf)) * 256, (yf - int(yf)) * 256


def png_rgb(data):
    """Минимальный декодер PNG (8-bit RGB/RGBA, без интерлейса)."""
    pos, w, h, ct, idat = 8, 0, 0, 0, b""
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", body[:10])
        elif typ == b"IDAT":
            idat += body
        pos += 12 + ln
    bpp = 4 if ct == 6 else 3
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; i += 1
        cur = bytearray(raw[i:i + stride]); i += stride
        for x in range(stride):
            a = cur[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: cur[x] = (cur[x] + a) & 255
            elif f == 2: cur[x] = (cur[x] + b) & 255
            elif f == 3: cur[x] = (cur[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                cur[x] = (cur[x] + pr) & 255
        rows.append(cur); prev = cur
    return w, bpp, rows


for lat, lon, name in PTS:
    z = 14
    x, y, px, py = tile(lat, lon, z)
    try:
        st, data, ct = get(f"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png")
        w, bpp, rows = png_rgb(data)
        r, g, b = rows[int(py)][int(px) * bpp:int(px) * bpp + 3]
        terr = r * 256 + g + b / 256 - 32768
    except Exception as e:  # noqa
        terr = f"ERR {e}"
    try:
        st, d2, _ = get(f"https://api.open-meteo.com/v1/elevation?latitude={lat}&longitude={lon}")
        om = json.loads(d2)["elevation"][0]
    except Exception as e:  # noqa
        om = f"ERR {e}"
    print(f"ELEV {name}: terrarium={terr if isinstance(terr, str) else round(terr, 1)} m | open-meteo(Copernicus)={om} m")

for name, url in [
    ("opentopomap", "https://a.tile.opentopomap.org/13/{x}/{y}.png"),
    ("esri_hillshade", "https://server.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/13/{y}/{x}"),
    ("terrarium15", "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/15/{x}/{y}.png"),
]:
    zz = 15 if name == "terrarium15" else 13
    x, y, _, _ = tile(48.283, 37.176, zz)
    try:
        st, data, ct = get(url.format(x=x, y=y))
        print("TILE", name, st, len(data), ct)
    except Exception as e:  # noqa
        print("TILE", name, "ERR", e)
