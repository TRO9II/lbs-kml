package ru.lbs.kml;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.events.MapListener;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.modules.SqlTileWriter;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.views.overlay.Marker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Карта ЛБС по направлениям (областям). */
public class MapActivity extends Activity {

    private static final String[] NAMES = {
            "Вся линия", "Херсонская", "Запорожская", "Днепропетровская",
            "Донецкая", "Харьковская", "Сумская"
    };
    /** север, восток, юг, запад */
    private static final double[][] BOX = {
            {51.95, 38.60, 46.30, 32.20},
            {47.40, 34.80, 46.20, 32.20},
            {48.00, 37.00, 46.80, 34.60},
            {48.50, 37.00, 47.60, 35.60},
            {49.30, 38.60, 47.20, 36.50},
            {50.50, 38.40, 49.00, 36.00},
            {52.40, 35.80, 50.50, 33.00},
    };

    private MapView map;
    private final List<Button> chips = new ArrayList<>();
    private int selected = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        IConfigurationProvider conf = Configuration.getInstance();
        conf.load(this, LbsCore.prefs(this));
        conf.setUserAgentValue(getPackageName());
        // во внутренней памяти (а не в кэше), чтобы система не удаляла скачанные области
        File base = new File(getFilesDir(), "osmdroid");
        conf.setOsmdroidBasePath(base);
        conf.setOsmdroidTileCache(new File(base, "tiles"));
        conf.setTileDownloadThreads((short) 8);          // по умолчанию 2 — отсюда медленная прогрузка
        conf.setTileFileSystemThreads((short) 8);
        conf.setTileDownloadMaxQueueSize((short) 120);
        conf.setTileFileSystemMaxQueueSize((short) 120);
        conf.setTileFileSystemCacheMaxBytes(3000L * 1024 * 1024);
        conf.setTileFileSystemCacheTrimBytes(2500L * 1024 * 1024);
        conf.setExpirationExtendedDuration(60L * 24 * 60 * 60 * 1000); // скачанное считается свежим 60 дней

