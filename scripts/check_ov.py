import urllib.request, hashlib
H = {"User-Agent": "ru.lbs.kml (https://github.com/TRO9II/lbs-kml)"}
z, x, y = 13, 4956, 2852
for name, u in {
 "carto-voyager-nolabels": "https://a.basemaps.cartocdn.com/rastertiles/voyager_nolabels/{z}/{x}/{y}.png",
 "carto-light-nolabels": "https://a.basemaps.cartocdn.com/light_nolabels/{z}/{x}/{y}.png",
 "carto-voyager": "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
 "wmflabs-nolabels": "https://tiles.wmflabs.org/osm-no-labels/{z}/{x}/{y}.png",
 "esri-street": "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
 "esri-lightgray": "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}",
 "osm": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
 "osm-hot": "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
 "cyclosm": "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
}.items():
    try:
        r = urllib.request.urlopen(urllib.request.Request(u.format(z=z, x=x, y=y), headers=H), timeout=20)
        b = r.read()
        print(name, r.status, len(b), hashlib.md5(b).hexdigest()[:6])
    except Exception as e:
        print(name, "ERR", e)
