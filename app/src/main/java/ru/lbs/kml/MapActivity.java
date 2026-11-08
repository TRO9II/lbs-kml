package ru.lbs.kml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
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
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.FolderOverlay;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Главный экран: карта со всеми инструментами. ЛБС — в меню. */
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
    private SharedPreferences prefs;
    private final List<Button> chips = new ArrayList<>();
    private int selected = 0;
    private String lbsDate = "";
    private long lbsLoadedAt = -1;

    private final FolderOverlay lbsFolder = new FolderOverlay();
    private GridOverlay gridOverlay;
    private ReticleOverlay reticleOverlay;
    private PlacesOverlay placesOverlay;
    private PointsOverlay pointsOverlay;
    private PathOverlay pathOverlay;
    private Places places;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = LbsCore.prefs(this);

        IConfigurationProvider conf = Configuration.getInstance();
        conf.load(this, prefs);
        conf.setUserAgentValue(getPackageName());
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

        float d = getResources().getDisplayMetrics().density;
        // слои — снизу вверх
        map.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) { return onMapTap(p); }
            @Override public boolean longPressHelper(GeoPoint p) { return onMapLongPress(p); }
        }));
        RotationGestureOverlay rot = new RotationGestureOverlay(map);
        rot.setEnabled(true);
        map.getOverlays().add(rot);
        map.getOverlays().add(lbsFolder);
        gridOverlay = new GridOverlay(d);
        map.getOverlays().add(gridOverlay);
        placesOverlay = new PlacesOverlay(d);
        map.getOverlays().add(placesOverlay);
        pathOverlay = new PathOverlay(d);
        map.getOverlays().add(pathOverlay);
        pointsOverlay = new PointsOverlay(d);
        map.getOverlays().add(pointsOverlay);
        reticleOverlay = new ReticleOverlay(d);
        map.getOverlays().add(reticleOverlay);
        map.getOverlays().add(new CompassSync());

        userPoints = UserPoints.load(this);
        pointsOverlay.setPoints(userPoints);
        pointsOverlay.setListener(this::onPointTap);
        routes = Routes.load(this);
        pathOverlay.setSaved(routes);
        restoreTarget();

        wireUi();
        reloadLbs();
        loadPlaces();
        buildChips();
        applyStyle(prefs.getInt("mapStyle", STYLE_SAT));
        applyGrid();
        map.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent e) { updateCoords(); return false; }
            @Override public boolean onZoom(ZoomEvent e) { updateCoords(); return false; }
        });

        if (savedInstanceState != null) selected = savedInstanceState.getInt("sel", 0);
        final boolean restoreView = prefs.contains("viewLat");
        map.addOnFirstLayoutListener((v, l, t, r, b) -> {
            if (savedInstanceState == null && restoreView) {
                map.getController().setZoom((double) prefs.getFloat("viewZoom", 8f));
                map.getController().setCenter(new GeoPoint(
                        Double.parseDouble(prefs.getString("viewLat", "48.5")),
                        Double.parseDouble(prefs.getString("viewLon", "36.5"))));
                selected = -1;
                styleChips();
            } else {
                zoomTo(Math.max(0, selected), false);
            }
            updateCoords();
        });

        // автопроверка ЛБС и разрешение на уведомления
        try {
            boolean auto = LbsCore.autoEnabled(this);
            LbsCore.schedule(this, auto);
            if (auto && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void wireUi() {
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> map.getController().zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> map.getController().zoomOut());
        findViewById(R.id.btnCompass).setOnClickListener(v -> resetNorth());
        findViewById(R.id.btnGrid).setOnClickListener(v -> toggleGrid());
        findViewById(R.id.dlCancel).setOnClickListener(v -> cancelDownload());
        findViewById(R.id.coordCard).setOnClickListener(v -> copyCenterCoords());
        findViewById(R.id.targetClear).setOnClickListener(v -> clearTarget());
        findViewById(R.id.lbsBannerBtn).setOnClickListener(v -> openLbsScreen());

        findViewById(R.id.dockLayers).setOnClickListener(v -> showLayers());
        findViewById(R.id.dockRuler).setOnClickListener(v -> startTool(TOOL_RULER, null));
        findViewById(R.id.dockRoute).setOnClickListener(v -> showRoutes());
        findViewById(R.id.dockPoints).setOnClickListener(v -> showPointsList());
        findViewById(R.id.dockMenu).setOnClickListener(v -> showMenu());

        findViewById(R.id.toolUndo).setOnClickListener(v -> toolUndo());
        findViewById(R.id.toolClear).setOnClickListener(v -> toolClear());
        findViewById(R.id.toolTable).setOnClickListener(v -> toolTable());
        findViewById(R.id.toolCenter).setOnClickListener(v -> {
            GeoPoint c = (GeoPoint) map.getMapCenter();
            toolAdd(c.getLatitude(), c.getLongitude());
        });
        findViewById(R.id.toolSave).setOnClickListener(v -> toolSave());
        findViewById(R.id.toolDone).setOnClickListener(v -> stopTool());
        setupSearch();
    }

    // ================= ЛБС =================

    private void openLbsScreen() {
        startActivity(new Intent(this, MainActivity.class));
    }

    /** Загружает (или перезагружает) слои ЛБС из последнего файла. */
    private void reloadLbs() {
        File f = LbsCore.mapFile(this);
        long mod = f.exists() ? f.lastModified() : 0;
        if (mod == lbsLoadedAt) return;
        lbsLoadedAt = mod;
        lbsFolder.getItems().clear();
        KmlBuilder.Parsed data = null;
        try {
            data = LbsCore.loadMapData(this);
        } catch (Exception ignored) {
        }
        View banner = findViewById(R.id.lbsBanner);
        if (data == null || data.lines.isEmpty()) {
            lbsDate = "";
            banner.setVisibility(View.VISIBLE);
            map.invalidate();
            return;
        }
        banner.setVisibility(View.GONE);
        lbsDate = data.date.replace(" o ", ", ");
        boolean gray = LbsCore.optGray(this), diff = LbsCore.optDiff(this);
        if (gray && prefs.getBoolean("showGray", true)) {
            for (double[][] g : data.gray) {
                Polygon pg = new Polygon();
                pg.setPoints(pts(g));
                pg.getFillPaint().setColor(Color.argb(90, 120, 120, 120));
                pg.getOutlinePaint().setColor(Color.argb(200, 110, 110, 110));
                pg.getOutlinePaint().setStrokeWidth(dp(1));
                lbsFolder.add(pg);
            }
        }
        boolean showDiff = diff && prefs.getBoolean("showDiff", true);
        if (showDiff) for (double[][] l : data.removed) lbsFolder.add(line(l, Color.rgb(140, 140, 140), 3));
        if (prefs.getBoolean("showLbs", true))
            for (double[][] l : data.lines) lbsFolder.add(line(l, Color.rgb(230, 20, 20), 3.5f));
        if (showDiff) for (double[][] l : data.added) lbsFolder.add(line(l, Color.rgb(255, 210, 0), 5));
        map.invalidate();
    }

    private void forceReloadLbs() {
        lbsLoadedAt = -2;
        reloadLbs();
    }

    private static List<GeoPoint> pts(double[][] l) {
        List<GeoPoint> out = new ArrayList<>(l.length);
        for (double[] p : l) out.add(new GeoPoint(p[1], p[0]));
        return out;
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

    // ================= Компас =================

    private final class CompassSync extends Overlay {
        private float last = Float.NaN;

        @Override
        public void draw(Canvas c, MapView mv, boolean shadow) {
            if (shadow) return;
            float o = mv.getMapOrientation();
            if (o != last) {
                last = o;
                findViewById(R.id.compassNeedle).setRotation(o);
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

    // ================= Направления =================

    private void buildChips() {
        LinearLayout row = findViewById(R.id.chips);
        for (int i = 0; i < NAMES.length; i++) {
            Button b = new Button(this);
            b.setText(NAMES[i]);
            b.setAllCaps(false);
            b.setTextSize(13.5f);
            b.setStateListAnimator(null);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setElevation(dp(3));
            int ph = (int) dp(14), pv = (int) dp(7);
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
        findViewById(R.id.chipsScroll).setVisibility(prefs.getBoolean("showChips", true) ? View.VISIBLE : View.GONE);
    }

    private void styleChips() {
        for (int i = 0; i < chips.size(); i++) {
            Button b = chips.get(i);
            boolean on = i == selected;
            b.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            b.setTextColor(on ? Color.WHITE : getColor(R.color.hud_text));
        }
    }

    private void zoomTo(int i, boolean animated) {
        selected = i;
        styleChips();
        double[] b = BOX[i];
        map.zoomToBoundingBox(new BoundingBox(b[0], b[1], b[2], b[3]), animated, (int) dp(24));
    }

    // ================= Подложка =================

    private static final int STYLE_SCHEME = 0, STYLE_SAT = 1, STYLE_TOPO = 2;
    private static final String[] STYLE_NAMES = {"Схема", "Спутник (Esri)", "Топокарта (Esri)"};
    private static final String[] STYLE_SHORT = {"Схема", "Esri Спутник", "Esri Топо"};
    private static final String ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/";

    /** Тайлы Esri: порядок в адресе z/y/x. */
    private static final class EsriSource extends OnlineTileSourceBase {
        EsriSource(String name, String service, String ext) {
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

    private TilesOverlay esriLabels;
    private int currentStyle = STYLE_SAT;

    private boolean labelsOn() {
        return prefs.getBoolean("labelsRu", true);
    }

    private void applyStyle(int style) {
        if (style < 0 || style > 2) style = STYLE_SAT;
        currentStyle = style;
        prefs.edit().putInt("mapStyle", style).apply();
        TextView attr = findViewById(R.id.attribution);
        if (esriLabels != null) {
            map.getOverlays().remove(esriLabels);
            esriLabels.onDetach(map);
            esriLabels = null;
        }
        boolean ru = labelsOn();
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
                esriLabels = new TilesOverlay(prov, getApplicationContext());
                esriLabels.setLoadingBackgroundColor(Color.TRANSPARENT);
                esriLabels.setLoadingLineColor(Color.TRANSPARENT);
                map.getOverlays().add(2, esriLabels);
            }
            attr.setText("Esri, Maxar, Earthstar Geographics");
        }
        placesOverlay.setEnabled(ru);
        updateCoords();
        map.invalidate();
    }

    // ================= Окно «Слои» =================

    private void showLayers() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(22);
        box.setPadding(pad, (int) dp(6), pad, (int) dp(4));

        box.addView(sectionTitle("ПОДЛОЖКА"));
        LinearLayout styles = new LinearLayout(this);
        styles.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> styleBtns = new ArrayList<>();
        String[] st = {"Спутник", "Топо", "Схема"};
        int[] stIdx = {STYLE_SAT, STYLE_TOPO, STYLE_SCHEME};
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setText(st[i]);
            t.setGravity(android.view.Gravity.CENTER);
            t.setTextSize(14);
            int ph = (int) dp(10);
            t.setPadding(ph, (int) dp(10), ph, (int) dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd(i < 2 ? (int) dp(8) : 0);
            final int s = stIdx[i];
            t.setOnClickListener(v -> {
                applyStyle(s);
                for (int k = 0; k < styleBtns.size(); k++) styleSeg(styleBtns.get(k), stIdx[k] == currentStyle);
            });
            styleBtns.add(t);
            styles.addView(t, lp);
            styleSeg(t, s == currentStyle);
        }
        box.addView(styles);

        box.addView(sectionTitle("НА КАРТЕ"));
        box.addView(toggleRow("Подписи на русском", "Названия населённых пунктов", labelsOn(), on -> {
            prefs.edit().putBoolean("labelsRu", on).apply();
            applyStyle(currentStyle);
        }));
        box.addView(toggleRow("Сетка СК-42", "Километровая сетка Гаусса-Крюгера, круг-компас", gridOn(), on -> {
            prefs.edit().putBoolean("gridOn", on).apply();
            applyGrid();
        }));
        box.addView(toggleRow("Кнопки направлений", "Херсонская, Запорожская…", prefs.getBoolean("showChips", true), on -> {
            prefs.edit().putBoolean("showChips", on).apply();
            findViewById(R.id.chipsScroll).setVisibility(on ? View.VISIBLE : View.GONE);
        }));

        box.addView(sectionTitle("ЛБС"));
        box.addView(toggleRow("Линия фронта", lbsDate.isEmpty() ? "не загружена" : "снимок " + lbsDate,
                prefs.getBoolean("showLbs", true), on -> {
                    prefs.edit().putBoolean("showLbs", on).apply();
                    forceReloadLbs();
                }));
        box.addView(toggleRow("Изменения", "Жёлтым — новое положение, серым — прежнее",
                prefs.getBoolean("showDiff", true), on -> {
                    prefs.edit().putBoolean("showDiff", on).apply();
                    forceReloadLbs();
                }));
        box.addView(toggleRow("Серая зона", "Статус неизвестен", prefs.getBoolean("showGray", true), on -> {
            prefs.edit().putBoolean("showGray", on).apply();
            forceReloadLbs();
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Слои").setView(sv).setPositiveButton("Готово", null).show();
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTextColor(getColor(R.color.text2));
        t.setPadding(0, (int) dp(16), 0, (int) dp(8));
        return t;
    }

    private void styleSeg(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(on ? getColor(R.color.accent) : Color.TRANSPARENT);
        g.setStroke((int) dp(1.2f), on ? getColor(R.color.accent) : getColor(R.color.divider));
        t.setBackground(g);
        t.setTextColor(on ? Color.WHITE : getColor(R.color.text));
    }

    private interface Toggle {
        void on(boolean on);
    }

    private View toggleRow(String title, String sub, boolean value, Toggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) dp(6), 0, (int) dp(6));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(getColor(R.color.text));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12.5f);
        s.setTextColor(getColor(R.color.text2));
        texts.addView(t);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, on) -> cb.on(on));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    // ================= Меню =================

    private void showMenu() {
        String[] items = {
                "ЛБС — обновить, файл, автопроверка",
                "Скачать видимую область (офлайн)",
                "Экспорт моих точек в KML",
                "О карте: даты снимков, легенда",
                "О приложении"};
        new AlertDialog.Builder(this)
                .setTitle("Меню")
                .setItems(items, (dlg, which) -> {
                    switch (which) {
                        case 0: openLbsScreen(); break;
                        case 1: chooseDownload(); break;
                        case 2: exportPoints(); break;
                        case 3: showInfo(); break;
                        default: showAbout();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showAbout() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("ЛБС KML  v" + ver)
                .setMessage("Карта линии фронта с сеткой СК-42, точками, линейкой и маршрутами.\n\n"
                        + "Разработчик: TRO9II\n\n"
                        + "Данные: DeepStateMap (ЛБС), OpenStreetMap (населённые пункты), "
                        + "Esri / CARTO / OpenStreetMap (подложки).")
                .setPositiveButton("OK", null)
                .show();
    }

    // ================= Информация о карте =================

    private void showInfo() {
        final String head = "ЛБС (DeepState): " + (lbsDate.isEmpty() ? "не загружена" : lbsDate) + "\n";
        final String legend = "\n━  ЛБС — красная линия\n"
                + "━  Изменения — жёлтая (новое), серая (прежнее)\n"
                + "▮  Серая зона — статус неизвестен\n"
                + "\nПоворот карты — двумя пальцами, компас — вернуть на север."
                + "\nДолгое нажатие на карте — новая точка.";
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
                        String dd = a.optString("DATE (YYYYMMDD)", "");
                        if (dd.length() != 8) continue;
                        StringBuilder sb = new StringBuilder("Спутник (центр карты): ")
                                .append(dd.substring(6, 8)).append('.').append(dd.substring(4, 6)).append('.').append(dd.substring(0, 4));
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

    // ================= Русские подписи =================

    private void loadPlaces() {
        placesOverlay.setEnabled(labelsOn());
        new Thread(() -> {
            try {
                Places p = Places.get(getApplicationContext());
                runOnUiThread(() -> {
                    places = p;
                    placesOverlay.setPlaces(p);
                    map.invalidate();
                    if (searchOpen() && searchBox.getText().length() > 0) runLocalSearch();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Список населённых пунктов не загрузился",
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ================= Поиск =================

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
        findViewById(R.id.chipsScroll).setVisibility(!show && prefs.getBoolean("showChips", true) ? View.VISIBLE : View.GONE);
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
        addResultRow("Искать «" + q + "» в интернете", "OpenStreetMap Nominatim", v -> searchOnline(query));
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
        t.setTextColor(getColor(R.color.hud_text));
        t.setTextSize(16);
        row.addView(t);
        if (!sub.isEmpty()) {
            TextView st = new TextView(this);
            st.setText(sub);
            st.setTextColor(getColor(R.color.hud_text2));
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

    // ================= Сетка, плашки, цель =================

    private boolean gridOn() {
        return prefs.getBoolean("gridOn", false);
    }

    private void toggleGrid() {
        boolean on = !gridOn();
        prefs.edit().putBoolean("gridOn", on).apply();
        applyGrid();
        Toast.makeText(this, on ? "Сетка СК-42 включена" : "Сетка СК-42 выключена", Toast.LENGTH_SHORT).show();
    }

    private void applyGrid() {
        boolean on = gridOn();
        gridOverlay.setEnabled(on);
        reticleOverlay.setReticle(on && tool == TOOL_NONE);
        findViewById(R.id.coordCard).setVisibility(on || tool != TOOL_NONE ? View.VISIBLE : View.GONE);
        findViewById(R.id.scaleCard).setVisibility(on ? View.VISIBLE : View.GONE);
        ((ImageView) findViewById(R.id.btnGrid)).setImageTintList(ColorStateList.valueOf(
                on ? getColor(R.color.hud_accent) : getColor(R.color.hud_text)));
        updateCoords();
        map.invalidate();
    }

    private static final double[] BARS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000};

    private void updateCoords() {
        if (reticleOverlay == null || map == null) return;
        GeoPoint c = (GeoPoint) map.getMapCenter();
        double lat = c.getLatitude(), lon = c.getLongitude();
        if (findViewById(R.id.coordCard).getVisibility() == View.VISIBLE) {
            double[] r = Sk42.fromWgs(lat, lon);
            ((TextView) findViewById(R.id.coordSk)).setText("X = " + Math.round(r[0]) + "   Y = " + Math.round(r[1]));
            ((TextView) findViewById(R.id.coordWgs)).setText(String.format(Locale.US, "%.6f° %s  %.6f° %s",
                    Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W"));
        }
        if (gridOn()) {
            Projection pj = map.getProjection();
            android.graphics.Point p0 = pj.toPixels(c, null);
            android.graphics.Point p1 = pj.toPixels(new GeoPoint(lat + 1000.0 / 111132.0, lon), null);
            double pxPerM = Math.hypot(p1.x - p0.x, p1.y - p0.y) / 1000.0;
            if (pxPerM > 0) {
                double xdpi = getResources().getDisplayMetrics().xdpi;
                long scale = Math.round((1 / pxPerM) * xdpi / 0.0254 / 100.0) * 100;
                ((TextView) findViewById(R.id.scaleText)).setText(STYLE_SHORT[currentStyle] + "   1:" + Geo.group(scale));
                double bar = BARS[0];
                for (double m : BARS) if (m * pxPerM <= dp(110)) bar = m;
                ((ScaleBarView) findViewById(R.id.scaleBar)).setBarPx((float) (bar * pxPerM));
                ((TextView) findViewById(R.id.scaleBarText)).setText(Geo.dist(bar));
            }
            int maxZ = (int) map.getTileProvider().getTileSource().getMaximumZoomLevel();
            ((TextView) findViewById(R.id.zoomText)).setText(Math.round(map.getZoomLevelDouble()) + "/" + maxZ);
        }
        updateTargetCard(lat, lon);
        if (tool != TOOL_NONE) updateToolPanel();
    }

    private boolean hasTarget() {
        return prefs.contains("targetLat");
    }

    private void setTarget(double lat, double lon, String name) {
        prefs.edit().putString("targetLat", Double.toString(lat)).putString("targetLon", Double.toString(lon))
                .putString("targetName", name).apply();
        reticleOverlay.setTarget(lat, lon);
        updateCoords();
        map.invalidate();
        Toast.makeText(this, "Цель: " + name, Toast.LENGTH_SHORT).show();
    }

    private void clearTarget() {
        prefs.edit().remove("targetLat").remove("targetLon").remove("targetName").apply();
        reticleOverlay.clearTarget();
        updateCoords();
        map.invalidate();
    }

    private void restoreTarget() {
        if (!hasTarget()) return;
        try {
            reticleOverlay.setTarget(Double.parseDouble(prefs.getString("targetLat", "0")),
                    Double.parseDouble(prefs.getString("targetLon", "0")));
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
            tLat = Double.parseDouble(prefs.getString("targetLat", "0"));
            tLon = Double.parseDouble(prefs.getString("targetLon", "0"));
        } catch (NumberFormatException e) {
            card.setVisibility(View.GONE);
            return;
        }
        double[] inv = Geo.inverse(lat, lon, tLat, tLon);
        card.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.targetMain)).setText(
                "→ " + Geo.dist(inv[0]) + "   " + String.format(Locale.US, "%.1f°", inv[1]));
        ((TextView) findViewById(R.id.targetSub)).setText(
                prefs.getString("targetName", "") + " · д.у. " + Geo.mils(Geo.gridBearing(lat, lon, tLat, tLon)));
    }

    private void copyCenterCoords() {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        copy("СК-42: " + Sk42.format(c.getLatitude(), c.getLongitude()) + "\nWGS-84: "
                + String.format(Locale.US, "%.6f, %.6f", c.getLatitude(), c.getLongitude()), "Координаты скопированы");
    }

    private void copy(String text, String toast) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("lbs", text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private static String coordsText(double lat, double lon) {
        return "СК-42: " + Sk42.format(lat, lon) + "\nWGS-84: " + String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    // ================= Нажатия на карту =================

    private boolean onMapTap(GeoPoint p) {
        if (tool != TOOL_NONE) {
            toolAdd(p.getLatitude(), p.getLongitude());
            return true;
        }
        return false;
    }

    private boolean onMapLongPress(GeoPoint p) {
        if (tool != TOOL_NONE) {
            toolAdd(p.getLatitude(), p.getLongitude());
            return true;
        }
        editPoint(null, p.getLatitude(), p.getLongitude(), null);
        return true;
    }

    // ================= Свои точки =================

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
        if (tool != TOOL_NONE) {
            toolAdd(p.lat, p.lon); // привязка к точке: «от точки до точки»
            return;
        }
        if (p == pointsOverlay.getExtra()) {
            new AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setMessage((p.note == null ? "" : p.note + "\n\n") + coordsText(p.lat, p.lon))
                    .setPositiveButton("Сохранить как точку", (d, w) -> {
                        pointsOverlay.setExtra(null);
                        editPoint(null, p.lat, p.lon, p.name);
                    })
                    .setNeutralButton("Навести", (d, w) -> setTarget(p.lat, p.lon, p.name))
                    .setNegativeButton("Убрать", (d, w) -> {
                        pointsOverlay.setExtra(null);
                        map.invalidate();
                    })
                    .show();
            return;
        }
        editPoint(p, p.lat, p.lon, null);
    }

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

        box.addView(sectionTitle("ЦВЕТ"));
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
        HorizontalScrollView hs = new HorizontalScrollView(this);
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
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button nav = new Button(this);
        nav.setText("Навести");
        nav.setAllCaps(false);
        nav.setOnClickListener(v -> {
            String nm = name.getText().toString().trim();
            setTarget(lat, lon, nm.isEmpty() ? "Точка" : nm);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
        });
        Button ruler = new Button(this);
        ruler.setText("Линейка отсюда");
        ruler.setAllCaps(false);
        ruler.setOnClickListener(v -> {
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            startTool(TOOL_RULER, null);
            toolAdd(lat, lon);
        });
        actions.addView(nav, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(ruler, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) dp(10);
        box.addView(actions, alp);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Новая точка" : "Точка")
                .setView(sv)
                .setPositiveButton("Сохранить", (dd, w) -> {
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
            b.setNeutralButton("Удалить", (dd, w) -> new AlertDialog.Builder(this)
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

    private GradientDrawable swatch(int color, boolean sel) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke((int) dp(sel ? 3 : 1), sel ? getColor(R.color.text) : getColor(R.color.divider));
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
        Collections.sort(sorted, (a, b) -> Long.compare(b.created, a.created));
        GeoPoint c = (GeoPoint) map.getMapCenter();
        CharSequence[] items = new CharSequence[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            UserPoints.Point p = sorted.get(i);
            double[] inv = Geo.inverse(c.getLatitude(), c.getLongitude(), p.lat, p.lon);
            SpannableStringBuilder sb = new SpannableStringBuilder("● ");
            sb.setSpan(new ForegroundColorSpan(p.color), 0, 1, 0);
            sb.append(p.name).append("\n");
            int st = sb.length();
            sb.append(Geo.dist(inv[0])).append(" от центра · ").append(Sk42.format(p.lat, p.lon));
            sb.setSpan(new RelativeSizeSpan(0.8f), st, sb.length(), 0);
            sb.setSpan(new ForegroundColorSpan(getColor(R.color.text2)), st, sb.length(), 0);
            items[i] = sb;
        }
        new AlertDialog.Builder(this)
                .setTitle("Мои точки (" + sorted.size() + ")")
                .setItems(items, (d, which) -> {
                    UserPoints.Point p = sorted.get(which);
                    map.getController().animateTo(new GeoPoint(p.lat, p.lon), Math.max(13.0, map.getZoomLevelDouble()), 800L);
                })
                .setPositiveButton("Экспорт KML", (d, w) -> exportPoints())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void exportPoints() {
        if (userPoints.isEmpty()) {
            Toast.makeText(this, "Нет точек для экспорта", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "Tochki_" + new SimpleDateFormat("dd_MM_yyyy", Locale.US).format(new Date()) + ".kml";
        saveAndShare(name, UserPoints.toKml(userPoints), "Отправить точки");
    }

    private void saveAndShare(String name, String kml, String title) {
        new Thread(() -> {
            try {
                Uri uri = LbsCore.saveToDownloads(getApplicationContext(), name, kml.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    Toast.makeText(this, name + " сохранён в «Загрузки»", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(LbsCore.KML_MIME);
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, title));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось сохранить: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ================= Линейка и маршруты =================

    private static final int TOOL_NONE = 0, TOOL_RULER = 1, TOOL_ROUTE = 2;
    private int tool = TOOL_NONE;
    private final List<double[]> toolPts = new ArrayList<>();
    private Routes.Route editingRoute;
    private List<Routes.Route> routes = new ArrayList<>();

    private void startTool(int which, Routes.Route route) {
        tool = which;
        editingRoute = route;
        toolPts.clear();
        if (route != null) {
            toolPts.addAll(route.pts);
            route.visible = false; // пока редактируем — рисуем как активную линию
        }
        pathOverlay.setActive(toolPts, which == TOOL_RULER ? getColor(R.color.hud_amber)
                : route != null ? route.color : Routes.COLORS[0]);
        findViewById(R.id.dock).setVisibility(View.GONE);
        findViewById(R.id.toolPanel).setVisibility(View.VISIBLE);
        findViewById(R.id.toolCross).setVisibility(View.VISIBLE);
        findViewById(R.id.toolSave).setVisibility(which == TOOL_ROUTE ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.toolTitle)).setText(which == TOOL_RULER ? "ЛИНЕЙКА"
                : "МАРШРУТ" + (route != null ? " · " + route.name : ""));
        applyGrid();
        updateToolPanel();
        map.invalidate();
        if (toolPts.isEmpty())
            Toast.makeText(this, "Нажимайте на карту или на свои точки, чтобы ставить вершины", Toast.LENGTH_SHORT).show();
    }

    private void stopTool() {
        if (tool == TOOL_ROUTE && editingRoute != null) editingRoute.visible = true;
        if (tool == TOOL_ROUTE && editingRoute == null && toolPts.size() > 1) {
            new AlertDialog.Builder(this)
                    .setTitle("Маршрут не сохранён")
                    .setMessage("Сохранить маршрут перед выходом?")
                    .setPositiveButton("Сохранить", (d, w) -> toolSave())
                    .setNegativeButton("Не сохранять", (d, w) -> finishTool())
                    .show();
            return;
        }
        finishTool();
    }

    private void finishTool() {
        tool = TOOL_NONE;
        editingRoute = null;
        toolPts.clear();
        pathOverlay.setActive(null, 0);
        findViewById(R.id.dock).setVisibility(View.VISIBLE);
        findViewById(R.id.toolPanel).setVisibility(View.GONE);
        findViewById(R.id.toolCross).setVisibility(View.GONE);
        applyGrid();
        map.invalidate();
    }

    private void toolAdd(double lat, double lon) {
        toolPts.add(new double[]{lat, lon});
        updateToolPanel();
        map.invalidate();
    }

    private void toolUndo() {
        if (!toolPts.isEmpty()) toolPts.remove(toolPts.size() - 1);
        updateToolPanel();
        map.invalidate();
    }

    private void toolClear() {
        toolPts.clear();
        updateToolPanel();
        map.invalidate();
    }

    private void updateToolPanel() {
        int n = toolPts.size();
        TextView total = findViewById(R.id.toolTotal), sub = findViewById(R.id.toolSub);
        if (n == 0) {
            total.setText("0 м");
            sub.setText("Нажмите на карту или «В центр»");
            return;
        }
        List<Routes.Leg> legs = Routes.legs(toolPts);
        double len = legs.isEmpty() ? 0 : legs.get(legs.size() - 1).total;
        total.setText(Geo.dist(len));
        GeoPoint c = (GeoPoint) map.getMapCenter();
        double[] last = toolPts.get(n - 1);
        double[] toC = Geo.inverse(last[0], last[1], c.getLatitude(), c.getLongitude());
        StringBuilder sb = new StringBuilder();
        if (!legs.isEmpty()) {
            Routes.Leg l = legs.get(legs.size() - 1);
            sb.append(legs.size()).append(legs.size() == 1 ? " участок" : legs.size() < 5 ? " участка" : " участков")
              .append(" · посл. ").append(Geo.dist(l.dist)).append(" ")
              .append(String.format(Locale.US, "%.1f°", l.az)).append(" (").append(Geo.mils(l.grid)).append(")\n");
        }
        sb.append("До прицела: ").append(Geo.dist(toC[0])).append(" ")
          .append(String.format(Locale.US, "%.1f°", toC[1]))
          .append(" (").append(Geo.mils(Geo.gridBearing(last[0], last[1], c.getLatitude(), c.getLongitude()))).append(")");
        sub.setText(sb);
    }

    private void toolTable() {
        if (toolPts.size() < 2) {
            Toast.makeText(this, "Нужно минимум 2 точки", Toast.LENGTH_SHORT).show();
            return;
        }
        final String table = Routes.table(toolPts);
        TextView t = new TextView(this);
        t.setText(table);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextIsSelectable(true);
        t.setTextColor(getColor(R.color.text));
        int pad = (int) dp(18);
        t.setPadding(pad, pad, pad, pad);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(t);
        ScrollView sv = new ScrollView(this);
        sv.addView(hs);
        new AlertDialog.Builder(this)
                .setTitle("Участки · " + Geo.dist(Routes.length(toolPts)))
                .setView(sv)
                .setPositiveButton("Скопировать", (d, w) -> copy(table, "Таблица скопирована"))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void toolSave() {
        if (toolPts.size() < 2) {
            Toast.makeText(this, "Нужно минимум 2 точки", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(20);
        box.setPadding(pad, (int) dp(8), pad, 0);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Название маршрута");
        name.setText(editingRoute != null ? editingRoute.name : "Маршрут " + (routes.size() + 1));
        name.setSelectAllOnFocus(true);
        box.addView(name);
        box.addView(sectionTitle("ЦВЕТ"));
        final int[] chosen = {editingRoute != null ? editingRoute.color : Routes.COLORS[0]};
        LinearLayout colors = new LinearLayout(this);
        final List<View> sw = new ArrayList<>();
        for (int i = 0; i < Routes.COLORS.length; i++) {
            final int col = Routes.COLORS[i];
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) dp(30), (int) dp(30));
            lp.setMarginEnd((int) dp(8));
            v.setLayoutParams(lp);
            v.setOnClickListener(x -> {
                chosen[0] = col;
                for (int k = 0; k < sw.size(); k++) sw.get(k).setBackground(swatch(Routes.COLORS[k], Routes.COLORS[k] == col));
            });
            v.setBackground(swatch(col, col == chosen[0]));
            sw.add(v);
            colors.addView(v);
        }
        box.addView(colors);
        new AlertDialog.Builder(this)
                .setTitle("Сохранить маршрут")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Routes.Route r = editingRoute;
                    if (r == null) {
                        r = new Routes.Route();
                        r.id = Long.toString(System.currentTimeMillis(), 36);
                        r.created = System.currentTimeMillis();
                        routes.add(r);
                    }
                    String nm = name.getText().toString().trim();
                    r.name = nm.isEmpty() ? "Маршрут" : nm;
                    r.color = chosen[0];
                    r.pts.clear();
                    r.pts.addAll(toolPts);
                    r.visible = true;
                    saveRoutes();
                    Toast.makeText(this, "Маршрут «" + r.name + "» сохранён", Toast.LENGTH_SHORT).show();
                    editingRoute = r;
                    finishTool();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void saveRoutes() {
        try {
            Routes.save(this, routes);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось сохранить маршруты", Toast.LENGTH_LONG).show();
        }
        pathOverlay.setSaved(routes);
        map.invalidate();
    }

    private void showRoutes() {
        List<CharSequence> items = new ArrayList<>();
        items.add("＋  Новый маршрут");
        for (Routes.Route r : routes) {
            SpannableStringBuilder sb = new SpannableStringBuilder("━ ");
            sb.setSpan(new ForegroundColorSpan(r.color), 0, 1, 0);
            sb.append(r.name).append(r.visible ? "" : "  (скрыт)").append("\n");
            int st = sb.length();
            sb.append(Geo.dist(Routes.length(r.pts))).append(" · ").append(Integer.toString(Math.max(0, r.pts.size() - 1)))
              .append(" уч.");
            sb.setSpan(new RelativeSizeSpan(0.8f), st, sb.length(), 0);
            sb.setSpan(new ForegroundColorSpan(getColor(R.color.text2)), st, sb.length(), 0);
            items.add(sb);
        }
        new AlertDialog.Builder(this)
                .setTitle("Маршруты")
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (which == 0) startTool(TOOL_ROUTE, null);
                    else routeActions(routes.get(which - 1));
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void routeActions(Routes.Route r) {
        String[] acts = {"Показать на карте", "Изменить", "Таблица участков", r.visible ? "Скрыть" : "Показать слой",
                "Экспорт в KML", "Удалить"};
        new AlertDialog.Builder(this)
                .setTitle(r.name + " · " + Geo.dist(Routes.length(r.pts)))
                .setItems(acts, (d, which) -> {
                    switch (which) {
                        case 0: fitRoute(r); break;
                        case 1: startTool(TOOL_ROUTE, r); break;
                        case 2: {
                            List<double[]> keep = new ArrayList<>(toolPts);
                            toolPts.clear();
                            toolPts.addAll(r.pts);
                            toolTable();
                            toolPts.clear();
                            toolPts.addAll(keep);
                            break;
                        }
                        case 3: r.visible = !r.visible; saveRoutes(); break;
                        case 4:
                            saveAndShare(r.name.replaceAll("[^A-Za-z0-9А-Яа-яЁё_-]", "_") + ".kml", Routes.toKml(r), "Отправить маршрут");
                            break;
                        default:
                            new AlertDialog.Builder(this)
                                    .setTitle("Удалить «" + r.name + "»?")
                                    .setPositiveButton("Удалить", (d2, w2) -> {
                                        routes.remove(r);
                                        saveRoutes();
                                    })
                                    .setNegativeButton("Отмена", null)
                                    .show();
                    }
                })
                .show();
    }

    private void fitRoute(Routes.Route r) {
        if (r.pts.isEmpty()) return;
        r.visible = true;
        saveRoutes();
        double n = -90, s = 90, e = -180, w = 180;
        for (double[] p : r.pts) {
            n = Math.max(n, p[0]); s = Math.min(s, p[0]);
            e = Math.max(e, p[1]); w = Math.min(w, p[1]);
        }
        double padLat = Math.max(0.005, (n - s) * 0.15), padLon = Math.max(0.005, (e - w) * 0.15);
        map.zoomToBoundingBox(new BoundingBox(n + padLat, e + padLon, s - padLat, w - padLon), true, (int) dp(40));
    }

    // ================= Офлайн =================

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
                            + "Включите русские подписи или выберите спутник / топокарту.")
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
                .setItems(items, (dlg, which) -> {
                    if (counts[which] > MAX_TILES) {
                        Toast.makeText(this, "Слишком большая область — приблизьте карту или выберите уровень ниже",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    startDownload(cm, bb, zMin, zMaxes[which], counts[which]);
                })
                .setNeutralButton("Очистить (" + mb(tileDb().length()) + ")", (dlg, w) -> confirmClear())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void startDownload(CacheManager cm, BoundingBox bb, int zMin, int zMax, int total) {
        final TextView t = findViewById(R.id.dlText);
        findViewById(R.id.dlRow).setVisibility(View.VISIBLE);
        t.setText("Скачиваю область: 0%");
        dlTask = cm.downloadAreaAsyncNoUI(this, bb, zMin, zMax, new CacheManager.CacheManagerCallback() {
            @Override public void onTaskComplete() { finishDownload("Область сохранена — работает без интернета"); }
            @Override public void onTaskFailed(int errors) {
                finishDownload("Скачано с ошибками (" + errors + " тайлов). Проверьте интернет и повторите");
            }
            @Override public void updateProgress(int progress, int currentZoomLevel, int zoomMin, int zoomMax) {
                int pct = total > 0 ? Math.min(100, progress * 100 / total) : 0;
                t.setText("Скачиваю область: " + pct + "% · уровень " + currentZoomLevel + " из " + zoomMax);
            }
            @Override public void downloadStarted() {}
            @Override public void setPossibleTilesInArea(int total) {}
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
                        + "). Линия фронта, точки и маршруты останутся.")
                .setPositiveButton("Удалить", (dlg, w) -> {
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

    // ================= Системное =================

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

    private void showSavedCrash() {
        String crash = prefs.getString("crash", null);
        if (crash == null) return;
        prefs.edit().remove("crash").apply();
        new AlertDialog.Builder(this)
                .setTitle("Приложение упало в прошлый раз")
                .setMessage("Скопируйте текст ошибки и пришлите разработчику.")
                .setPositiveButton("Скопировать", (d, w) -> copy(crash, "Скопировано"))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (searchOpen()) {
            openSearch(false);
            return;
        }
        if (tool != TOOL_NONE) {
            stopTool();
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
        reloadLbs();
        showSavedCrash();
    }

    @Override
    protected void onPause() {
        if (map != null) {
            GeoPoint c = (GeoPoint) map.getMapCenter();
            prefs.edit().putString("viewLat", Double.toString(c.getLatitude()))
                    .putString("viewLon", Double.toString(c.getLongitude()))
                    .putFloat("viewZoom", (float) map.getZoomLevelDouble()).apply();
            map.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (dlTask != null) dlTask.cancel(true);
        if (map != null) map.onDetach();
        super.onDestroy();
    }

    @SuppressWarnings("unused")
    private static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