        setContentView(R.layout.activity_map);
        applyInsets(findViewById(R.id.mapRoot), findViewById(R.id.topBar), findViewById(R.id.bottomBar));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        map = findViewById(R.id.map);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.setMinZoomLevel(5.0);
        map.setMaxZoomLevel(19.5);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);

        findViewById(R.id.btnZoomIn).setOnClickListener(v -> map.getController().zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> map.getController().zoomOut());
        findViewById(R.id.btnLayers).setOnClickListener(v -> chooseStyle());
        findViewById(R.id.btnDownload).setOnClickListener(v -> chooseDownload());
        findViewById(R.id.dlCancel).setOnClickListener(v -> cancelDownload());
        findViewById(R.id.btnLabels).setOnClickListener(v -> toggleLabels());
        styleLabelsButton();
        setupSearch();
        map.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent e) { updateImageryInfo(); return false; }
            @Override public boolean onZoom(ZoomEvent e) { updateImageryInfo(); return false; }
        });

        KmlBuilder.Parsed data;
        try {
            data = LbsCore.loadMapData(this);
        } catch (Exception e) {
            data = null;
        }
        if (data == null || data.lines.isEmpty()) {
            Toast.makeText(this, "Сначала нажмите «Обновить ЛБС»", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ((TextView) findViewById(R.id.mapDate)).setText("Снимок DeepState: " + data.date.replace(" o ", ", "));
        addOverlays(data);
        loadPlaces();
        applyStyle(LbsCore.prefs(this).getInt("mapStyle", STYLE_SAT));
        buildChips();

        if (savedInstanceState != null) selected = savedInstanceState.getInt("sel", 0);
        map.addOnFirstLayoutListener((v, l, t, r, b) -> zoomTo(selected, false));
    }

    // ---------- Подложка ----------

    private static final int STYLE_SCHEME = 0, STYLE_SAT = 1, STYLE_TOPO = 2;
    private static final String[] STYLE_NAMES = {"Схема", "Спутник (Esri)", "Топокарта (Esri)"};
    private static final String ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/";

    /** Тайлы Esri: порядок в адресе z/y/x. */
    private static final class EsriSource extends OnlineTileSourceBase {
        EsriSource(String name, String service, String ext) {
            // у Esri в Украине детальные снимки до 18 уровня; дальше карта увеличивает 18-й
            super(name, 0, 18, 256, ext, new String[]{ESRI + service + "/MapServer/tile/"});
        }

        @Override
        public String getTileURLString(long idx) {
            return getBaseUrl() + MapTileIndex.getZoom(idx) + "/" + MapTileIndex.getY(idx) + "/" + MapTileIndex.getX(idx);
        }
    }

    private static final OnlineTileSourceBase SAT = new EsriSource("EsriImagery", "World_Imagery", ".jpg");
    private static final OnlineTileSourceBase TOPO = new EsriSource("EsriTopo", "World_Topo_Map", ".jpg");
    private static final OnlineTileSourceBase LABELS = new EsriSource("EsriLabels", "Reference/World_Boundaries_and_Places", ".png");

    private TilesOverlay labels;

    private static final OnlineTileSourceBase CARTO_NOLABELS = new XYTileSource("CartoVoyagerNoLabels",
            0, 19, 256, ".png", new String[]{
            "https://a.basemaps.cartocdn.com/rastertiles/voyager_nolabels/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager_nolabels/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager_nolabels/"});

    private int currentStyle = STYLE_SAT;

    private void applyStyle(int style) {
        if (style < 0 || style > 2) style = STYLE_SAT;
        currentStyle = style;
        LbsCore.prefs(this).edit().putInt("mapStyle", style).apply();
        TextView attr = findViewById(R.id.attribution);
        if (labels != null) {
            map.getOverlays().remove(labels);
            labels.onDetach(map);
            labels = null;
        }
        boolean ru = labelsOn();
        // спутник — без растягивания под плотность экрана (иначе размыто); схемы — с ним (крупнее подписи)
        map.setTilesScaledToDpi(style != STYLE_SAT);
        updateImageryInfo();
        if (style == STYLE_SCHEME) {
            // с русскими подписями — схема без своих подписей, чтобы не дублировать
            map.setTileSource(ru ? CARTO_NOLABELS : TileSourceFactory.MAPNIK);
            attr.setText(ru ? "© OpenStreetMap, © CARTO" : "© участники OpenStreetMap");
        } else if (style == STYLE_TOPO) {
            map.setTileSource(TOPO);
            attr.setText("Esri, HERE, Garmin, USGS, © OpenStreetMap");
        } else {
            map.setTileSource(SAT);
            if (!ru) {
                // английские подписи Esri, если русские выключены
                MapTileProviderBasic prov = new MapTileProviderBasic(getApplicationContext(), LABELS);
                labels = new TilesOverlay(prov, getApplicationContext());
                labels.setLoadingBackgroundColor(Color.TRANSPARENT);
                labels.setLoadingLineColor(Color.TRANSPARENT);
                map.getOverlays().add(0, labels);
            }
            attr.setText("Esri, Maxar, Earthstar Geographics");
        }
        if (placesOverlay != null) placesOverlay.setEnabled(ru);
        map.invalidate();
    }

    // ---------- Дата спутникового снимка ----------

    private final Handler infoHandler = new Handler(Looper.getMainLooper());
    private final Runnable infoTask = this::queryImageryDate;
    private int infoSeq = 0;

    private void updateImageryInfo() {
        infoHandler.removeCallbacks(infoTask);
        TextView t = findViewById(R.id.imageryInfo);
        if (currentStyle != STYLE_SAT) {
            t.setVisibility(View.GONE);
            return;
        }
        infoHandler.postDelayed(infoTask, 1200);
    }

    /** Спрашивает у Esri дату и разрешение снимка в центре экрана. */
    private void queryImageryDate() {
        TextView t = findViewById(R.id.imageryInfo);
        if (map.getZoomLevelDouble() < 11) {
            t.setVisibility(View.VISIBLE);
            t.setText("Приблизьте карту, чтобы увидеть дату снимка");
            return;
        }
        final int seq = ++infoSeq;
        final GeoPoint c = (GeoPoint) map.getMapCenter();
        final BoundingBox bb = map.getBoundingBox();
        final int w = Math.max(1, map.getWidth()), h = Math.max(1, map.getHeight());
        new Thread(() -> {
            String text;
            try {
                String url = ESRI + "World_Imagery/MapServer/identify?geometryType=esriGeometryPoint&sr=4326"
                        + "&layers=top&tolerance=1&returnGeometry=false&f=json"
                        + "&geometry=" + c.getLongitude() + "," + c.getLatitude()
                        + "&mapExtent=" + bb.getLonWest() + "," + bb.getLatSouth() + "," + bb.getLonEast() + "," + bb.getLatNorth()
                        + "&imageDisplay=" + w + "," + h + ",96";
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestProperty("User-Agent", getPackageName());
                con.setConnectTimeout(10000);
                con.setReadTimeout(15000);
                String body;
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int k;
                    while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                    body = bo.toString("UTF-8");
                }
                JSONArray res = new JSONObject(body).optJSONArray("results");
                text = null;
                if (res != null) {
                    for (int i = 0; i < res.length() && text == null; i++) {
                        JSONObject a = res.getJSONObject(i).optJSONObject("attributes");
                        if (a == null) continue;
                        String d = a.optString("DATE (YYYYMMDD)", "");
                        if (d.length() != 8) continue;
                        String date = d.substring(6, 8) + "." + d.substring(4, 6) + "." + d.substring(0, 4);
                        String resM = a.optString("RESOLUTION (M)", "");
                        String src = a.optString("SOURCE", "");
                        StringBuilder sb = new StringBuilder("Спутниковый снимок: ").append(date);
                        if (!resM.isEmpty()) {
                            try {
                                double m = Double.parseDouble(resM);
                                sb.append(" · ").append(m < 1 ? Math.round(m * 100) + " см" : (Math.round(m * 10) / 10.0) + " м");
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (!src.isEmpty()) sb.append(" · ").append(src);
                        text = sb.toString();
                    }
                }
                if (text == null) text = "Дата снимка неизвестна";
            } catch (Exception e) {
                text = "Дата снимка: нет связи";
            }
            final String out = text;
            runOnUiThread(() -> {
                if (seq != infoSeq || isFinishing() || currentStyle != STYLE_SAT) return;
                t.setVisibility(View.VISIBLE);
                t.setText(out);
            });
        }).start();
    }

    // ---------- Русские подписи ----------

    private PlacesOverlay placesOverlay;
    private Places places;

    private boolean labelsOn() {
        return LbsCore.prefs(this).getBoolean("labelsRu", true);
    }

    private void toggleLabels() {
        boolean on = !labelsOn();
        LbsCore.prefs(this).edit().putBoolean("labelsRu", on).apply();
        applyStyle(currentStyle);
        styleLabelsButton();
        Toast.makeText(this, on ? "Подписи на русском включены" : "Подписи на русском выключены",
                Toast.LENGTH_SHORT).show();
    }

    private void styleLabelsButton() {
        TextView b = findViewById(R.id.btnLabels);
        b.setTextColor(labelsOn() ? getColor(R.color.accent) : getColor(R.color.text2));
    }

    private void loadPlaces() {
        placesOverlay = new PlacesOverlay(getResources().getDisplayMetrics().density);
        placesOverlay.setEnabled(labelsOn());
        map.getOverlays().add(placesOverlay);
        new Thread(() -> {
            try {
                Places p = Places.get(getApplicationContext());
                runOnUiThread(() -> {
                    places = p;
                    placesOverlay.setPlaces(p);
                    map.invalidate();
                    if (searchBox != null && searchBox.getText().length() > 0) runLocalSearch();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Список населённых пунктов не загрузился",
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------- Поиск ----------

    private EditText searchBox;
    private LinearLayout results;
    private View resultsScroll;
    private Marker found;
    private Thread onlineSearch;

    private void setupSearch() {
        searchBox = findViewById(R.id.searchBox);
        results = findViewById(R.id.results);
        resultsScroll = findViewById(R.id.resultsScroll);
        findViewById(R.id.btnSearch).setOnClickListener(v -> openSearch(true));
        findViewById(R.id.btnSearchClose).setOnClickListener(v -> openSearch(false));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { runLocalSearch(); }
        });
        searchBox.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String q = searchBox.getText().toString().trim();
                if (places != null && !places.search(q, 1).isEmpty()) {
                    showPlace(places.search(q, 1).get(0));
                } else if (!q.isEmpty()) {
                    searchOnline(q);
                }
                return true;
            }
            return false;
        });
    }

    private void openSearch(boolean open) {
        findViewById(R.id.titleRow).setVisibility(open ? View.GONE : View.VISIBLE);
        findViewById(R.id.searchRow).setVisibility(open ? View.VISIBLE : View.GONE);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (open) {
            searchBox.requestFocus();
            if (imm != null) imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
        } else {
            searchBox.setText("");
            showResults(false);
            if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        }
    }

    private void showResults(boolean show) {
        resultsScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        findViewById(R.id.chipsScroll).setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void runLocalSearch() {
        String q = searchBox.getText().toString().trim();
        results.removeAllViews();
        if (q.isEmpty()) { showResults(false); return; }
        showResults(true);
        if (places == null) {
            addResultRow("Загружаю список…", "", null);
            return;
        }
        List<Integer> hits = places.search(q, 30);
        for (int i : hits) {
            final int idx = i;
            addResultRow(places.name[i], places.subtitle(i), v -> showPlace(idx));
        }
        final String query = q;
        addResultRow("🌐 Искать «" + q + "» в интернете", "OpenStreetMap Nominatim", v -> searchOnline(query));
    }

    private void addResultRow(String title, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = (int) dp(16), pv = (int) dp(10);
        row.setPadding(ph, pv, ph, pv);
        if (click != null) {
            row.setBackgroundResource(resolveSelectable());
            row.setOnClickListener(click);
        }
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.text));
        t.setTextSize(16);
        row.addView(t);
        if (!sub.isEmpty()) {
            TextView st = new TextView(this);
            st.setText(sub);
            st.setTextColor(getColor(R.color.text2));
            st.setTextSize(13);
            row.addView(st);
        }
        results.addView(row);
    }

    private int resolveSelectable() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private void showPlace(int i) {
        goTo(places.lat[i], places.lon[i], places.name[i], places.subtitle(i), places.rank[i] <= 1 ? 12.5 : 14.0);
    }

    private void goTo(double lat, double lon, String title, String sub, double zoom) {
        openSearch(false);
        GeoPoint g = new GeoPoint(lat, lon);
        if (found != null) map.getOverlays().remove(found);
        found = new Marker(map);
        found.setPosition(g);
        found.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        found.setTitle(title);
        found.setSnippet(sub);
        map.getOverlays().add(found);
        map.getController().animateTo(g, zoom, 900L);
        map.invalidate();
    }

    /** Поиск в интернете через Nominatim (OpenStreetMap), названия на русском. */
    private void searchOnline(String q) {
        if (onlineSearch != null && onlineSearch.isAlive()) return;
        results.removeAllViews();
        showResults(true);
        addResultRow("Ищу в интернете…", "", null);
        onlineSearch = new Thread(() -> {
            List<String[]> out = new ArrayList<>();
            String err = null;
            try {
                URL url = new URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=15"
                        + "&accept-language=ru&countrycodes=ua,ru&q=" + URLEncoder.encode(q, "UTF-8"));
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestProperty("User-Agent", getPackageName() + " (https://github.com/TRO9II/lbs-kml)");
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int k;
                    while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                    JSONArray arr = new JSONArray(bo.toString("UTF-8"));
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject o = arr.getJSONObject(j);
                        String disp = o.optString("display_name");
                        int comma = disp.indexOf(',');
                        out.add(new String[]{comma > 0 ? disp.substring(0, comma) : disp,
                                comma > 0 ? disp.substring(comma + 1).trim() : "",
                                o.optString("lat"), o.optString("lon")});
                    }
                }
            } catch (Exception e) {
                err = "Нет связи с сервером поиска";
            }
            final String error = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                results.removeAllViews();
                if (error != null) addResultRow(error, "Проверьте интернет", null);
                else if (out.isEmpty()) addResultRow("Ничего не найдено", "", null);
                for (String[] r : out) {
                    addResultRow(r[0], r[1], v -> {
                        try {
                            goTo(Double.parseDouble(r[2]), Double.parseDouble(r[3]), r[0], r[1], 13.0);
                        } catch (NumberFormatException ignored) {
                        }
                    });
                }
            });
        });
        onlineSearch.start();
    }

    private void chooseStyle() {
        int cur = LbsCore.prefs(this).getInt("mapStyle", STYLE_SAT);
        new AlertDialog.Builder(this)
                .setTitle("Подложка карты")
                .setSingleChoiceItems(STYLE_NAMES, cur, (d, which) -> {
                    applyStyle(which);
                    d.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ---------- Скачивание области для офлайна ----------

    private static final int MAX_TILES = 25000;
    private CacheManager.CacheManagerTask dlTask;

    private File tileDb() {
        return new File(Configuration.getInstance().getOsmdroidTileCache(), "cache.db");
    }

    private static String mb(double bytes) {
        double m = bytes / 1024 / 1024;
        return m >= 1024 ? String.format(Locale.US, "%.1f ГБ", m / 1024) : Math.max(1, Math.round(m)) + " МБ";
    }

    /** Примерный размер одного тайла, байт. */
    private int tileBytes() {
        return currentStyle == STYLE_SAT ? 20000 : currentStyle == STYLE_TOPO ? 16000 : 9000;
    }

    private void chooseDownload() {
        if (dlTask != null) {
            Toast.makeText(this, "Загрузка уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        final CacheManager cm;
        try {
            cm = new CacheManager(map);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Нельзя скачать эту карту")
                    .setMessage("Правила OpenStreetMap запрещают массовое скачивание схемы с их подписями. "
                            + "Включите «Аа» (русские подписи) или выберите спутник / топокарту.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        final BoundingBox bb = map.getBoundingBox();
        final int zMin = 8;
        final int[] zMaxes = {15, 16, 17, 18};
        final String[] labels = new String[zMaxes.length];
        final int[] counts = new int[zMaxes.length];
        for (int i = 0; i < zMaxes.length; i++) {
            counts[i] = cm.possibleTilesInArea(bb, zMin, zMaxes[i]);
            String detail = zMaxes[i] <= 15 ? "обзор" : zMaxes[i] == 16 ? "сёла" : zMaxes[i] == 17 ? "улицы" : "максимум, дома";
            labels[i] = "До уровня " + zMaxes[i] + " (" + detail + ") — " + mb((double) counts[i] * tileBytes())
                    + (counts[i] > MAX_TILES ? " · слишком много, приблизьте" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Скачать видимую область")
                .setItems(labels, (d, which) -> {
                    if (counts[which] > MAX_TILES) {
                        Toast.makeText(this, "Слишком большая область — приблизьте карту или выберите уровень ниже",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    startDownload(cm, bb, zMin, zMaxes[which], counts[which]);
                })
                .setNeutralButton("Очистить (" + mb(tileDb().length()) + ")", (d, w) -> confirmClear())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void startDownload(CacheManager cm, BoundingBox bb, int zMin, int zMax, int total) {
        final TextView t = findViewById(R.id.dlText);
        findViewById(R.id.dlRow).setVisibility(View.VISIBLE);
        t.setText("Скачиваю область: 0%");
        dlTask = cm.downloadAreaAsyncNoUI(this, bb, zMin, zMax, new CacheManager.CacheManagerCallback() {
            @Override
            public void onTaskComplete() {
                finishDownload("Область сохранена — работает без интернета");
            }

            @Override
            public void onTaskFailed(int errors) {
                finishDownload("Скачано с ошибками (" + errors + " тайлов). Проверьте интернет и повторите");
            }

            @Override
            public void updateProgress(int progress, int currentZoomLevel, int zoomMin, int zoomMax) {
                int pct = total > 0 ? Math.min(100, progress * 100 / total) : 0;
                t.setText("Скачиваю область: " + pct + "% · уровень " + currentZoomLevel + " из " + zoomMax);
            }

            @Override
            public void downloadStarted() {
            }

            @Override
            public void setPossibleTilesInArea(int total) {
            }
        });
    }

    private void finishDownload(String msg) {
        dlTask = null;
        findViewById(R.id.dlRow).setVisibility(View.GONE);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        map.invalidate();
    }

    private void cancelDownload() {
        if (dlTask != null) dlTask.cancel(true);
        finishDownload("Загрузка остановлена. Уже скачанное сохранено");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Удалить сохранённые карты?")
                .setMessage("Все скачанные и просмотренные участки карты будут удалены (" + mb(tileDb().length())
                        + "). Линия фронта и подписи останутся.")
                .setPositiveButton("Удалить", (d, w) -> {
                    try {
                        new SqlTileWriter().purgeCache();
                    } catch (Exception ignored) {
                    }
                    map.invalidate();
                    Toast.makeText(this, "Сохранённые карты удалены", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static List<GeoPoint> pts(double[][] l) {
        List<GeoPoint> out = new ArrayList<>(l.length);
        for (double[] p : l) out.add(new GeoPoint(p[1], p[0]));
        return out;
    }

    private void addOverlays(KmlBuilder.Parsed d) {
        boolean gray = LbsCore.optGray(this), diff = LbsCore.optDiff(this);
        if (gray) {
            for (double[][] g : d.gray) {
                Polygon pg = new Polygon();
                pg.setPoints(pts(g));
                pg.getFillPaint().setColor(Color.argb(90, 120, 120, 120));
                pg.getOutlinePaint().setColor(Color.argb(200, 110, 110, 110));
                pg.getOutlinePaint().setStrokeWidth(dp(1));
                map.getOverlays().add(pg);
            }
        }
        if (diff) {
            for (double[][] l : d.removed) map.getOverlays().add(line(l, Color.rgb(140, 140, 140), 3));
        }
        for (double[][] l : d.lines) map.getOverlays().add(line(l, Color.rgb(220, 0, 0), 3.5f));
        if (diff) {
            for (double[][] l : d.added) map.getOverlays().add(line(l, Color.rgb(255, 210, 0), 5));
        }
        findViewById(R.id.legendDiff).setVisibility(diff ? View.VISIBLE : View.GONE);
        findViewById(R.id.legendGray).setVisibility(gray ? View.VISIBLE : View.GONE);
    }

    private Polyline line(double[][] l, int color, float widthDp) {
        Polyline pl = new Polyline();
        pl.setPoints(pts(l));
        Paint p = pl.getOutlinePaint();
        p.setColor(color);
        p.setStrokeWidth(dp(widthDp));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setAntiAlias(true);
        return pl;
    }

    private void buildChips() {
        LinearLayout row = findViewById(R.id.chips);
        for (int i = 0; i < NAMES.length; i++) {
            Button b = new Button(this);
            b.setText(NAMES[i]);
            b.setAllCaps(false);
            b.setTextSize(14);
            b.setStateListAnimator(null);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            int ph = (int) dp(14), pv = (int) dp(8);
            b.setPadding(ph, pv, ph, pv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) dp(8));
            final int idx = i;
            b.setOnClickListener(v -> zoomTo(idx, true));
            row.addView(b, lp);
            chips.add(b);
        }
        styleChips();
    }

    private void styleChips() {
        for (int i = 0; i < chips.size(); i++) {
            Button b = chips.get(i);
            boolean on = i == selected;
            b.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            b.setTextColor(on ? Color.WHITE : getColor(R.color.text));
        }
    }

    private void zoomTo(int i, boolean animated) {
        selected = i;
        styleChips();
        double[] b = BOX[i];
        map.zoomToBoundingBox(new BoundingBox(b[0], b[1], b[2], b[3]), animated, (int) dp(24));
    }

    private void applyInsets(View root, View top, View bottom) {
        final int tp = top.getPaddingTop(), bp = bottom.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int t, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                t = in.top; b = in.bottom;
            } else {
                t = insets.getSystemWindowInsetTop(); b = insets.getSystemWindowInsetBottom();
            }
            top.setPadding(top.getPaddingLeft(), tp + t, top.getPaddingRight(), top.getPaddingBottom());
            bottom.setPadding(bottom.getPaddingLeft(), bottom.getPaddingTop(), bottom.getPaddingRight(), bp + b);
            return insets;
        });
        root.requestApplyInsets();
    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.searchRow).getVisibility() == View.VISIBLE) {
            openSearch(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("sel", selected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override
    protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        infoHandler.removeCallbacksAndMessages(null);
        if (dlTask != null) dlTask.cancel(true);
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
