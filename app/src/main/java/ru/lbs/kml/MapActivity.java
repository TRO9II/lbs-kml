package ru.lbs.kml;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.modules.SqlTileWriter;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Карта ЛБС по направлениям: подложки, поиск, русские подписи, поворот, офлайн. */
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
    private String lbsDate = "";

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
        conf.setTileDownloadThreads((short) 8);
        conf.setTileFileSystemThreads((short) 8);
        conf.setTileDownloadMaxQueueSize((short) 120);
        conf.setTileFileSystemMaxQueueSize((short) 120);
        conf.setTileFileSystemCacheMaxBytes(3000L * 1024 * 1024);
        conf.setTileFileSystemCacheTrimBytes(2500L * 1024 * 1024);
        conf.setExpirationExtendedDuration(60L * 24 * 60 * 60 * 1000);

        setContentView(R.layout.activity_map);
        applyInsets(findViewById(R.id.mapRoot), findViewById(R.id.topBar), findViewById(R.id.bottomBar));

        map = findViewById(R.id.map);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.setMinZoomLevel(5.0);
        map.setMaxZoomLevel(19.5);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);

        // поворот карты двумя пальцами на 360°
        RotationGestureOverlay rot = new RotationGestureOverlay(map);
        rot.setEnabled(true);
        map.getOverlays().add(rot);

        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> map.getController().zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> map.getController().zoomOut());
        findViewById(R.id.btnLayers).setOnClickListener(v -> chooseStyle());
        findViewById(R.id.btnLabels).setOnClickListener(v -> toggleLabels());
        findViewById(R.id.btnDownload).setOnClickListener(v -> chooseDownload());
        findViewById(R.id.btnInfo).setOnClickListener(v -> showInfo());
        findViewById(R.id.btnCompass).setOnClickListener(v -> resetNorth());
        findViewById(R.id.dlCancel).setOnClickListener(v -> cancelDownload());
        findViewById(R.id.btnGrid).setOnClickListener(v -> toggleGrid());
        findViewById(R.id.btnPoints).setOnClickListener(v -> showPointsList());
        findViewById(R.id.coordCard).setOnClickListener(v -> copyCenterCoords());
        findViewById(R.id.targetClear).setOnClickListener(v -> clearTarget());
        styleLabelsButton();
        setupSearch();

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
        lbsDate = data.date.replace(" o ", ", ");
        // долгое нажатие — новая точка (слой в самом низу, чтобы нажатия на булавки обрабатывались раньше)
        map.getOverlays().add(0, new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) { return false; }
            @Override public boolean longPressHelper(GeoPoint p) { editPoint(null, p.getLatitude(), p.getLongitude()); return true; }
        }));
        addOverlays(data);
        gridOverlay = new GridOverlay(getResources().getDisplayMetrics().density);
        map.getOverlays().add(gridOverlay);
        reticleOverlay = new ReticleOverlay(getResources().getDisplayMetrics().density);
        restoreTarget();
        loadPlaces();
        pointsOverlay = new PointsOverlay(getResources().getDisplayMetrics().density);
        pointsOverlay.setPoints(userPoints = UserPoints.load(this));
        pointsOverlay.setListener(this::onPointTap);
        map.getOverlays().add(pointsOverlay);
        map.getOverlays().add(reticleOverlay);
        map.getOverlays().add(new CompassSync());
        applyGrid();
        map.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent e) { updateCoords(); return false; }
            @Override public boolean onZoom(ZoomEvent e) { updateCoords(); return false; }
        });
        applyStyle(LbsCore.prefs(this).getInt("mapStyle", STYLE_SAT));
        buildChips();

        if (savedInstanceState != null) selected = savedInstanceState.getInt("sel", 0);
        map.addOnFirstLayoutListener((v, l, t, r, b) -> zoomTo(selected, false));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------- Компас ----------

    /** Невидимый слой: при каждой перерисовке поворачивает стрелку компаса вслед за картой. */
    private final class CompassSync extends Overlay {
        private float last = Float.NaN;

        @Override
        public void draw(Canvas c, MapView mv, boolean shadow) {
            if (shadow) return;
            float o = mv.getMapOrientation();
            if (o != last) {
                last = o;
                View needle = findViewById(R.id.compassNeedle);
                needle.setRotation(o);
            }
        }
    }

    private void resetNorth() {
        if (map.getMapOrientation() == 0f) {
            Toast.makeText(this, "Поворачивайте карту двумя пальцами", Toast.LENGTH_SHORT).show();
            return;
        }
        map.getController().animateTo(map.getMapCenter(), map.getZoomLevelDouble(), 400L, 0f);
    }

    // ---------- Слои линии ----------

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
        if (diff) for (double[][] l : d.removed) map.getOverlays().add(line(l, Color.rgb(140, 140, 140), 3));
        for (double[][] l : d.lines) map.getOverlays().add(line(l, Color.rgb(220, 0, 0), 3.5f));
        if (diff) for (double[][] l : d.added) map.getOverlays().add(line(l, Color.rgb(255, 210, 0), 5));
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

    // ---------- Направления ----------

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
            b.setElevation(dp(3));
            int ph = (int) dp(14), pv = (int) dp(8);
            b.setPadding(ph, pv, ph, pv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) dp(8));
            lp.topMargin = (int) dp(2);
            lp.bottomMargin = (int) dp(6);
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
    private static final OnlineTileSourceBase CARTO_NOLABELS = new XYTileSource("CartoVoyagerNoLabels",
            0, 19, 256, ".png", new String[]{
            "https://a.basemaps.cartocdn.com/rastertiles/voyager_nolabels/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager_nolabels/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager_nolabels/"});

    private TilesOverlay labels;
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
        // спутник — без растягивания под плотность экрана (иначе размыто)
        map.setTilesScaledToDpi(style != STYLE_SAT);
        if (style == STYLE_SCHEME) {
            map.setTileSource(ru ? CARTO_NOLABELS : TileSourceFactory.MAPNIK);
            attr.setText(ru ? "© OpenStreetMap, © CARTO" : "© OpenStreetMap");
        } else if (style == STYLE_TOPO) {
            map.setTileSource(TOPO);
            attr.setText("Esri, HERE, Garmin, USGS, © OpenStreetMap");
        } else {
            map.setTileSource(SAT);
            if (!ru) {
                MapTileProviderBasic prov = new MapTileProviderBasic(getApplicationContext(), LABELS);
                labels = new TilesOverlay(prov, getApplicationContext());
                labels.setLoadingBackgroundColor(Color.TRANSPARENT);
                labels.setLoadingLineColor(Color.TRANSPARENT);
                map.getOverlays().add(0, labels);
            }
            attr.setText("Esri, Maxar, Earthstar Geographics");
        }
        if (placesOverlay != null) placesOverlay.setEnabled(ru);
        updateCoords();
        map.invalidate();
    }

    private void chooseStyle() {
        new AlertDialog.Builder(this)
                .setTitle("Подложка карты")
                .setSingleChoiceItems(STYLE_NAMES, currentStyle, (d, which) -> {
                    applyStyle(which);
                    d.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ---------- Информация: даты и легенда ----------

    private void showInfo() {
        final String head = "ЛБС (DeepState): " + lbsDate + "\n";
        final String legend = "\n━  ЛБС — красная линия\n"
                + (LbsCore.optDiff(this) ? "━  Изменения — жёлтая (новое), серая (прежнее)\n" : "")
                + (LbsCore.optGray(this) ? "▮  Серая зона — статус неизвестен\n" : "")
                + "\nПоворот: двумя пальцами. Компас — вернуть на север.";
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("О карте")
                .setMessage(head + (currentStyle == STYLE_SAT ? "Спутник: загружаю дату снимка…\n" : "") + legend)
                .setPositiveButton("OK", null)
                .show();
        if (currentStyle != STYLE_SAT) return;
        queryImageryDate(text -> {
            if (dlg.isShowing()) dlg.setMessage(head + text + "\n" + legend);
        });
    }

    private interface TextCallback {
        void on(String text);
    }

    /** Дата и разрешение спутникового снимка Esri в центре экрана. */
    private void queryImageryDate(TextCallback cb) {
        if (map.getZoomLevelDouble() < 11) {
            cb.on("Спутник: приблизьте карту, чтобы узнать дату снимка");
            return;
        }
        final GeoPoint c = (GeoPoint) map.getMapCenter();
        final BoundingBox bb = map.getBoundingBox();
        final int w = Math.max(1, map.getWidth()), h = Math.max(1, map.getHeight());
        new Thread(() -> {
            String text = null;
            try {
                String url = ESRI + "World_Imagery/MapServer/identify?geometryType=esriGeometryPoint&sr=4326"
                        + "&layers=top&tolerance=1&returnGeometry=false&f=json"
                        + "&geometry=" + c.getLongitude() + "," + c.getLatitude()
                        + "&mapExtent=" + bb.getLonWest() + "," + bb.getLatSouth() + "," + bb.getLonEast() + "," + bb.getLatNorth()
                        + "&imageDisplay=" + w + "," + h + ",96";
                JSONArray res = new JSONObject(httpGet(url)).optJSONArray("results");
                if (res != null) {
                    for (int i = 0; i < res.length() && text == null; i++) {
                        JSONObject a = res.getJSONObject(i).optJSONObject("attributes");
                        if (a == null) continue;
                        String d = a.optString("DATE (YYYYMMDD)", "");
                        if (d.length() != 8) continue;
                        StringBuilder sb = new StringBuilder("Спутник (центр карты): ")
                                .append(d.substring(6, 8)).append('.').append(d.substring(4, 6)).append('.').append(d.substring(0, 4));
                        try {
                            double m = Double.parseDouble(a.optString("RESOLUTION (M)", ""));
                            sb.append(" · ").append(m < 1 ? Math.round(m * 100) + " см" : (Math.round(m * 10) / 10.0) + " м");
                        } catch (NumberFormatException ignored) {
                        }
                        String src = a.optString("SOURCE", "");
                        if (!src.isEmpty()) sb.append(" · ").append(src);
                        text = sb.toString();
                    }
                }
                if (text == null) text = "Спутник: дата снимка неизвестна";
            } catch (Exception e) {
                text = "Спутник: нет связи, дата неизвестна";
            }
            final String out = text;
            runOnUiThread(() -> {
                if (!isFinishing()) cb.on(out);
            });
        }).start();
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", getPackageName() + " (https://github.com/TRO9II/lbs-kml)");
        con.setConnectTimeout(12000);
        con.setReadTimeout(20000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            return bo.toString("UTF-8");
        }
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
                    if (searchBox.getVisibility() == View.VISIBLE && searchBox.getText().length() > 0) runLocalSearch();
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
    private Thread onlineSearch;

    private void setupSearch() {
        searchBox = findViewById(R.id.searchBox);
        results = findViewById(R.id.results);
        resultsScroll = findViewById(R.id.resultsScroll);
        findViewById(R.id.searchHint).setOnClickListener(v -> openSearch(true));
        findViewById(R.id.btnSearchClose).setOnClickListener(v -> openSearch(false));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { runLocalSearch(); }
        });
        searchBox.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String q = searchBox.getText().toString().trim();
                List<Integer> hit = places != null ? places.search(q, 1) : new ArrayList<>();
                if (!hit.isEmpty()) showPlace(hit.get(0));
                else if (!q.isEmpty()) searchOnline(q);
                return true;
            }
            return false;
        });
    }

    private boolean searchOpen() {
        return searchBox.getVisibility() == View.VISIBLE;
    }

    private void openSearch(boolean open) {
        findViewById(R.id.searchHint).setVisibility(open ? View.GONE : View.VISIBLE);
        searchBox.setVisibility(open ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnSearchClose).setVisibility(open ? View.VISIBLE : View.GONE);
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
        for (int i : places.search(q, 30)) {
            final int idx = i;
            addResultRow(places.name[i], places.subtitle(i), v -> showPlace(idx));
        }
        final String query = q;
        addResultRow("🌐  Искать «" + q + "» в интернете", "OpenStreetMap Nominatim", v -> searchOnline(query));
    }

    private void addResultRow(String title, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = (int) dp(18), pv = (int) dp(11);
        row.setPadding(ph, pv, ph, pv);
        if (click != null) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
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

    private void showPlace(int i) {
        goTo(places.lat[i], places.lon[i], places.name[i], places.subtitle(i), places.rank[i] <= 1 ? 12.5 : 14.0);
    }

    private void goTo(double lat, double lon, String title, String sub, double zoom) {
        openSearch(false);
        UserPoints.Point p = new UserPoints.Point();
        p.id = "";
        p.name = title;
        p.note = sub;
        p.color = 0xFF1E88E5;
        p.lat = lat;
        p.lon = lon;
        pointsOverlay.setExtra(p);
        map.getController().animateTo(new GeoPoint(lat, lon), zoom, 900L);
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
                JSONArray arr = new JSONArray(httpGet("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=15"
                        + "&accept-language=ru&countrycodes=ua,ru&q=" + URLEncoder.encode(q, "UTF-8")));
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject o = arr.getJSONObject(j);
                    String disp = o.optString("display_name");
                    int comma = disp.indexOf(',');
                    out.add(new String[]{comma > 0 ? disp.substring(0, comma) : disp,
                            comma > 0 ? disp.substring(comma + 1).trim() : "",
                            o.optString("lat"), o.optString("lon")});
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
        final String[] items = new String[zMaxes.length];
        final int[] counts = new int[zMaxes.length];
        for (int i = 0; i < zMaxes.length; i++) {
            counts[i] = cm.possibleTilesInArea(bb, zMin, zMaxes[i]);
            String detail = zMaxes[i] <= 15 ? "обзор" : zMaxes[i] == 16 ? "сёла" : zMaxes[i] == 17 ? "улицы" : "максимум, дома";
            items[i] = "До уровня " + zMaxes[i] + " (" + detail + ") — " + mb((double) counts[i] * tileBytes())
                    + (counts[i] > MAX_TILES ? " · слишком много, приблизьте" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Скачать видимую область")
                .setItems(items, (d, which) -> {
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

    // ---------- Сетка СК-42, круг-компас, плашки ----------

    private GridOverlay gridOverlay;
    private ReticleOverlay reticleOverlay;

    private boolean gridOn() {
        return LbsCore.prefs(this).getBoolean("gridOn", false);
    }

    private void toggleGrid() {
        boolean on = !gridOn();
        LbsCore.prefs(this).edit().putBoolean("gridOn", on).apply();
        applyGrid();
        Toast.makeText(this, on ? "Сетка СК-42 включена" : "Сетка СК-42 выключена", Toast.LENGTH_SHORT).show();
    }

    private void applyGrid() {
        boolean on = gridOn();
        gridOverlay.setEnabled(on);
        reticleOverlay.setReticle(on);
        findViewById(R.id.coordCard).setVisibility(on ? View.VISIBLE : View.GONE);
        findViewById(R.id.scaleCard).setVisibility(on ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.btnGrid)).setTextColor(on ? getColor(R.color.accent) : getColor(R.color.text));
        updateCoords();
        map.invalidate();
    }

    private static final double[] BARS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000};

    /** Обновляет плашки: координаты центра, масштаб, цель. */
    private void updateCoords() {
        if (reticleOverlay == null) return;
        GeoPoint c = (GeoPoint) map.getMapCenter();
        double lat = c.getLatitude(), lon = c.getLongitude();
        if (gridOn()) {
            double[] r = Sk42.fromWgs(lat, lon);
            ((TextView) findViewById(R.id.coordSk)).setText(
                    "X = " + Math.round(r[0]) + "   Y = " + Math.round(r[1]));
            ((TextView) findViewById(R.id.coordWgs)).setText(String.format(Locale.US, "%.6f° %s  %.6f° %s",
                    Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W"));

            // метров на пиксель: точка в 1 км к северу
            org.osmdroid.views.Projection pj = map.getProjection();
            android.graphics.Point p0 = pj.toPixels(c, null);
            android.graphics.Point p1 = pj.toPixels(new GeoPoint(lat + 1000.0 / 111132.0, lon), null);
            double pxPerM = Math.hypot(p1.x - p0.x, p1.y - p0.y) / 1000.0;
            if (pxPerM > 0) {
                double xdpi = getResources().getDisplayMetrics().xdpi;
                long scale = Math.round((1 / pxPerM) * xdpi / 0.0254 / 100.0) * 100;
                ((TextView) findViewById(R.id.scaleText)).setText(
                        STYLE_SHORT[currentStyle] + "   1:" + Geo.group(scale));
                double bar = BARS[0];
                for (double m : BARS) if (m * pxPerM <= dp(110)) bar = m;
                ((ScaleBarView) findViewById(R.id.scaleBar)).setBarPx((float) (bar * pxPerM));
                ((TextView) findViewById(R.id.scaleBarText)).setText(Geo.dist(bar));
            }
            int maxZ = (int) map.getTileProvider().getTileSource().getMaximumZoomLevel();
            ((TextView) findViewById(R.id.zoomText)).setText(Math.round(map.getZoomLevelDouble()) + "/" + maxZ);
        }
        updateTargetCard(lat, lon);
    }

    private static final String[] STYLE_SHORT = {"Схема", "Esri Спутник", "Esri Топо"};

    // ---------- Цель (навигация к точке) ----------

    private boolean hasTarget() {
        return LbsCore.prefs(this).contains("targetLat");
    }

    private void setTarget(double lat, double lon, String name) {
        LbsCore.prefs(this).edit()
                .putString("targetLat", Double.toString(lat))
                .putString("targetLon", Double.toString(lon))
                .putString("targetName", name)
                .apply();
        reticleOverlay.setTarget(lat, lon);
        updateCoords();
        map.invalidate();
        Toast.makeText(this, "Цель: " + name, Toast.LENGTH_SHORT).show();
    }

    private void clearTarget() {
        LbsCore.prefs(this).edit().remove("targetLat").remove("targetLon").remove("targetName").apply();
        reticleOverlay.clearTarget();
        updateCoords();
        map.invalidate();
    }

    private void restoreTarget() {
        if (!hasTarget()) return;
        try {
            reticleOverlay.setTarget(Double.parseDouble(LbsCore.prefs(this).getString("targetLat", "0")),
                    Double.parseDouble(LbsCore.prefs(this).getString("targetLon", "0")));
        } catch (NumberFormatException e) {
            clearTarget();
        }
    }

    private void updateTargetCard(double lat, double lon) {
        View card = findViewById(R.id.targetCard);
        if (!hasTarget()) {
            card.setVisibility(View.GONE);
            return;
        }
        double tLat, tLon;
        try {
            tLat = Double.parseDouble(LbsCore.prefs(this).getString("targetLat", "0"));
            tLon = Double.parseDouble(LbsCore.prefs(this).getString("targetLon", "0"));
        } catch (NumberFormatException e) {
            card.setVisibility(View.GONE);
            return;
        }
        double[] inv = Geo.inverse(lat, lon, tLat, tLon);
        double grid = Geo.gridBearing(lat, lon, tLat, tLon);
        card.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.targetMain)).setText(
                "→ " + Geo.dist(inv[0]) + "   " + String.format(Locale.US, "%.1f°", inv[1]));
        ((TextView) findViewById(R.id.targetSub)).setText(
                LbsCore.prefs(this).getString("targetName", "") + " · дир. угол " + Geo.mils(grid));
    }

    private void copyCenterCoords() {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        copy("СК-42: " + Sk42.format(c.getLatitude(), c.getLongitude()) + "\nWGS-84: "
                + String.format(Locale.US, "%.6f, %.6f", c.getLatitude(), c.getLongitude()));
    }

    private void copy(String text) {
        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("coords", text));
        Toast.makeText(this, "Координаты скопированы", Toast.LENGTH_SHORT).show();
    }

    // ---------- Свои точки ----------

    private PointsOverlay pointsOverlay;
    private List<UserPoints.Point> userPoints = new ArrayList<>();

    private void savePoints() {
        try {
            UserPoints.save(this, userPoints);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось сохранить точки", Toast.LENGTH_LONG).show();
        }
        pointsOverlay.setPoints(userPoints);
        map.invalidate();
    }

    private void onPointTap(UserPoints.Point p) {
        if (p == pointsOverlay.getExtra()) {
            // результат поиска: предложить сохранить
            new AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setMessage((p.note == null ? "" : p.note + "\n\n") + coordsText(p.lat, p.lon))
                    .setPositiveButton("Сохранить как точку", (d, w) -> {
                        pointsOverlay.setExtra(null);
                        editPoint(null, p.lat, p.lon, p.name);
                    })
                    .setNeutralButton("⚑ Навести", (d, w) -> setTarget(p.lat, p.lon, p.name))
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        editPoint(p, p.lat, p.lon);
    }

    private static String coordsText(double lat, double lon) {
        return "СК-42: " + Sk42.format(lat, lon) + "\nWGS-84: " + String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    private void editPoint(UserPoints.Point existing, double lat, double lon) {
        editPoint(existing, lat, lon, null);
    }

    /** Создание/редактирование точки: название, цвет, заметка. */
    private void editPoint(UserPoints.Point existing, double lat, double lon, String presetName) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(20);
        box.setPadding(pad, (int) dp(8), pad, 0);

        EditText name = new EditText(this);
        name.setHint("Название");
        name.setSingleLine(true);
        name.setText(existing != null ? existing.name : presetName != null ? presetName : "Точка " + (userPoints.size() + 1));
        name.setSelectAllOnFocus(true);
        box.addView(name);

        EditText note = new EditText(this);
        note.setHint("Заметка (необязательно)");
        note.setText(existing != null ? existing.note : "");
        box.addView(note);

        TextView colorTitle = new TextView(this);
        colorTitle.setText("Цвет");
        colorTitle.setTextColor(getColor(R.color.text2));
        colorTitle.setPadding(0, (int) dp(12), 0, (int) dp(6));
        box.addView(colorTitle);

        final int[] chosen = {existing != null ? existing.color : UserPoints.COLORS[0]};
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        final List<View> swatches = new ArrayList<>();
        int sz = (int) dp(30);
        for (int i = 0; i < UserPoints.COLORS.length; i++) {
            final int col = UserPoints.COLORS[i];
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMarginEnd((int) dp(7));
            sw.setLayoutParams(lp);
            sw.setContentDescription(UserPoints.COLOR_NAMES[i]);
            sw.setOnClickListener(v -> {
                chosen[0] = col;
                for (int k = 0; k < swatches.size(); k++)
                    swatches.get(k).setBackground(swatch(UserPoints.COLORS[k], UserPoints.COLORS[k] == col));
            });
            sw.setBackground(swatch(col, col == chosen[0]));
            swatches.add(sw);
            colors.addView(sw);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(colors);
        box.addView(hs);

        TextView coords = new TextView(this);
        coords.setText(coordsText(lat, lon));
        coords.setTextColor(getColor(R.color.text2));
        coords.setTextSize(13);
        coords.setPadding(0, (int) dp(14), 0, 0);
        coords.setTextIsSelectable(true);
        box.addView(coords);

        final AlertDialog[] dlgRef = new AlertDialog[1];
        Button nav = new Button(this);
        nav.setText("⚑  Навести сюда (расстояние и азимут)");
        nav.setAllCaps(false);
        nav.setOnClickListener(v -> {
            String nm = name.getText().toString().trim();
            setTarget(lat, lon, nm.isEmpty() ? "Точка" : nm);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = (int) dp(10);
        box.addView(nav, nlp);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Новая точка" : "Точка")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    UserPoints.Point p = existing;
                    if (p == null) {
                        p = new UserPoints.Point();
                        p.id = Long.toString(System.currentTimeMillis(), 36);
                        p.created = System.currentTimeMillis();
                        p.lat = lat;
                        p.lon = lon;
                        userPoints.add(p);
                    }
                    String nm = name.getText().toString().trim();
                    p.name = nm.isEmpty() ? "Точка" : nm;
                    p.note = note.getText().toString().trim();
                    p.color = chosen[0];
                    savePoints();
                })
                .setNegativeButton("Отмена", null);
        if (existing != null) {
            b.setNeutralButton("Удалить", (d, w) -> new AlertDialog.Builder(this)
                    .setTitle("Удалить «" + existing.name + "»?")
                    .setPositiveButton("Удалить", (d2, w2) -> {
                        userPoints.remove(existing);
                        savePoints();
                    })
                    .setNegativeButton("Отмена", null)
                    .show());
        }
        dlgRef[0] = b.show();
    }

    private android.graphics.drawable.GradientDrawable swatch(int color, boolean selected) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke((int) dp(selected ? 3 : 1), selected ? getColor(R.color.text) : getColor(R.color.divider));
        return g;
    }

    private void showPointsList() {
        if (userPoints.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Мои точки")
                    .setMessage("Пока нет точек.\n\nЧтобы поставить точку, нажмите и подержите палец на карте.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        final List<UserPoints.Point> sorted = new ArrayList<>(userPoints);
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(b.created, a.created));
        CharSequence[] items = new CharSequence[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            UserPoints.Point p = sorted.get(i);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("● ");
            sb.setSpan(new android.text.style.ForegroundColorSpan(p.color), 0, 1, 0);
            sb.append(p.name).append("\n");
            int st = sb.length();
            sb.append(Sk42.format(p.lat, p.lon));
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), st, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text2)), st, sb.length(), 0);
            items[i] = sb;
        }
        new AlertDialog.Builder(this)
                .setTitle("Мои точки (" + sorted.size() + ")")
                .setItems(items, (d, which) -> {
                    UserPoints.Point p = sorted.get(which);
                    map.getController().animateTo(new GeoPoint(p.lat, p.lon), Math.max(13.0, map.getZoomLevelDouble()), 800L);
                })
                .setPositiveButton("Экспорт в KML", (d, w) -> exportPoints())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void exportPoints() {
        new Thread(() -> {
            String msg;
            try {
                String name = "Tochki_" + new java.text.SimpleDateFormat("dd_MM_yyyy", Locale.US).format(new java.util.Date()) + ".kml";
                android.net.Uri uri = LbsCore.saveToDownloads(getApplicationContext(), name,
                        UserPoints.toKml(userPoints).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    Toast.makeText(this, name + " сохранён в «Загрузки»", Toast.LENGTH_LONG).show();
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    i.setType(LbsCore.KML_MIME);
                    i.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                    i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(i, "Отправить точки"));
                });
                return;
            } catch (Exception e) {
                msg = "Не удалось сохранить файл: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    // ---------- Отступы под системные панели ----------

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
        if (searchOpen()) {
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
        if (dlTask != null) dlTask.cancel(true);
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
