import urllib.request, math
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
srcs = {
 "esri-transport": "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}",
 "openrailwaymap": "https://a.tiles.openrailwaymap.org/standard/{z}/{x}/{y}.png",
 "waymarked-hiking": "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png",
 "osm-hot": "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
}
for name, url in srcs.items():
    for z in (12, 15, 17, 19):
        x, y = tile(48.0159, 37.8028, z)  # Донецк
        try:
            r = urllib.request.urlopen(urllib.request.Request(url.format(z=z, x=x, y=y), headers={"User-Agent": "ru.lbs.kml (https://github.com/TRO9II/lbs-kml)"}), timeout=20)
            b = r.read()
            print(name, z, r.status, r.headers.get("Content-Type"), len(b), "PNG" if b[:4] == b"\x89PNG" else b[:4], "colortype", b[25] if b[:4] == b"\x89PNG" else "")
        except Exception as e:
            print(name, z, "ERR", e)
