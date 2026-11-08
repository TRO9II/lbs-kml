(function () {
  var d;
  try { d = JSON.parse(document.body.innerText); } catch (e) { return 'WAIT'; }
  if (!d || !d.map || !d.map.features) return 'ERR Неожиданный формат данных DeepStateMap';
  var F = d.map.features.filter(function (f) { return f.geometry && f.geometry.type == 'Polygon'; });
  var nm = function (f) { return (f.properties && f.properties.name) || ''; };
  var gray = F.filter(function (f) { return nm(f).indexOf('Статус невідомий') == 0; });
  var occ = F.filter(function (f) { var n = nm(f); return n.indexOf('Окуповано') == 0 || n.indexOf('ОРДЛО') == 0; });
  if (!occ.length) return 'ERR Не найдены оккупированные полигоны';
  var G = new Map(), cs = 0.04;
  var key = function (x, y) { return Math.floor(x / cs) + ',' + Math.floor(y / cs); };
  gray.forEach(function (f) {
    f.geometry.coordinates.forEach(function (r) {
      for (var i = 0; i < r.length - 1; i++) {
        var x1 = r[i][0], y1 = r[i][1], x2 = r[i + 1][0], y2 = r[i + 1][1];
        var n = Math.max(1, Math.ceil(Math.hypot(x2 - x1, y2 - y1) / 0.01));
        for (var k = 0; k < n; k++) {
          var x = x1 + (x2 - x1) * k / n, y = y1 + (y2 - y1) * k / n, kk = key(x, y);
          if (!G.has(kk)) G.set(kk, []);
          G.get(kk).push([x, y]);
        }
      }
    });
  });
  var near = function (x, y) {
    var cx = Math.floor(x / cs), cy = Math.floor(y / cs);
    for (var a = -1; a <= 1; a++) for (var b = -1; b <= 1; b++) {
      var p = G.get((cx + a) + ',' + (cy + b));
      if (p) for (var j = 0; j < p.length; j++) if (Math.hypot(p[j][0] - x, p[j][1] - y) < 0.04) return true;
    }
    return false;
  };
  var lines = [];
  occ.forEach(function (f) {
    f.geometry.coordinates.forEach(function (r0) {
      var r = r0.slice();
      if (r.length > 1 && r[0][0] == r[r.length - 1][0] && r[0][1] == r[r.length - 1][1]) r.pop();
      var N = r.length;
      if (N < 3) return;
      var m = r.map(function (p) {
        var x = p[0], y = p[1];
        return near(x, y) || (N > 300 && x >= 33.3 && x <= 35.4 && y > 46.75 && y < 47.6);
      });
      var m2 = m.slice();
      for (var i = 0; i < N; i++) {
        if (m[i] && !m[(i + 1) % N]) {
          var j = 1;
          while (j <= 5 && !m[(i + j) % N]) j++;
          if (j <= 5) for (var k = 1; k < j; k++) m2[(i + k) % N] = true;
        }
      }
      if (m2.every(function (v) { return v; })) { lines.push(r.concat([r[0]])); return; }
      var s = m2.indexOf(false), cur = [];
      for (var k2 = 1; k2 <= N; k2++) {
        var idx = (s + k2) % N;
        if (m2[idx]) cur.push(r[idx]);
        else { if (cur.length >= 3) lines.push(cur); cur = []; }
      }
      if (cur.length >= 3) lines.push(cur);
    });
  });
  if (!lines.length) return 'ERR Линия фронта не найдена';
  return 'DATE ' + d.datetime + '|' + d.id + '\n' + lines.map(function (l) {
    return l.map(function (p) { return p[0].toFixed(7) + ',' + p[1].toFixed(7); }).join(' ');
  }).join('\n');
})();
