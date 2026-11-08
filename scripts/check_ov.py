import urllib.request, math, io, subprocess, sys
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "pillow"], check=True)
from PIL import Image
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
url = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"
for (lat, lon, nm) in ((48.0159, 37.8028, "Donetsk city"), (48.30, 37.60, "rural")):
    for z in (11, 13, 15, 17):
        x, y = tile(lat, lon, z)
        b = urllib.request.urlopen(urllib.request.Request(url.format(z=z, x=x, y=y), headers={"User-Agent": "ru.lbs.kml"}), timeout=20).read()
        im = Image.open(io.BytesIO(b)).convert("RGBA")
        px = list(im.getdata())
        n = len(px)
        opaque = sum(1 for p in px if p[3] > 200) / n
        semi = sum(1 for p in px if 0 < p[3] <= 200) / n
        from collections import Counter
        c = Counter((p[0]//32, p[1]//32, p[2]//32, p[3]//64) for p in px if p[3] > 0).most_common(5)
        print(nm, z, f"opaque={opaque:.2%} semi={semi:.2%}", c)
