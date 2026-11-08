"""Собирает населённые пункты зоны фронта из OpenStreetMap (Overpass) в assets/places.tsv.gz.

Формат строки (TSV): ранг  название(ru)  второе_название  lat  lon  население  ближайший_город  км
ранг: 0 город, 1 посёлок/пгт, 2 село, 3 хутор
"""
import gzip, json, math, sys, urllib.parse, urllib.request

BBOX = (45.9, 31.0, 52.6, 40.8)  # юг, запад, север, восток
QUERY = ('[out:json][timeout:600];'
         'node["place"~"^(city|town|village|hamlet)$"](%s,%s,%s,%s);'
         'out body qt;') % BBOX
SERVERS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
RANK = {"city": 0, "town": 1, "village": 2, "hamlet": 3}
OUT = "app/src/main/assets/places.tsv.gz"


def fetch():
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    for url in SERVERS:
        try:
            req = urllib.request.Request(url, data=body, headers={
                "User-Agent": "lbs-kml places builder (https://github.com/TRO9II/lbs-kml)"})
            with urllib.request.urlopen(req, timeout=700) as r:
                data = json.load(r)
            print("OK", url, len(data.get("elements", [])))
            return data
        except Exception as e:  # noqa
            print("FAIL", url, e)
    sys.exit("all Overpass servers failed")


def km(lat1, lon1, lat2, lon2):
    x = (lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2)) * 111.32
    y = (lat2 - lat1) * 110.57
    return math.hypot(x, y)


def main():
    data = fetch()
    rows = []
    with_ru = 0
    for el in data["elements"]:
        t = el.get("tags", {})
        place = t.get("place")
        if place not in RANK:
            continue
        ru = t.get("name:ru", "").strip()
        nm = t.get("name", "").strip()
        uk = t.get("name:uk", "").strip()
        disp = ru or nm
        if not disp:
            continue
        if ru:
            with_ru += 1
        alt = ""
        for cand in (uk, nm):
            if cand and cand != disp:
                alt = cand
                break
        pop = t.get("population", "").replace(" ", "").replace(",", "").replace(".", "")
        pop = int(pop) if pop.isdigit() else 0
        rows.append([RANK[place], disp.replace("\t", " "), alt.replace("\t", " "),
                     round(el["lat"], 5), round(el["lon"], 5), pop])

    # ближайший город/посёлок для сёл (чтобы различать одноимённые)
    big = [r for r in rows if r[0] <= 1]
    grid = {}
    for b in big:
        grid.setdefault((int(b[3] * 4), int(b[4] * 3)), []).append(b)
    for r in rows:
        best, bd = None, 1e9
        cy, cx = int(r[3] * 4), int(r[4] * 3)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for b in grid.get((cy + dy, cx + dx), []):
                    if b is r:
                        continue
                    d = km(r[3], r[4], b[3], b[4])
                    if d < bd:
                        best, bd = b, d
        if best is not None and r[0] >= 1:
            r += [best[1], int(round(bd))]
        else:
            r += ["", 0]

    rows.sort(key=lambda r: (r[0], -r[5], r[1]))
    with gzip.open(OUT, "wt", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(str(v) for v in r) + "\n")
    print("places:", len(rows), "with name:ru:", with_ru,
          "by rank:", [sum(1 for r in rows if r[0] == k) for k in range(4)])


if __name__ == "__main__":
    main()
