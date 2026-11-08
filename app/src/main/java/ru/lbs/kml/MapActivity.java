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
        myLoc = new MyLocationOverlay(d);
        map.getOverlays().add(myLoc);
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

        applyOffline();
        applyNight();
        setupLock();
        if (prefs.getBoolean("locOn", false) && hasLocPerm()) startLocation(false);
        checkUpdates();
        if (savedInstanceState == null) handleViewIntent(getIntent());

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
        findViewById(R.id.btnCompass).setOnClickListener(v -> onCompassClick());
        findViewById(R.id.btnCompass).setOnLongClickListener(v -> {
            setCompassMode(!compassMode);
            return true;
        });
        findViewById(R.id.btnGrid).setOnClickListener(v -> toggleGrid());
        findViewById(R.id.btnMyLoc).setOnClickListener(v -> onMyLocClick());
        findViewById(R.id.btnMyLoc).setOnLongClickListener(v -> {
            if (locOn) stopLocation();
            else startLocation(true);
            return true;
        });
        map.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { dx = e.getX(); dy = e.getY(); }
                else if (follow && e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE
                        && Math.hypot(e.getX() - dx, e.getY() - dy) > dp(24)) { follow = false; styleMyLoc(); }
                return false;
            }
        });
        findViewById(R.id.offlineChip).setOnClickListener(v -> openMenu(true));
        findViewById(R.id.toolProfile).setOnClickListener(v -> showProfile(toolPts, tool == TOOL_RULER ? "линейка" : "маршрут"));
        findViewById(R.id.dlCancel).setOnClickListener(v -> cancelDownload());
        findViewById(R.id.coordCard).setOnClickListener(v -> copyCenterCoords());
        findViewById(R.id.targetClear).setOnClickListener(v -> clearTarget());

        findViewById(R.id.dockLayers).setOnClickListener(v -> showLayers());
        findViewById(R.id.dockRuler).setOnClickListener(v -> startTool(TOOL_RULER, null));
        findViewById(R.id.dockRoute).setOnClickListener(v -> showRoutes());
        findViewById(R.id.dockPoints).setOnClickListener(v -> showPointsList());
        findViewById(R.id.dockMenu).setOnClickListener(v -> openMenu(true));
        findViewById(R.id.btnSearch).setOnClickListener(v -> openSearch(true));
        setupMenu();
        findViewById(R.id.genScrim).setOnClickListener(v -> closeSheet());
        findViewById(R.id.genClose).setOnClickListener(v -> closeSheet());
        setupLbsWeb();

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
        View badge = findViewById(R.id.menuBadge);
        if (data == null || data.lines.isEmpty()) {
            lbsDate = "";
            badge.setVisibility(View.VISIBLE);
            map.invalidate();
            return;
        }
        badge.setVisibility(View.GONE);
        lbsDate = data.date.replace(" o ", ", ");
        boolean gray = LbsCore.optGray(this), diff = LbsCore.optDiff(this);
        if (gray && prefs.getBoolean("showGray", true)) {
            for (double[][] g : data.gray) {
                Polygon pg = new Polygon();
                pg.setPoints(pts(g));
                pg.getFillPaint().setColor(Color.argb(140, 70, 70, 70));
                pg.getOutlinePaint().setColor(Color.argb(230, 50, 50, 50));
                pg.getOutlinePaint().setStrokeWidth(dp(1.2f));
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
                findViewById(R.id.compassRose).setRotation(o);
            }
        }
    }

    private void resetNorth() {
        map.getController().animateTo(map.getMapCenter(), map.getZoomLevelDouble(), 400L, 0f);
    }

    // ---- компас по датчику телефона

    private android.hardware.SensorManager sensors;
    private boolean compassMode;
    private float azSin, azCos;
    private boolean azInit;
    private long lastHeadingApply;
    private float declination;

    /** Нажатие: повернута карта → на север; на севере → режим «по компасу»; в режиме → выключить. */
    private void onCompassClick() {
        if (compassMode) {
            setCompassMode(false);
        } else if (Math.abs(map.getMapOrientation()) > 0.5f) {
            resetNorth();
        } else {
            setCompassMode(true);
        }
    }

    private void setCompassMode(boolean on) {
        if (sensors == null) sensors = getSystemService(android.hardware.SensorManager.class);
        android.hardware.Sensor rv = sensors == null ? null
                : sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        if (on && rv == null) {
            Toast.makeText(this, "В телефоне нет датчика компаса", Toast.LENGTH_LONG).show();
            return;
        }
        compassMode = on;
        findViewById(R.id.btnCompass).setBackgroundResource(on ? R.drawable.bg_round_accent : R.drawable.bg_round_hud);
        findViewById(R.id.headingText).setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            azInit = false;
            GeoPoint c = (GeoPoint) map.getMapCenter();
            declination = new android.hardware.GeomagneticField((float) c.getLatitude(), (float) c.getLongitude(), 0,
                    System.currentTimeMillis()).getDeclination();
            sensors.registerListener(compassListener, rv, android.hardware.SensorManager.SENSOR_DELAY_UI);
            Toast.makeText(this, "Карта поворачивается по компасу", Toast.LENGTH_SHORT).show();
        } else {
            sensors.unregisterListener(compassListener);
            resetNorth();
        }
    }

    private final android.hardware.SensorEventListener compassListener = new android.hardware.SensorEventListener() {
        private final float[] rm = new float[9], or = new float[3];

        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            android.hardware.SensorManager.getRotationMatrixFromVector(rm, e.values);
            android.hardware.SensorManager.getOrientation(rm, or);
            double az = Math.toDegrees(or[0]) + declination; // истинный азимут
            // сглаживание по углу (через синус/косинус, без скачка 359→0)
            float s = (float) Math.sin(Math.toRadians(az)), c = (float) Math.cos(Math.toRadians(az));
            if (!azInit) { azSin = s; azCos = c; azInit = true; }
            azSin += (s - azSin) * 0.15f;
            azCos += (c - azCos) * 0.15f;
            long now = System.currentTimeMillis();
            if (now - lastHeadingApply < 40) return;
            lastHeadingApply = now;
            float heading = (float) ((Math.toDegrees(Math.atan2(azSin, azCos)) + 360) % 360);
            map.setMapOrientation(-heading);
            ((TextView) findViewById(R.id.headingText)).setText(String.format(Locale.US, "%.0f°", heading));
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
        }
    };

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
        findViewById(R.id.chipsScroll).setVisibility(prefs.getBoolean("showChips", false) ? View.VISIBLE : View.GONE);
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

    private static final int STYLE_SCHEME = 0, STYLE_SAT = 1, STYLE_TOPO = 2, STYLE_RELIEF = 3;
    private static final String[] STYLE_NAMES = {"Схема", "Спутник (Esri)", "Топокарта (Esri)", "Рельеф (OpenTopoMap)"};
    private static final String[] STYLE_SHORT = {"Схема", "Esri Спутник", "Esri Топо", "Рельеф"};
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

    /** Рельеф с горизонталями и отметками высот. */
    private static final OnlineTileSourceBase RELIEF = new XYTileSource("OpenTopoMap",
            0, 17, 256, ".png", new String[]{
            "https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/"},
            "© OpenTopoMap",
            new org.osmdroid.tileprovider.tilesource.TileSourcePolicy(2,
                    org.osmdroid.tileprovider.tilesource.TileSourcePolicy.FLAG_NO_BULK
                            | org.osmdroid.tileprovider.tilesource.TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL));
    private static final OnlineTileSourceBase HILLSHADE = new EsriSource("EsriHillshade", "Elevation/World_Hillshade", ".jpg");

    private TilesOverlay esriLabels, hillshade;
    private int currentStyle = STYLE_SAT;

    /** Отмывка рельефа поверх любой подложки (полупрозрачная). */
    private void applyHillshade() {
        if (hillshade != null) {
            map.getOverlays().remove(hillshade);
            hillshade.onDetach(map);
            hillshade = null;
        }
        if (!prefs.getBoolean("hillshade", false) || currentStyle == STYLE_RELIEF) return;
        MapTileProviderBasic prov = new MapTileProviderBasic(getApplicationContext(), HILLSHADE);
        hillshade = new TilesOverlay(prov, getApplicationContext());
        hillshade.setLoadingBackgroundColor(Color.TRANSPARENT);
        hillshade.setLoadingLineColor(Color.TRANSPARENT);
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setScale(1f, 1f, 1f, 0.42f);
        hillshade.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        map.getOverlays().add(2, hillshade);
    }

    private boolean labelsOn() {
        return prefs.getBoolean("labelsRu", true);
    }

    private void applyStyle(int style) {
        if (style < 0 || style > 3) style = STYLE_SAT;
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
        } else if (style == STYLE_RELIEF) {
            map.setTileSource(RELIEF);
            attr.setText("© OpenTopoMap (CC-BY-SA), © OpenStreetMap, SRTM");
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
        applyHillshade();
        placesOverlay.setEnabled(ru);
        updateCoords();
        map.invalidate();
    }

    // ================= Панели в своём стиле (вместо стандартных окон) =================

    private LinearLayout sheetContent, sheetFooter;

    private void sheet(String title) {
        View host = findViewById(R.id.genSheet), panel = findViewById(R.id.genPanel), scrim = findViewById(R.id.genScrim);
        ((TextView) findViewById(R.id.genTitle)).setText(title);
        sheetContent = findViewById(R.id.genContent);
        sheetFooter = findViewById(R.id.genFooter);
        sheetContent.removeAllViews();
        sheetFooter.removeAllViews();
        sheetFooter.setVisibility(View.GONE);
        findViewById(R.id.genScroll).scrollTo(0, 0);
        if (host.getVisibility() != View.VISIBLE) {
            host.setVisibility(View.VISIBLE);
            panel.setTranslationY(dp(600));
            scrim.setAlpha(0f);
            panel.animate().translationY(0).setDuration(220).start();
            scrim.animate().alpha(1f).setDuration(220).start();
        }
    }

    private void closeSheet() {
        View host = findViewById(R.id.genSheet), panel = findViewById(R.id.genPanel), scrim = findViewById(R.id.genScrim);
        if (host.getVisibility() != View.VISIBLE) return;
        hideKeyboard();
        panel.animate().translationY(dp(800)).setDuration(170).withEndAction(() -> host.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(170).start();
    }

    private boolean sheetOpen() {
        return findViewById(R.id.genSheet).getVisibility() == View.VISIBLE;
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private TextView hSection(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setLetterSpacing(0.1f);
        t.setTextColor(getColor(R.color.hud_text2));
        t.setPadding((int) dp(2), (int) dp(16), 0, (int) dp(8));
        sheetContent.addView(t);
        return t;
    }

    private interface Toggle {
        void on(boolean on);
    }

    private View hToggle(String title, String sub, boolean value, Toggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(12), (int) dp(9), (int) dp(6), (int) dp(9));
        row.setBackgroundResource(R.drawable.bg_hud_btn);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.hud_text));
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(getColor(R.color.hud_text2));
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, on) -> cb.on(on));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        sheetContent.addView(row);
        return row;
    }

    /** Строка списка: значок слева, заголовок/подзаголовок, необязательный элемент справа. */
    private LinearLayout hRow(View lead, String title, String sub, View trail, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(10), (int) dp(9), (int) dp(6), (int) dp(9));
        row.setBackgroundResource(R.drawable.bg_hud_btn);
        if (lead != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) dp(12));
            row.addView(lead, lp);
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.hud_text));
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(getColor(R.color.hud_text2));
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (trail != null) row.addView(trail);
        if (click != null) row.setOnClickListener(click);
        sheetContent.addView(row);
        return row;
    }

    private ImageView hIcon(int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setLayoutParams(new LinearLayout.LayoutParams((int) dp(sizeDp), (int) dp(sizeDp)));
        return iv;
    }

    private ImageView hIconButton(int res, int tint, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        int p = (int) dp(9);
        iv.setPadding(p, p, p, p);
        iv.setBackgroundResource(R.drawable.bg_hud_btn);
        iv.setLayoutParams(new LinearLayout.LayoutParams((int) dp(40), (int) dp(40)));
        iv.setOnClickListener(click);
        return iv;
    }

    private EditText hInput(String hint, String text) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(text);
        e.setTextSize(16);
        e.setTextColor(getColor(R.color.hud_text));
        e.setHintTextColor(getColor(R.color.hud_text2));
        e.setBackgroundResource(R.drawable.bg_input);
        int ph = (int) dp(14), pv = (int) dp(11);
        e.setPadding(ph, pv, ph, pv);
        return e;
    }

    private LinearLayout hCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card_dark);
        int p = (int) dp(12);
        c.setPadding(p, (int) dp(10), p, (int) dp(10));
        return c;
    }

    /** Кнопка-«таблетка» (иконка + текст). */
    private TextView hChip(String text, int icon, boolean accent, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTextColor(accent ? Color.WHITE : getColor(R.color.hud_text));
        t.setBackgroundResource(accent ? R.drawable.bg_accent_btn : R.drawable.bg_sheet_chip);
        int ph = (int) dp(12);
        t.setPadding(ph, 0, ph, 0);
        if (icon != 0) {
            android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
            d.setTint(accent ? Color.WHITE : getColor(R.color.hud_text));
            int s = (int) dp(18);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawables(d, null, null, null);
            t.setCompoundDrawablePadding((int) dp(6));
        }
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout hChipRow(View... chips) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < chips.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(42), 1);
            if (i < chips.length - 1) lp.setMarginEnd((int) dp(8));
            row.addView(chips[i], lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = (int) dp(6);
        sheetContent.addView(row, rl);
        return row;
    }

    private void footer(String cancel, String ok, View.OnClickListener onOk) {
        sheetFooter.setVisibility(View.VISIBLE);
        sheetFooter.removeAllViews();
        TextView c = hChip(cancel, 0, false, v -> closeSheet());
        TextView o = hChip(ok, R.drawable.ic_check, true, onOk);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, (int) dp(48), 1);
        lc.setMarginEnd((int) dp(10));
        sheetFooter.addView(c, lc);
        sheetFooter.addView(o, new LinearLayout.LayoutParams(0, (int) dp(48), 1.4f));
    }

    private void styleSegHud(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(on ? getColor(R.color.hud_accent) : 0xFF22262C);
        g.setStroke((int) dp(1), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
        t.setBackground(g);
        t.setTextColor(on ? Color.WHITE : getColor(R.color.hud_text));
        android.graphics.drawable.Drawable[] ds = t.getCompoundDrawables();
        if (ds[1] != null) ds[1].setTint(on ? Color.WHITE : getColor(R.color.hud_text));
    }

    // ---------- Слои ----------

    private void showLayers() {
        sheet("Слои");
        hSection("ПОДЛОЖКА");
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> btns = new ArrayList<>();
        final String[] names = {"Спутник", "Рельеф", "Топо", "Схема"};
        final int[] idx = {STYLE_SAT, STYLE_RELIEF, STYLE_TOPO, STYLE_SCHEME};
        final int[] icons = {R.drawable.ic_layers, R.drawable.ic_mountain, R.drawable.ic_lbs, R.drawable.ic_grid};
        for (int i = 0; i < 4; i++) {
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(13.5f);
            t.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.Drawable d = getDrawable(icons[i]).mutate();
            int s = (int) dp(22);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawables(null, d, null, null);
            t.setCompoundDrawablePadding((int) dp(4));
            t.setPadding(0, (int) dp(12), 0, (int) dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i < 3) lp.setMarginEnd((int) dp(6));
            final int st = idx[i];
            t.setOnClickListener(v -> {
                applyStyle(st);
                for (int k = 0; k < btns.size(); k++) styleSegHud(btns.get(k), idx[k] == currentStyle);
            });
            btns.add(t);
            seg.addView(t, lp);
            styleSegHud(t, st == currentStyle);
        }
        sheetContent.addView(seg);

        hSection("ВЫСОТЫ");
        hToggle("Отмывка рельефа", "Тени холмов и балок поверх подложки", prefs.getBoolean("hillshade", false), on -> {
            prefs.edit().putBoolean("hillshade", on).apply();
            applyHillshade();
            map.invalidate();
        });
        TextView hint = new TextView(this);
        hint.setText("Подложка «Рельеф» — горизонтали и отметки высот. Высота центра карты показана в плашке координат.");
        hint.setTextColor(getColor(R.color.hud_text2));
        hint.setTextSize(12);
        hint.setPadding((int) dp(12), (int) dp(2), (int) dp(12), 0);
        sheetContent.addView(hint);

        hSection("НА КАРТЕ");
        hToggle("Подписи на русском", "Названия населённых пунктов", labelsOn(), on -> {
            prefs.edit().putBoolean("labelsRu", on).apply();
            applyStyle(currentStyle);
        });
        hToggle("Сетка СК-42", "Километровая сетка и круг-компас", gridOn(), on -> {
            prefs.edit().putBoolean("gridOn", on).apply();
            applyGrid();
        });
        hToggle("Кнопки направлений", "Строка с областями под поиском", prefs.getBoolean("showChips", false), on -> {
            prefs.edit().putBoolean("showChips", on).apply();
            findViewById(R.id.chipsScroll).setVisibility(on ? View.VISIBLE : View.GONE);
        });

        hSection("ЛИНИЯ ФРОНТА");
        hToggle("ЛБС", lbsDate.isEmpty() ? "не загружена" : "снимок " + lbsDate, prefs.getBoolean("showLbs", true), on -> {
            prefs.edit().putBoolean("showLbs", on).apply();
            forceReloadLbs();
        });
        hToggle("Изменения", "Новое положение — жёлтым, прежнее — серым", prefs.getBoolean("showDiff", true), on -> {
            prefs.edit().putBoolean("showDiff", on).apply();
            forceReloadLbs();
        });
        hToggle("Серая зона", "Статус неизвестен", prefs.getBoolean("showGray", true), on -> {
            prefs.edit().putBoolean("showGray", on).apply();
            forceReloadLbs();
        });
    }

    // ================= Меню =================

    private void showAbout() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        sheet("О приложении");
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams((int) dp(64), (int) dp(64)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding((int) dp(14), 0, 0, 0);
        TextView n = new TextView(this);
        n.setText("ЛБС KML");
        n.setTextSize(22);
        n.setTextColor(getColor(R.color.hud_text));
        n.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        TextView v = new TextView(this);
        v.setText("Версия " + ver);
        v.setTextColor(getColor(R.color.hud_text2));
        v.setTextSize(13);
        tt.addView(n);
        tt.addView(v);
        head.addView(tt);
        sheetContent.addView(head);

        hSection("ВОЗМОЖНОСТИ");
        int tx = getColor(R.color.hud_text);
        hRow(hIcon(R.drawable.ic_lbs, 0xFFFF5A4E, 22), "Линия фронта", "Обновление, изменения, серая зона, файл KML", null, null);
        hRow(hIcon(R.drawable.ic_grid, tx, 22), "Сетка СК-42", "Гаусс-Крюгер, круг-компас, координаты X/Y", null, null);
        hRow(hIcon(R.drawable.ic_mountain, tx, 22), "Высоты и рельеф", "Высота точки, горизонтали, отмывка", null, null);
        hRow(hIcon(R.drawable.ic_place, tx, 22), "Точки", "Цвета, значки, цель, экспорт KML/GPX", null, null);
        hRow(hIcon(R.drawable.ic_route, tx, 22), "Линейка и маршруты", "Азимуты, дир. углы, таблица участков", null, null);
        hRow(hIcon(R.drawable.ic_download, tx, 22), "Офлайн-карты", "Скачивание районов, работа без сети", null, null);

        hSection("ИСТОЧНИКИ ДАННЫХ");
        TextView src = new TextView(this);
        src.setText("Подложки: Esri, OpenTopoMap, CARTO, OpenStreetMap.\nНаселённые пункты: OpenStreetMap.\n"
                + "Высоты: Terrain Tiles (SRTM), точность ±5–10 м.");
        src.setTextColor(getColor(R.color.hud_text2));
        src.setTextSize(12.5f);
        src.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(src);
    }

    // ================= Информация о карте =================

    private void showInfo() {
        sheet("О карте");
        LinearLayout c1 = hCard();
        addKV(c1, "Линия фронта", lbsDate.isEmpty() ? "не загружена" : "снимок " + lbsDate);
        addKV(c1, "Подложка", STYLE_NAMES[currentStyle]);
        final TextView sat = addKV(c1, "Спутник в центре", currentStyle == STYLE_SAT ? "загружаю…" : "включите подложку «Спутник»");
        GeoPoint c = (GeoPoint) map.getMapCenter();
        final TextView el = addKV(c1, "Высота центра", "…");
        sheetContent.addView(c1);
        Elevation.get(this, c.getLatitude(), c.getLongitude(), h -> el.setText(Elevation.fmt(h)));
        if (currentStyle == STYLE_SAT) queryImageryDate(t -> sat.setText(t.replace("Спутник (центр карты): ", "").replace("Спутник: ", "")));

        hSection("ЛЕГЕНДА");
        legendRow(0xFFE61414, false, "ЛБС", "линия фронта");
        legendRow(0xFFFFD200, false, "Изменения", "новое положение линии");
        legendRow(0xFF8C8C8C, false, "Прежнее положение", "до последнего снимка");
        legendRow(0x8C464646, true, "Серая зона", "статус неизвестен");
        legendRow(0xFF00E5FF, false, "Маршруты", "свои, цвет задаётся");

        hSection("ПОДСКАЗКИ");
        TextView tips = new TextView(this);
        tips.setText("• Нажатие на прицел в центре — новая точка\n• Долгое нажатие — точка в этом месте\n"
                + "• Двумя пальцами — поворот карты\n• Компас: север вверх ↔ по компасу телефона\n"
                + "• В поиске можно вводить координаты: 48.28, 37.17 или X 5351605 Y 7364745");
        tips.setTextColor(getColor(R.color.hud_text2));
        tips.setTextSize(13);
        tips.setLineSpacing(dp(3), 1f);
        tips.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(tips);
    }

    private TextView addKV(LinearLayout card, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) dp(4), 0, (int) dp(4));
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextColor(getColor(R.color.hud_text2));
        kt.setTextSize(13);
        TextView vt = new TextView(this);
        vt.setText(v);
        vt.setTextColor(getColor(R.color.hud_text));
        vt.setTextSize(13);
        vt.setGravity(android.view.Gravity.END);
        row.addView(kt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(vt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        card.addView(row);
        return vt;
    }

    private void legendRow(int color, boolean area, String title, String sub) {
        View sw = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(area ? 4 : 2));
        g.setColor(color);
        if (area) g.setStroke((int) dp(1), 0xFF505050);
        sw.setBackground(g);
        sw.setLayoutParams(new LinearLayout.LayoutParams((int) dp(26), (int) dp(area ? 16 : 5)));
        hRow(sw, title, sub, null, null);
    }

    private interface TextCallback {
        void on(String text);
    }

    private void queryImageryDate(TextCallback cb) {
        if (offline()) {
            cb.on("Спутник: дата снимка недоступна без сети");
            return;
        }
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
        findViewById(R.id.topRow).setVisibility(open ? View.GONE : View.VISIBLE);
        findViewById(R.id.searchPill).setVisibility(open ? View.VISIBLE : View.GONE);
        findViewById(R.id.searchHint).setVisibility(open ? View.GONE : View.VISIBLE);
        searchBox.setVisibility(open ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnSearchClose).setVisibility(open ? View.VISIBLE : View.GONE);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (open) {
            runLocalSearch();
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
        findViewById(R.id.chipsScroll).setVisibility(!show && prefs.getBoolean("showChips", false) ? View.VISIBLE : View.GONE);
    }

    private void runLocalSearch() {
        String q = searchBox.getText().toString().trim();
        results.removeAllViews();
        showResults(true);
        if (q.isEmpty()) {
            // пустой запрос — быстрый переход по направлениям
            addHeader("НАПРАВЛЕНИЯ");
            for (int i = 0; i < NAMES.length; i++) {
                final int idx = i;
                addResultRow(NAMES[i], i == 0 ? "весь фронт" : "область", v -> {
                    openSearch(false);
                    zoomTo(idx, true);
                });
            }
            return;
        }
        CoordParse.Result cr = CoordParse.parse(q);
        if (cr != null) {
            addHeader("КООРДИНАТЫ · " + cr.kind.toUpperCase(Locale.ROOT));
            final double clat = cr.lat, clon = cr.lon;
            addResultRow(String.format(Locale.US, "Перейти: %.6f, %.6f", clat, clon),
                    "СК-42: " + Sk42.format(clat, clon), v -> goTo(clat, clon, "Координаты",
                            String.format(Locale.US, "%.6f, %.6f", clat, clon), 15.0));
        }
        if (places == null) {
            addResultRow("Загружаю список…", "", null);
            return;
        }
        if (cr != null) addHeader("НАСЕЛЁННЫЕ ПУНКТЫ");
        for (int i : places.search(q, 30)) {
            final int idx = i;
            addResultRow(places.name[i], places.subtitle(i), v -> showPlace(idx));
        }
        final String query = q;
        addResultRow("Искать «" + q + "» в интернете", "OpenStreetMap Nominatim", v -> searchOnline(query));
    }

    private void addHeader(String t) {
        TextView h = new TextView(this);
        h.setText(t);
        h.setTextSize(11);
        h.setLetterSpacing(0.1f);
        h.setTextColor(getColor(R.color.hud_text2));
        h.setPadding((int) dp(18), (int) dp(10), (int) dp(18), (int) dp(4));
        results.addView(h);
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
        if (offline()) {
            Toast.makeText(this, "Режим «Без сети»: поиск только по встроенному списку", Toast.LENGTH_LONG).show();
            return;
        }
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
        findViewById(R.id.coordCard).setVisibility(View.VISIBLE);
        findViewById(R.id.scaleRow).setVisibility(on ? View.VISIBLE : View.GONE);
        ((ImageView) findViewById(R.id.btnGrid)).setImageTintList(ColorStateList.valueOf(
                on ? getColor(R.color.hud_accent) : getColor(R.color.hud_text)));
        updateCoords();
        map.invalidate();
    }

    private static final double[] BARS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000};

    private double centerElev = Double.NaN, targetElev = Double.NaN;
    private final android.os.Handler elevHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String coordWgsText = "";

    private final Runnable elevTask = () -> {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        Elevation.get(this, c.getLatitude(), c.getLongitude(), h -> {
            centerElev = h;
            showCoordLine();
            GeoPoint c2 = (GeoPoint) map.getMapCenter();
            updateTargetCard(c2.getLatitude(), c2.getLongitude());
        });
    };

    private void showCoordLine() {
        ((TextView) findViewById(R.id.coordWgs)).setText(coordWgsText
                + (Double.isNaN(centerElev) ? "" : "   ↑ " + Math.round(centerElev) + " м"));
    }

    private void updateCoords() {
        if (reticleOverlay == null || map == null) return;
        elevHandler.removeCallbacks(elevTask);
        elevHandler.postDelayed(elevTask, 250);
        GeoPoint c = (GeoPoint) map.getMapCenter();
        double lat = c.getLatitude(), lon = c.getLongitude();
        if (findViewById(R.id.coordCard).getVisibility() == View.VISIBLE) {
            double[] r = Sk42.fromWgs(lat, lon);
            ((TextView) findViewById(R.id.coordSk)).setText("X " + Math.round(r[0]) + "   Y " + Math.round(r[1]));
            coordWgsText = String.format(Locale.US, "%.5f° %s  %.5f° %s",
                    Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
            showCoordLine();
        }
        if (gridOn()) {
            Projection pj = map.getProjection();
            android.graphics.Point p0 = pj.toPixels(c, null);
            android.graphics.Point p1 = pj.toPixels(new GeoPoint(lat + 1000.0 / 111132.0, lon), null);
            double pxPerM = Math.hypot(p1.x - p0.x, p1.y - p0.y) / 1000.0;
            if (pxPerM > 0) {
                double xdpi = getResources().getDisplayMetrics().xdpi;
                long scale = Math.round((1 / pxPerM) * xdpi / 0.0254 / 100.0) * 100;
                ((TextView) findViewById(R.id.scaleText)).setText("1:" + Geo.group(scale));
                double bar = BARS[0];
                for (double m : BARS) if (m * pxPerM <= dp(110)) bar = m;
                ((ScaleBarView) findViewById(R.id.scaleBar)).setBarPx((float) (bar * pxPerM));
                ((TextView) findViewById(R.id.scaleBarText)).setText(Geo.dist(bar));
            }
            int maxZ = (int) map.getTileProvider().getTileSource().getMaximumZoomLevel();
            ((TextView) findViewById(R.id.zoomText)).setText("z" + Math.round(map.getZoomLevelDouble()) + "/" + maxZ);
        }
        ((TextView) findViewById(R.id.zoomLevel)).setText(Long.toString(Math.round(map.getZoomLevelDouble())));
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
        targetElev = Double.NaN;
        Elevation.get(this, lat, lon, h -> { targetElev = h; updateCoords(); });
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
            double tl = Double.parseDouble(prefs.getString("targetLat", "0")), tn = Double.parseDouble(prefs.getString("targetLon", "0"));
            reticleOverlay.setTarget(tl, tn);
            Elevation.get(this, tl, tn, h -> { targetElev = h; updateCoords(); });
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
        String dh = "";
        if (!Double.isNaN(centerElev) && !Double.isNaN(targetElev)) {
            long d = Math.round(targetElev - centerElev);
            dh = "   " + (d >= 0 ? "↑+" : "↓") + d + " м";
        }
        ((TextView) findViewById(R.id.targetMain)).setText(
                "→ " + Geo.dist(inv[0]) + "   " + String.format(Locale.US, "%.1f°", inv[1]) + dh);
        String fromMe = "";
        android.location.Location me = myLoc == null ? null : myLoc.getLocation();
        if (me != null) {
            double[] mi = Geo.inverse(me.getLatitude(), me.getLongitude(), tLat, tLon);
            fromMe = "\nот меня: " + Geo.dist(mi[0]) + " · " + String.format(Locale.US, "%.1f°", mi[1])
                    + " · " + Geo.mils(Geo.gridBearing(me.getLatitude(), me.getLongitude(), tLat, tLon));
        }
        ((TextView) findViewById(R.id.targetSub)).setText(
                prefs.getString("targetName", "") + " · д.у. " + Geo.mils(Geo.gridBearing(lat, lon, tLat, tLon)) + fromMe);
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
        // нажатие на центр (прицел) — новая точка в центре
        Projection pj = map.getProjection();
        android.graphics.Point a = pj.toPixels(p, null), b = pj.toPixels(map.getMapCenter(), null);
        if (Math.hypot(a.x - b.x, a.y - b.y) < dp(26)) {
            newPointAtCenter();
            return true;
        }
        return false;
    }

    private void newPointAtCenter() {
        if (tool != TOOL_NONE) {
            GeoPoint c = (GeoPoint) map.getMapCenter();
            toolAdd(c.getLatitude(), c.getLongitude());
            return;
        }
        GeoPoint c = (GeoPoint) map.getMapCenter();
        editPoint(null, c.getLatitude(), c.getLongitude(), null);
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
            sheet(p.name);
            if (p.note != null && !p.note.isEmpty()) {
                TextView n = new TextView(this);
                n.setText(p.note);
                n.setTextColor(getColor(R.color.hud_text2));
                n.setTextSize(13);
                sheetContent.addView(n);
            }
            coordsCard(p.lat, p.lon);
            hChipRow(
                    hChip("Навести", R.drawable.ic_flag, false, v -> { closeSheet(); setTarget(p.lat, p.lon, p.name); }),
                    hChip("Убрать", R.drawable.ic_close, false, v -> { closeSheet(); pointsOverlay.setExtra(null); map.invalidate(); }));
            footer("Закрыть", "Сохранить точку", v -> {
                pointsOverlay.setExtra(null);
                editPoint(null, p.lat, p.lon, p.name);
            });
            return;
        }
        editPoint(p, p.lat, p.lon, null);
    }

    /** Карточка координат (нажатие — скопировать). */
    private void coordsCard(double lat, double lon) {
        LinearLayout card = hCard();
        TextView cap = new TextView(this);
        cap.setText("КООРДИНАТЫ · нажмите, чтобы скопировать");
        cap.setTextSize(10.5f);
        cap.setLetterSpacing(0.06f);
        cap.setTextColor(getColor(R.color.hud_text2));
        TextView sk = new TextView(this);
        double[] r = Sk42.fromWgs(lat, lon);
        sk.setText("X " + Math.round(r[0]) + "   Y " + Math.round(r[1]));
        sk.setTextSize(16);
        sk.setTextColor(getColor(R.color.hud_text));
        sk.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        TextView wgs = new TextView(this);
        wgs.setText(String.format(Locale.US, "%.6f, %.6f  ·  зона %d", lat, lon, (int) r[2]));
        wgs.setTextSize(12.5f);
        wgs.setTextColor(0xFF64B5F6);
        final TextView el = new TextView(this);
        el.setText("Высота: …");
        el.setTextSize(12.5f);
        el.setTextColor(getColor(R.color.hud_text2));
        card.addView(cap);
        card.addView(sk);
        card.addView(wgs);
        card.addView(el);
        Elevation.get(this, lat, lon, h -> el.setText("Высота: " + Elevation.fmt(h) + "  (±5–10 м)"));
        card.setOnClickListener(v -> copy(coordsText(lat, lon), "Координаты скопированы"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(12);
        sheetContent.addView(card, lp);
    }

    /** Создание/редактирование точки в своей панели: название, цвет, значок, заметка. */
    private void editPoint(UserPoints.Point existing, double lat, double lon, String presetName) {
        sheet(existing == null ? "Новая точка" : "Точка");
        final int[] chosen = {existing != null ? existing.color : prefs.getInt("lastColor", UserPoints.COLORS[0])};
        final String[] icon = {existing != null && existing.icon != null ? existing.icon : prefs.getString("lastIcon", "")};

        // превью булавки + название
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final ImageView preview = new ImageView(this);
        float den = getResources().getDisplayMetrics().density;
        preview.setImageBitmap(UserPoints.pin(chosen[0], den * 1.3f, icon[0]));
        top.addView(preview, new LinearLayout.LayoutParams((int) dp(40), (int) dp(52)));
        final EditText name = hInput("Название",
                existing != null ? existing.name : presetName != null ? presetName : "Точка " + (userPoints.size() + 1));
        name.setSingleLine(true);
        name.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        nl.setMarginStart((int) dp(12));
        top.addView(name, nl);
        sheetContent.addView(top);

        final EditText note = hInput("Заметка (необязательно)", existing != null ? existing.note : "");
        LinearLayout.LayoutParams ntl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ntl.topMargin = (int) dp(8);
        sheetContent.addView(note, ntl);

        hSection("ЦВЕТ");
        final List<View> swatches = new ArrayList<>();
        LinearLayout colRow = null;
        for (int i = 0; i < UserPoints.COLORS.length; i++) {
            if (i % 8 == 0) {
                colRow = new LinearLayout(this);
                colRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = (int) dp(8);
                sheetContent.addView(colRow, rl);
            }
            final int col = UserPoints.COLORS[i];
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(32), 1);
            lp.setMarginEnd((int) dp(7));
            sw.setContentDescription(UserPoints.COLOR_NAMES[i]);
            sw.setOnClickListener(v -> {
                chosen[0] = col;
                for (int k = 0; k < swatches.size(); k++)
                    swatches.get(k).setBackground(swatchHud(UserPoints.COLORS[k], UserPoints.COLORS[k] == col));
                preview.setImageBitmap(UserPoints.pin(chosen[0], den * 1.3f, icon[0]));
            });
            sw.setBackground(swatchHud(col, col == chosen[0]));
            swatches.add(sw);
            colRow.addView(sw, lp);
        }

        hSection("ЗНАЧОК");
        final List<TextView> iconViews = new ArrayList<>();
        LinearLayout icRow = null;
        for (int i = 0; i < UserPoints.ICONS.length; i++) {
            if (i % 6 == 0) {
                icRow = new LinearLayout(this);
                icRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = (int) dp(7);
                sheetContent.addView(icRow, rl);
            }
            final String ic = UserPoints.ICONS[i];
            final String icName = UserPoints.ICON_NAMES[i];
            TextView t = new TextView(this);
            t.setText(ic.isEmpty() ? "•" : ic);
            t.setTextSize(18);
            t.setGravity(android.view.Gravity.CENTER);
            t.setContentDescription(icName);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(40), 1);
            lp.setMarginEnd((int) dp(7));
            t.setOnClickListener(v -> {
                icon[0] = ic;
                for (TextView tv : iconViews) styleIconHud(tv, tv == v);
                preview.setImageBitmap(UserPoints.pin(chosen[0], den * 1.3f, icon[0]));
                Toast.makeText(this, icName, Toast.LENGTH_SHORT).show();
            });
            iconViews.add(t);
            icRow.addView(t, lp);
            styleIconHud(t, ic.equals(icon[0]));
        }

        coordsCard(lat, lon);

        hChipRow(
                hChip("Навести", R.drawable.ic_flag, false, v -> {
                    String nm = name.getText().toString().trim();
                    closeSheet();
                    setTarget(lat, lon, nm.isEmpty() ? "Точка" : nm);
                }),
                hChip("Линейка", R.drawable.ic_ruler, false, v -> {
                    closeSheet();
                    startTool(TOOL_RULER, null);
                    toolAdd(lat, lon);
                }),
                hChip("Поделиться", R.drawable.ic_share, false, v -> {
                    String nm = name.getText().toString().trim();
                    sharePointText(nm.isEmpty() ? "Точка" : nm, lat, lon);
                }));
        if (existing != null) {
            hChipRow(hChip("Удалить точку", R.drawable.ic_delete, false, v -> new AlertDialog.Builder(this)
                    .setTitle("Удалить «" + existing.name + "»?")
                    .setPositiveButton("Удалить", (d2, w2) -> {
                        userPoints.remove(existing);
                        savePoints();
                        closeSheet();
                    })
                    .setNegativeButton("Отмена", null)
                    .show()));
        }

        footer("Отмена", "Сохранить", v -> {
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
            p.icon = icon[0];
            prefs.edit().putInt("lastColor", chosen[0]).putString("lastIcon", icon[0]).apply();
            savePoints();
            closeSheet();
        });
    }

    private void styleIconHud(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(on ? getColor(R.color.hud_accent) : 0xFF22262C);
        g.setStroke((int) dp(1), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
        t.setBackground(g);
        t.setTextColor(on ? Color.WHITE : getColor(R.color.hud_text));
    }

    private GradientDrawable swatchHud(int color, boolean sel) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke((int) dp(sel ? 3 : 1), sel ? Color.WHITE : getColor(R.color.hud_line));
        return g;
    }

    private void showPointsList() {
        sheet("Мои точки" + (userPoints.isEmpty() ? "" : " · " + userPoints.size()));
        hChipRow(
                hChip("Точка в центре", R.drawable.ic_add, true, v -> { closeSheet(); newPointAtCenter(); }),
                hChip("Экспорт KML", R.drawable.ic_share, false, v -> { closeSheet(); exportPoints(); }));
        if (userPoints.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Пока нет точек.\nНажмите на прицел в центре карты — точка встанет туда. Или подержите палец на нужном месте.");
            t.setTextColor(getColor(R.color.hud_text2));
            t.setTextSize(13.5f);
            t.setPadding((int) dp(4), (int) dp(16), (int) dp(4), (int) dp(8));
            sheetContent.addView(t);
            return;
        }
        hSection("БЛИЖАЙШИЕ К ЦЕНТРУ КАРТЫ");
        GeoPoint c = (GeoPoint) map.getMapCenter();
        final List<UserPoints.Point> sorted = new ArrayList<>(userPoints);
        final double cLat = c.getLatitude(), cLon = c.getLongitude();
        Collections.sort(sorted, (a, b) -> Double.compare(
                Geo.inverse(cLat, cLon, a.lat, a.lon)[0], Geo.inverse(cLat, cLon, b.lat, b.lon)[0]));
        float den = getResources().getDisplayMetrics().density;
        for (UserPoints.Point p : sorted) {
            double[] inv = Geo.inverse(cLat, cLon, p.lat, p.lon);
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(p.color, den, p.icon));
            pin.setLayoutParams(new LinearLayout.LayoutParams((int) dp(26), (int) dp(35)));
            ImageView edit = hIconButton(R.drawable.ic_info, getColor(R.color.hud_text2), v -> editPoint(p, p.lat, p.lon, null));
            hRow(pin, p.name, Geo.dist(inv[0]) + " · " + String.format(Locale.US, "%.0f°", inv[1]) + " · "
                    + Sk42.format(p.lat, p.lon), edit, v -> {
                closeSheet();
                map.getController().animateTo(new GeoPoint(p.lat, p.lon), Math.max(13.0, map.getZoomLevelDouble()), 800L);
            });
        }
    }

    private void showExport() {
        sheet("Экспорт");
        int tx = getColor(R.color.hud_text);
        hSection("МОИ ТОЧКИ · " + userPoints.size());
        hRow(hIcon(R.drawable.ic_share, tx, 22), "Точки в KML", "AlpineQuest, Google Earth", null, v -> { closeSheet(); exportPoints(); });
        hRow(hIcon(R.drawable.ic_gpx, tx, 22), "Точки в GPX", "Навигаторы, OsmAnd, Locus", null, v -> {
            closeSheet();
            if (userPoints.isEmpty()) { Toast.makeText(this, "Нет точек", Toast.LENGTH_SHORT).show(); return; }
            saveAndShare("Tochki_" + new SimpleDateFormat("dd_MM_yyyy", Locale.US).format(new Date()) + ".gpx",
                    UserPoints.toGpx(userPoints), "Отправить точки", "application/gpx+xml");
        });
        hSection("МАРШРУТЫ · " + routes.size());
        hRow(hIcon(R.drawable.ic_route, tx, 22), "Все маршруты в KML", "Линии и контрольные точки", null, v -> {
            closeSheet();
            if (routes.isEmpty()) { Toast.makeText(this, "Нет маршрутов", Toast.LENGTH_SHORT).show(); return; }
            saveAndShare("Marshruty_" + new SimpleDateFormat("dd_MM_yyyy", Locale.US).format(new Date()) + ".kml",
                    Routes.toKmlAll(routes), "Отправить маршруты");
        });
        hRow(hIcon(R.drawable.ic_gpx, tx, 22), "Все маршруты в GPX", "Для навигаторов", null, v -> {
            closeSheet();
            if (routes.isEmpty()) { Toast.makeText(this, "Нет маршрутов", Toast.LENGTH_SHORT).show(); return; }
            saveAndShare("Marshruty_" + new SimpleDateFormat("dd_MM_yyyy", Locale.US).format(new Date()) + ".gpx",
                    Routes.toGpx(routes), "Отправить маршруты", "application/gpx+xml");
        });
        hSection("ЛИНИЯ ФРОНТА");
        hRow(hIcon(R.drawable.ic_lbs, 0xFFFF5A4E, 22), "Файл ЛБС (KML)", lbsDate.isEmpty() ? "не загружена" : "снимок " + lbsDate,
                null, v -> { closeSheet(); openLbsFile(true); });
        TextView note = new TextView(this);
        note.setText("Файлы сохраняются в папку «Загрузки» и сразу открывается окно «Отправить».");
        note.setTextColor(getColor(R.color.hud_text2));
        note.setTextSize(12);
        note.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(note);
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
        saveAndShare(name, kml, title, LbsCore.KML_MIME);
    }

    private void saveAndShare(String name, String kml, String title, String mime) {
        new Thread(() -> {
            try {
                Uri uri = LbsCore.saveToDownloads(getApplicationContext(), name, kml.getBytes(StandardCharsets.UTF_8),
                        mime.equals(LbsCore.KML_MIME) ? mime : "application/octet-stream");
                runOnUiThread(() -> {
                    Toast.makeText(this, name + " сохранён в «Загрузки»", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime);
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
        findViewById(R.id.centerMark).setVisibility(View.GONE);
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
        findViewById(R.id.centerMark).setVisibility(View.VISIBLE);
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
            total.setText("—");
            sub.setText("Нажмите на карту или ⊕ — первая точка. Дальше двигайте карту: линия тянется к прицелу");
            return;
        }
        List<Routes.Leg> legs = Routes.legs(toolPts);
        double len = legs.isEmpty() ? 0 : legs.get(legs.size() - 1).total;
        total.setText(legs.isEmpty() ? "—" : Geo.dist(len));
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
        sheet(editingRoute != null ? "Изменить маршрут" : "Сохранить маршрут");
        final EditText name = hInput("Название маршрута",
                editingRoute != null ? editingRoute.name : "Маршрут " + (routes.size() + 1));
        name.setSingleLine(true);
        name.setSelectAllOnFocus(true);
        sheetContent.addView(name);

        LinearLayout info = hCard();
        TextView t = new TextView(this);
        t.setText(Geo.dist(Routes.length(toolPts)) + " · " + (toolPts.size() - 1) + " уч. · " + toolPts.size() + " точек");
        t.setTextColor(getColor(R.color.hud_text));
        t.setTextSize(14);
        info.addView(t);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        il.topMargin = (int) dp(10);
        sheetContent.addView(info, il);

        hSection("ЦВЕТ ЛИНИИ");
        final int[] chosen = {editingRoute != null ? editingRoute.color : Routes.COLORS[0]};
        LinearLayout colors = new LinearLayout(this);
        final List<View> sw = new ArrayList<>();
        for (int i = 0; i < Routes.COLORS.length; i++) {
            final int col = Routes.COLORS[i];
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(34), 1);
            lp.setMarginEnd((int) dp(8));
            v.setOnClickListener(x -> {
                chosen[0] = col;
                for (int k = 0; k < sw.size(); k++) sw.get(k).setBackground(swatchHud(Routes.COLORS[k], Routes.COLORS[k] == col));
            });
            v.setBackground(swatchHud(col, col == chosen[0]));
            sw.add(v);
            colors.addView(v, lp);
        }
        sheetContent.addView(colors);

        footer("Отмена", "Сохранить", v -> {
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
            closeSheet();
            finishTool();
        });
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

    private View routeSwatch(int color) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(3));
        g.setColor(color);
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams((int) dp(26), (int) dp(6)));
        return v;
    }

    private void showRoutes() {
        sheet("Маршруты" + (routes.isEmpty() ? "" : " · " + routes.size()));
        hChipRow(hChip("Новый маршрут", R.drawable.ic_add, true, v -> { closeSheet(); startTool(TOOL_ROUTE, null); }));
        if (routes.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Маршрутов пока нет.\nНажмите «Новый маршрут» и ставьте точки на карте — "
                    + "получите длину участков, азимуты и дирекционные углы.");
            t.setTextColor(getColor(R.color.hud_text2));
            t.setTextSize(13.5f);
            t.setPadding((int) dp(4), (int) dp(16), (int) dp(4), (int) dp(8));
            sheetContent.addView(t);
            return;
        }
        hSection("СОХРАНЁННЫЕ");
        for (Routes.Route r : routes) {
            final ImageView eye = hIconButton(R.drawable.ic_eye,
                    r.visible ? getColor(R.color.hud_accent) : getColor(R.color.hud_text2), null);
            eye.setOnClickListener(v -> {
                r.visible = !r.visible;
                saveRoutes();
                eye.setImageTintList(ColorStateList.valueOf(r.visible ? getColor(R.color.hud_accent) : getColor(R.color.hud_text2)));
            });
            hRow(routeSwatch(r.color), r.name, Geo.dist(Routes.length(r.pts)) + " · "
                    + Math.max(0, r.pts.size() - 1) + " уч.", eye, v -> routeActions(r));
        }
    }

    private void routeActions(Routes.Route r) {
        sheet(r.name);
        LinearLayout info = hCard();
        TextView t = new TextView(this);
        List<Routes.Leg> legs = Routes.legs(r.pts);
        StringBuilder sb = new StringBuilder(Geo.dist(Routes.length(r.pts))).append(" · ").append(legs.size()).append(" уч.");
        if (!legs.isEmpty())
            sb.append("\nПервый участок: ").append(String.format(Locale.US, "%.1f°", legs.get(0).az))
              .append(" (").append(Geo.mils(legs.get(0).grid)).append(")");
        t.setText(sb);
        t.setTextColor(getColor(R.color.hud_text));
        t.setTextSize(13.5f);
        info.addView(t);
        sheetContent.addView(info);
        int tx = getColor(R.color.hud_text);
        hSection("ДЕЙСТВИЯ");
        hRow(hIcon(R.drawable.ic_eye, tx, 22), "Показать на карте", null, null, v -> { closeSheet(); fitRoute(r); });
        hRow(hIcon(R.drawable.ic_route, tx, 22), "Изменить точки", null, null, v -> { closeSheet(); startTool(TOOL_ROUTE, r); });
        hRow(hIcon(R.drawable.ic_table, tx, 22), "Таблица участков", "Длины, азимуты, дир. углы, координаты", null, v -> {
            closeSheet();
            List<double[]> keep = new ArrayList<>(toolPts);
            toolPts.clear();
            toolPts.addAll(r.pts);
            toolTable();
            toolPts.clear();
            toolPts.addAll(keep);
        });
        hRow(hIcon(R.drawable.ic_mountain, tx, 22), "Профиль высот", "Подъёмы, спуски, перепад", null, v -> showProfile(r.pts, r.name));
        hRow(hIcon(R.drawable.ic_share, tx, 22), "Экспорт в KML", "Для AlpineQuest", null, v -> {
            closeSheet();
            saveAndShare(r.name.replaceAll("[^A-Za-z0-9А-Яа-яЁё_-]", "_") + ".kml", Routes.toKml(r), "Отправить маршрут");
        });
        hRow(hIcon(R.drawable.ic_delete, 0xFFFF6B60, 22), "Удалить маршрут", null, null, v -> new AlertDialog.Builder(this)
                .setTitle("Удалить «" + r.name + "»?")
                .setPositiveButton("Удалить", (d2, w2) -> {
                    routes.remove(r);
                    saveRoutes();
                    showRoutes();
                })
                .setNegativeButton("Отмена", null)
                .show());
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

    // ================= Меню (выезжающая панель) =================

    private void setupMenu() {
        findViewById(R.id.menuScrim).setOnClickListener(v -> openMenu(false));
        findViewById(R.id.menuClose).setOnClickListener(v -> openMenu(false));
        findViewById(R.id.lbsUpdate).setOnClickListener(v -> startLbsUpdate());
        findViewById(R.id.lbsOpen).setOnClickListener(v -> openLbsFile(false));
        findViewById(R.id.lbsShare).setOnClickListener(v -> openLbsFile(true));
        findViewById(R.id.tileLayers).setOnClickListener(v -> { openMenu(false); showLayers(); });
        findViewById(R.id.tileImport).setOnClickListener(v -> { openMenu(false); pickFile(REQ_IMPORT); });
        findViewById(R.id.tileBackup).setOnClickListener(v -> { openMenu(false); makeBackup(); });
        findViewById(R.id.tileRestore).setOnClickListener(v -> { openMenu(false); pickFile(REQ_RESTORE); });
        findViewById(R.id.updateRow).setOnClickListener(v -> downloadUpdate());
        findViewById(R.id.pinRow).setOnClickListener(v -> { openMenu(false); showPinSettings(); });
        updatePinRow();
        Switch swOff = findViewById(R.id.swOffline), swNight = findViewById(R.id.swNight), swTrack = findViewById(R.id.swTrack);
        swOff.setChecked(offline());
        swNight.setChecked(prefs.getBoolean("night", false));
        swTrack.setChecked(false);
        swOff.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("offline", on).apply();
            applyOffline();
            Toast.makeText(this, on ? "Без сети: только сохранённые карты и высоты" : "Сеть включена", Toast.LENGTH_SHORT).show();
        });
        swNight.setOnCheckedChangeListener((b, on) -> { prefs.edit().putBoolean("night", on).apply(); applyNight(); });
        swTrack.setOnCheckedChangeListener((b, on) -> setTracking(on));
        findViewById(R.id.tileOffline).setOnClickListener(v -> { openMenu(false); chooseDownload(); });
        findViewById(R.id.tileRoutes).setOnClickListener(v -> { openMenu(false); showRoutes(); });
        findViewById(R.id.tilePoints).setOnClickListener(v -> { openMenu(false); showExport(); });
        findViewById(R.id.tileInfo).setOnClickListener(v -> { openMenu(false); showInfo(); });
        findViewById(R.id.tileAbout).setOnClickListener(v -> { openMenu(false); showAbout(); });

        Switch swAuto = findViewById(R.id.swAuto), swDiff = findViewById(R.id.swDiff), swGray = findViewById(R.id.swGray);
        swAuto.setChecked(LbsCore.autoEnabled(this));
        swDiff.setChecked(LbsCore.optDiff(this));
        swGray.setChecked(LbsCore.optGray(this));
        swAuto.setOnCheckedChangeListener((b, on) -> {
            try {
                LbsCore.schedule(this, on);
            } catch (Throwable e) {
                Toast.makeText(this, "Автопроверка не включилась", Toast.LENGTH_LONG).show();
            }
        });
        swDiff.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("optDiff", on).apply());
        swGray.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("optGray", on).apply());
        try {
            ((TextView) findViewById(R.id.menuVersion)).setText("Карта линии фронта · v"
                    + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
        }
    }

    private boolean menuOpen() {
        return findViewById(R.id.menuSheet).getVisibility() == View.VISIBLE;
    }

    private void openMenu(boolean open) {
        View sheet = findViewById(R.id.menuSheet), panel = findViewById(R.id.menuPanel), scrim = findViewById(R.id.menuScrim);
        if (open) {
            updateLbsCard();
            sheet.setVisibility(View.VISIBLE);
            panel.setTranslationY(dp(500));
            scrim.setAlpha(0f);
            panel.animate().translationY(0).setDuration(220).start();
            scrim.animate().alpha(1f).setDuration(220).start();
        } else {
            panel.animate().translationY(dp(600)).setDuration(180).withEndAction(() -> sheet.setVisibility(View.GONE)).start();
            scrim.animate().alpha(0f).setDuration(180).start();
        }
    }

    /** Лампочка и статус ЛБС: зелёная — свежая, жёлтая — обновляется/давно, красная — нет данных/ошибка. */
    private void updateLbsCard() {
        View lamp = findViewById(R.id.lbsLamp);
        TextView st = findViewById(R.id.lbsStatus);
        TextView btn = findViewById(R.id.lbsUpdate);
        String err = prefs.getString("lastError", null);
        long checked = prefs.getLong("lastCheck", 0);
        int color;
        String text;
        if (lbsRunning) {
            color = 0xFFFFB300;
            text = lbsProgress;
        } else if (lbsDate.isEmpty()) {
            color = 0xFFE53935;
            text = err != null ? "Ошибка: " + err : "Не загружена — нажмите «Обновить»";
        } else if (err != null) {
            color = 0xFFE53935;
            text = "Снимок " + lbsDate + " · ошибка обновления";
        } else if (System.currentTimeMillis() - checked > 24L * 3600 * 1000) {
            color = 0xFFFFB300;
            text = "Снимок " + lbsDate + " · давно не проверялась";
        } else {
            color = 0xFF43A047;
            text = "Снимок " + lbsDate + " · проверено "
                    + android.text.format.DateFormat.format("dd.MM HH:mm", checked);
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke((int) dp(3), (color & 0x00FFFFFF) | 0x55000000);
        lamp.setBackground(g);
        lamp.animate().cancel();
        lamp.setAlpha(1f);
        if (lbsRunning) pulse(lamp);
        st.setText(text);
        btn.setEnabled(!lbsRunning);
        btn.setAlpha(lbsRunning ? 0.5f : 1f);
        btn.setText(lbsRunning ? "Обновляю…" : "Обновить");
        boolean has = prefs.getString("lastUri", null) != null;
        findViewById(R.id.lbsOpen).setEnabled(has);
        findViewById(R.id.lbsShare).setEnabled(has);
        findViewById(R.id.lbsOpen).setAlpha(has ? 1f : 0.4f);
        findViewById(R.id.lbsShare).setAlpha(has ? 1f : 0.4f);
        findViewById(R.id.menuBadge).setVisibility(color == 0xFFE53935 ? View.VISIBLE : View.GONE);
    }

    private void pulse(View v) {
        v.animate().alpha(0.25f).setDuration(500).withEndAction(() ->
                v.animate().alpha(1f).setDuration(500).withEndAction(() -> {
                    if (lbsRunning) pulse(v);
                }).start()).start();
    }

    private void openLbsFile(boolean share) {
        String u = prefs.getString("lastUri", null);
        if (u == null) return;
        Uri uri = Uri.parse(u);
        Intent i = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
        if (share) {
            i.setType(LbsCore.KML_MIME);
            i.putExtra(Intent.EXTRA_STREAM, uri);
        } else {
            i.setDataAndType(uri, LbsCore.KML_MIME);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, share ? "Отправить файл ЛБС" : "Открыть в…"));
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для KML (установите AlpineQuest)", Toast.LENGTH_LONG).show();
        }
    }

    // ---- загрузка ЛБС прямо с карты (скрытый браузер)

    private android.webkit.WebView lbsWeb;
    private String lbsScript;
    private boolean lbsRunning;
    private int lbsTries;
    private String lbsProgress = "";
    private final android.os.Handler lbsHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void setupLbsWeb() {
        lbsWeb = findViewById(R.id.lbsWeb);
        try {
            lbsScript = LbsCore.readAsset(this, "lbs.js");
        } catch (Exception e) {
            lbsScript = null;
        }
        android.webkit.WebSettings ws = lbsWeb.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));
        lbsWeb.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                if (lbsRunning) lbsHandler.postDelayed(MapActivity.this::lbsExtract, 1500);
            }
        });
    }

    private void startLbsUpdate() {
        if (lbsRunning || lbsScript == null) return;
        if (offline()) {
            Toast.makeText(this, "Включён режим «Без сети» — выключите его в меню", Toast.LENGTH_LONG).show();
            return;
        }
        lbsRunning = true;
        lbsTries = 0;
        lbsProgress = "Загружаю данные…";
        updateLbsCard();
        lbsWeb.loadUrl(LbsCore.SOURCE_URL);
        lbsHandler.postDelayed(() -> {
            if (lbsRunning) lbsFail("сайт не ответил");
        }, 90_000);
    }

    private void lbsExtract() {
        if (!lbsRunning) return;
        lbsWeb.evaluateJavascript(lbsScript, raw -> {
            if (!lbsRunning) return;
            String res = LbsCore.decode(raw);
            if (res == null || res.startsWith("WAIT")) {
                if (++lbsTries < 20) {
                    lbsProgress = "Обновляю данные…";
                    updateLbsCard();
                    lbsHandler.postDelayed(this::lbsExtract, 2000);
                } else {
                    lbsFail("сайт не отдал данные");
                }
                return;
            }
            if (res.startsWith("ERR")) {
                lbsFail(res.substring(3).trim());
                return;
            }
            lbsProgress = "Обрабатываю данные…";
            updateLbsCard();
            final String data = res;
            new Thread(() -> {
                try {
                    LbsCore.Result r = LbsCore.buildAndSave(getApplicationContext(), data);
                    boolean isNew = LbsCore.remember(getApplicationContext(), r);
                    runOnUiThread(() -> {
                        lbsRunning = false;
                        lbsHandler.removeCallbacksAndMessages(null);
                        prefs.edit().remove("lastError").apply();
                        forceReloadLbs();
                        updateLbsCard();
                        Toast.makeText(this, isNew ? "ЛБС обновлена: снимок " + r.date.replace(" o ", ", ")
                                : "Новых данных нет — ЛБС актуальна", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> lbsFail("не удалось сохранить файл"));
                }
            }).start();
        });
    }

    private void lbsFail(String why) {
        lbsRunning = false;
        lbsHandler.removeCallbacksAndMessages(null);
        prefs.edit().putString("lastError", why).apply();
        updateLbsCard();
        Toast.makeText(this, "ЛБС не обновилась: " + why, Toast.LENGTH_LONG).show();
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
        sheet("Офлайн-карты");
        LinearLayout info = hCard();
        addKV(info, "Сохранено на телефоне", mb(tileDb().length()));
        addKV(info, "Подложка", STYLE_NAMES[currentStyle]);
        addKV(info, "Область", "то, что видно на экране");
        sheetContent.addView(info);

        final CacheManager cm;
        try {
            cm = new CacheManager(map);
        } catch (Exception e) {
            TextView t = new TextView(this);
            t.setText("Правила этого источника запрещают массовое скачивание. Для офлайна выберите «Спутник» или «Топо» "
                    + "(или «Схему» с русскими подписями). Просмотренные места «Рельефа» всё равно сохраняются.");
            t.setTextColor(0xFFFF8A80);
            t.setTextSize(13);
            t.setPadding((int) dp(4), (int) dp(12), (int) dp(4), 0);
            sheetContent.addView(t);
            return;
        }
        final BoundingBox bb = map.getBoundingBox();
        final int zMin = 8;
        int maxZ = (int) map.getTileProvider().getTileSource().getMaximumZoomLevel();
        final int[] zMaxes = {15, 16, 17, 18};
        final String[] detail = {"обзор, дороги", "сёла, посадки", "улицы, дворы", "максимум, дома"};
        hSection("СКАЧАТЬ ВИДИМУЮ ОБЛАСТЬ");
        for (int i = 0; i < zMaxes.length; i++) {
            final int z = Math.min(zMaxes[i], maxZ);
            if (i > 0 && z == Math.min(zMaxes[i - 1], maxZ)) continue;
            final int cnt = cm.possibleTilesInArea(bb, zMin, z);
            boolean tooBig = cnt > MAX_TILES;
            TextView size = new TextView(this);
            size.setText(tooBig ? "много" : mb((double) cnt * tileBytes()));
            size.setTextColor(tooBig ? 0xFFFF8A80 : getColor(R.color.hud_text));
            size.setTextSize(13.5f);
            size.setPadding((int) dp(8), 0, (int) dp(8), 0);
            hRow(hIcon(R.drawable.ic_download, tooBig ? getColor(R.color.hud_text2) : getColor(R.color.hud_accent), 22),
                    "До уровня " + z, detail[i] + (tooBig ? " · приблизьте карту" : ""), size, v -> {
                        if (tooBig) {
                            Toast.makeText(this, "Слишком большая область — приблизьте карту или выберите уровень ниже",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        closeSheet();
                        startDownload(cm, bb, zMin, z, cnt);
                    });
        }
        TextView note = new TextView(this);
        note.setText("Скачанное открывается мгновенно и работает без интернета. Большие районы лучше качать частями.");
        note.setTextColor(getColor(R.color.hud_text2));
        note.setTextSize(12);
        note.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(note);
        hChipRow(hChip("Очистить сохранённые карты", R.drawable.ic_delete, false, v -> { closeSheet(); confirmClear(); }));
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
        if (locMgr != null) locMgr.removeUpdates(locListener);
        if (track != null) setTracking(false);
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
            View mp = findViewById(R.id.menuPanel);
            mp.setPadding(mp.getPaddingLeft(), mp.getPaddingTop(), mp.getPaddingRight(), (int) dp(16) + b);
            int ime = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
            View gp = findViewById(R.id.genPanel);
            gp.setPadding(gp.getPaddingLeft(), gp.getPaddingTop(), gp.getPaddingRight(), (int) dp(14) + Math.max(b, ime));
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
        if (findViewById(R.id.lockView).getVisibility() == View.VISIBLE) {
            moveTaskToBack(true);
            return;
        }
        if (sheetOpen()) {
            closeSheet();
            return;
        }
        if (menuOpen()) {
            openMenu(false);
            return;
        }
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
        if (compassMode && sensors != null) {
            android.hardware.Sensor rv = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
            if (rv != null) sensors.registerListener(compassListener, rv, android.hardware.SensorManager.SENSOR_DELAY_UI);
        }
        reloadLbs();
        showSavedCrash();
        maybeLock();
    }

    @Override
    protected void onStop() {
        lastStop = System.currentTimeMillis();
        if (track != null) saveRoutes();
        super.onStop();
    }

    @Override
    protected void onPause() {
        if (sensors != null) sensors.unregisterListener(compassListener);
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
        lbsHandler.removeCallbacksAndMessages(null);
        if (lbsWeb != null) lbsWeb.destroy();
        if (dlTask != null) dlTask.cancel(true);
        if (map != null) map.onDetach();
        super.onDestroy();
    }

    // ================= Моё местоположение (GPS) и трек =================

    private static final int REQ_LOC = 21, REQ_IMPORT = 31, REQ_RESTORE = 32;
    private MyLocationOverlay myLoc;
    private android.location.LocationManager locMgr;
    private boolean locOn, follow, pendingCenter;
    private Routes.Route track;

    private final android.location.LocationListener locListener = new android.location.LocationListener() {
        @Override public void onLocationChanged(android.location.Location l) { onLocation(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {
            Toast.makeText(MapActivity.this, "GPS выключен в настройках телефона", Toast.LENGTH_SHORT).show();
        }
    };

    private boolean hasLocPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void onMyLocClick() {
        if (!locOn) {
            startLocation(true);
            return;
        }
        android.location.Location l = myLoc.getLocation();
        if (l == null) {
            Toast.makeText(this, "Ищу спутники… Выйдите на открытое место", Toast.LENGTH_SHORT).show();
            return;
        }
        follow = true;
        styleMyLoc();
        map.getController().animateTo(new GeoPoint(l.getLatitude(), l.getLongitude()), Math.max(15.0, map.getZoomLevelDouble()), 600L);
    }

    private void startLocation(boolean center) {
        if (!hasLocPerm()) {
            pendingCenter = center;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        locMgr = getSystemService(android.location.LocationManager.class);
        if (locMgr == null) return;
        if (!locMgr.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))
            Toast.makeText(this, "Включите геолокацию (GPS) в настройках телефона", Toast.LENGTH_LONG).show();
        try {
            locMgr.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 1f, locListener, android.os.Looper.getMainLooper());
            android.location.Location last = locMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            locOn = true;
            follow = center;
            prefs.edit().putBoolean("locOn", true).apply();
            if (last != null && System.currentTimeMillis() - last.getTime() < 10 * 60 * 1000) onLocation(last);
            else Toast.makeText(this, "Ищу спутники…", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "Нет разрешения на геолокацию", Toast.LENGTH_LONG).show();
        }
        styleMyLoc();
    }

    private void stopLocation() {
        if (track != null) setTracking(false);
        if (locMgr != null) locMgr.removeUpdates(locListener);
        locOn = false;
        follow = false;
        myLoc.setLocation(null);
        prefs.edit().putBoolean("locOn", false).apply();
        styleMyLoc();
        updateCoords();
        map.invalidate();
        Toast.makeText(this, "Местоположение выключено", Toast.LENGTH_SHORT).show();
    }

    private void styleMyLoc() {
        int c = !locOn ? getColor(R.color.hud_text) : follow ? 0xFF42A5F5 : 0xFF90CAF9;
        ((ImageView) findViewById(R.id.btnMyLoc)).setImageTintList(ColorStateList.valueOf(c));
        findViewById(R.id.btnMyLoc).setBackgroundResource(locOn && follow ? R.drawable.bg_round_blue : R.drawable.bg_round_hud);
    }

    private void onLocation(android.location.Location l) {
        myLoc.setLocation(l);
        if (follow) map.getController().animateTo(new GeoPoint(l.getLatitude(), l.getLongitude()));
        if (track != null) appendTrack(l);
        updateCoords();
        map.invalidate();
    }

    private void setTracking(boolean on) {
        if (on) {
            if (!locOn) startLocation(false);
            track = new Routes.Route();
            track.id = Long.toString(System.currentTimeMillis(), 36);
            track.created = System.currentTimeMillis();
            track.name = "Трек " + new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date());
            track.color = 0xFFFF4081;
            routes.add(track);
            saveRoutes();
            findViewById(R.id.trackDot).setVisibility(View.VISIBLE);
            Toast.makeText(this, "Запись трека идёт, пока приложение открыто", Toast.LENGTH_LONG).show();
        } else {
            if (track != null) {
                if (track.pts.size() < 2) {
                    routes.remove(track);
                    Toast.makeText(this, "Трек слишком короткий — не сохранён", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Трек сохранён: " + Geo.dist(Routes.length(track.pts)), Toast.LENGTH_LONG).show();
                }
                saveRoutes();
            }
            track = null;
            findViewById(R.id.trackDot).setVisibility(View.GONE);
        }
        Switch sw = findViewById(R.id.swTrack);
        if (sw.isChecked() != on) {
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(on);
            sw.setOnCheckedChangeListener((b, v) -> setTracking(v));
        }
    }

    private void appendTrack(android.location.Location l) {
        if (l.getAccuracy() > 40) return;
        if (!track.pts.isEmpty()) {
            double[] p = track.pts.get(track.pts.size() - 1);
            if (Geo.inverse(p[0], p[1], l.getLatitude(), l.getLongitude())[0] < 8) return;
        }
        track.pts.add(new double[]{l.getLatitude(), l.getLongitude()});
        if (track.pts.size() % 5 == 0) saveRoutes();
        else pathOverlay.setSaved(routes);
    }

    // ================= Профиль высот =================

    private void showProfile(List<double[]> pts, String title) {
        if (pts.size() < 2) {
            Toast.makeText(this, "Нужно минимум 2 точки", Toast.LENGTH_SHORT).show();
            return;
        }
        sheet("Профиль высот · " + title);
        final ProfileView pv = new ProfileView(this);
        sheetContent.addView(pv);
        LinearLayout card = hCard();
        final TextView st = new TextView(this);
        st.setText(offline() ? "Считаю по сохранённым высотам…" : "Загружаю высоты…");
        st.setTextColor(getColor(R.color.hud_text));
        st.setTextSize(13.5f);
        st.setLineSpacing(dp(3), 1f);
        card.addView(st);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(10);
        sheetContent.addView(card, lp);
        TextView hint = new TextView(this);
        hint.setText("Проведите пальцем по графику — высота и расстояние в этом месте.");
        hint.setTextColor(getColor(R.color.hud_text2));
        hint.setTextSize(12);
        hint.setPadding((int) dp(4), (int) dp(8), (int) dp(4), 0);
        sheetContent.addView(hint);

        final List<double[]> copy = new ArrayList<>(pts);
        new Thread(() -> {
            int n = copy.size();
            double[] cum = new double[n];
            for (int i = 1; i < n; i++)
                cum[i] = cum[i - 1] + Geo.inverse(copy.get(i - 1)[0], copy.get(i - 1)[1], copy.get(i)[0], copy.get(i)[1])[0];
            double total = cum[n - 1];
            int samples = 160;
            double[] d = new double[samples], e = new double[samples];
            int seg = 1;
            for (int k = 0; k < samples; k++) {
                double at = total * k / (samples - 1);
                while (seg < n - 1 && cum[seg] < at) seg++;
                double span = cum[seg] - cum[seg - 1];
                double t = span <= 0 ? 0 : (at - cum[seg - 1]) / span;
                double lat = copy.get(seg - 1)[0] + (copy.get(seg)[0] - copy.get(seg - 1)[0]) * t;
                double lon = copy.get(seg - 1)[1] + (copy.get(seg)[1] - copy.get(seg - 1)[1]) * t;
                d[k] = at;
                e[k] = Elevation.sync(getApplicationContext(), lat, lon);
            }
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, up = 0, down = 0, ref = Double.NaN;
            int known = 0;
            for (double v : e) {
                if (Double.isNaN(v)) continue;
                known++;
                min = Math.min(min, v);
                max = Math.max(max, v);
                if (Double.isNaN(ref)) ref = v;
                else if (v - ref > 3) { up += v - ref; ref = v; }
                else if (ref - v > 3) { down += ref - v; ref = v; }
            }
            final String text;
            if (known == 0) {
                text = offline() ? "Высоты для этого района не сохранены. Выключите режим «Без сети» и откройте профиль ещё раз."
                        : "Не удалось загрузить высоты. Проверьте интернет.";
            } else {
                double first = Double.NaN, last = Double.NaN;
                for (double v : e) if (!Double.isNaN(v)) { if (Double.isNaN(first)) first = v; last = v; }
                text = "Длина: " + Geo.dist(total) + "\n"
                        + "Начало " + Math.round(first) + " м → конец " + Math.round(last) + " м  ("
                        + (last - first >= 0 ? "+" : "") + Math.round(last - first) + " м)\n"
                        + "Мин " + Math.round(min) + " м · макс " + Math.round(max) + " м · перепад " + Math.round(max - min) + " м\n"
                        + "Набор ↑ " + Math.round(up) + " м · сброс ↓ " + Math.round(down) + " м";
            }
            runOnUiThread(() -> {
                pv.setData(d, e);
                st.setText(text);
            });
        }).start();
    }

    // ================= Поделиться точкой текстом =================

    private void sharePointText(String name, double lat, double lon) {
        Elevation.get(this, lat, lon, h -> {
            double[] r = Sk42.fromWgs(lat, lon);
            String txt = name + "\n"
                    + "СК-42: X " + Math.round(r[0]) + "  Y " + Math.round(r[1]) + " (зона " + (int) r[2] + ")\n"
                    + String.format(Locale.US, "WGS-84: %.6f, %.6f", lat, lon)
                    + (Double.isNaN(h) ? "" : "\nВысота: " + Math.round(h) + " м");
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, txt);
            startActivity(Intent.createChooser(i, "Поделиться точкой"));
        });
    }

    // ================= Режимы: без сети, ночь =================

    private boolean offline() {
        return prefs.getBoolean("offline", false);
    }

    private void applyOffline() {
        boolean off = offline();
        map.setUseDataConnection(!off);
        Elevation.offline = off;
        findViewById(R.id.offlineChip).setVisibility(off ? View.VISIBLE : View.GONE);
        map.invalidate();
    }

    private void applyNight() {
        boolean on = prefs.getBoolean("night", false);
        findViewById(R.id.nightFilter).setVisibility(on ? View.VISIBLE : View.GONE);
        android.graphics.ColorMatrix cm = null;
        if (on) {
            cm = new android.graphics.ColorMatrix(new float[]{
                    0.40f, 0.40f, 0.12f, 0, 0,
                    0.06f, 0.10f, 0.02f, 0, 0,
                    0.02f, 0.04f, 0.02f, 0, 0,
                    0, 0, 0, 1, 0});
        }
        map.getOverlayManager().getTilesOverlay().setColorFilter(cm == null ? null : new android.graphics.ColorMatrixColorFilter(cm));
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.12f : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        map.invalidate();
    }

    // ================= Защита ПИН-кодом =================

    private long lastStop = 0;
    private boolean unlocked = false;
    private final StringBuilder pinInput = new StringBuilder();
    private int pinFails = 0;
    private long pinBlockedUntil = 0;

    private boolean pinSet() {
        return prefs.contains("pinHash");
    }

    private static String sha(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void setupLock() {
        int[] ids = {R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4, R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9};
        for (int i = 0; i < ids.length; i++) {
            final String digit = Integer.toString(i);
            findViewById(ids[i]).setOnClickListener(v -> pinKey(digit));
        }
        findViewById(R.id.keyDel).setOnClickListener(v -> {
            if (pinInput.length() > 0) pinInput.setLength(pinInput.length() - 1);
            showPinDots();
        });
        findViewById(R.id.keyBio).setOnClickListener(v -> askBiometric());
    }

    private void maybeLock() {
        if (!pinSet()) {
            findViewById(R.id.lockView).setVisibility(View.GONE);
            return;
        }
        if (unlocked && (lastStop == 0 || System.currentTimeMillis() - lastStop < 30_000)) return;
        if (!unlocked && findViewById(R.id.lockView).getVisibility() == View.VISIBLE) return;
        unlocked = false;
        pinInput.setLength(0);
        ((TextView) findViewById(R.id.lockTitle)).setText("Введите ПИН-код");
        showPinDots();
        boolean bio = prefs.getBoolean("pinBio", false) && bioAvailable();
        findViewById(R.id.keyBio).setVisibility(bio ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.lockView).setVisibility(View.VISIBLE);
        hideKeyboard();
        if (bio) askBiometric();
    }

    private void showPinDots() {
        int len = prefs.getInt("pinLen", 4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(i < pinInput.length() ? "●" : "○");
        ((TextView) findViewById(R.id.lockDots)).setText(sb);
    }

    private void pinKey(String d) {
        if (System.currentTimeMillis() < pinBlockedUntil) {
            ((TextView) findViewById(R.id.lockTitle)).setText("Подождите " + (pinBlockedUntil - System.currentTimeMillis()) / 1000 + " с");
            return;
        }
        int len = prefs.getInt("pinLen", 4);
        if (pinInput.length() >= len) return;
        pinInput.append(d);
        showPinDots();
        if (pinInput.length() == len) {
            if (sha(prefs.getString("pinSalt", "") + pinInput).equals(prefs.getString("pinHash", ""))) {
                unlock();
            } else {
                pinFails++;
                pinInput.setLength(0);
                if (pinFails >= 5) {
                    pinBlockedUntil = System.currentTimeMillis() + 30_000;
                    pinFails = 0;
                    ((TextView) findViewById(R.id.lockTitle)).setText("Слишком много попыток — 30 с");
                } else {
                    ((TextView) findViewById(R.id.lockTitle)).setText("Неверный ПИН-код");
                }
                findViewById(R.id.lockDots).animate().translationX(dp(12)).setDuration(60).withEndAction(() ->
                        findViewById(R.id.lockDots).animate().translationX(-dp(12)).setDuration(60).withEndAction(() ->
                                findViewById(R.id.lockDots).animate().translationX(0).setDuration(60).start()).start()).start();
                showPinDots();
            }
        }
    }

    private void unlock() {
        unlocked = true;
        lastStop = 0;
        pinFails = 0;
        pinInput.setLength(0);
        findViewById(R.id.lockView).animate().alpha(0f).setDuration(160).withEndAction(() -> {
            findViewById(R.id.lockView).setVisibility(View.GONE);
            findViewById(R.id.lockView).setAlpha(1f);
        }).start();
    }

    private boolean bioAvailable() {
        if (Build.VERSION.SDK_INT < 29) return false;
        android.hardware.biometrics.BiometricManager bm = getSystemService(android.hardware.biometrics.BiometricManager.class);
        return bm != null && bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void askBiometric() {
        if (!bioAvailable()) return;
        java.util.concurrent.Executor ex = getMainExecutor();
        android.hardware.biometrics.BiometricPrompt p = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Вход в ЛБС KML")
                .setNegativeButton("ПИН-код", ex, (d, w) -> { })
                .build();
        p.authenticate(new android.os.CancellationSignal(), ex, new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                unlock();
            }
        });
    }

    private void updatePinRow() {
        TextView t = findViewById(R.id.pinRow);
        t.setText(pinSet() ? "ПИН-код включён · изменить или выключить" : "Защита ПИН-кодом · выключена");
        android.graphics.drawable.Drawable dr = getDrawable(R.drawable.ic_lock).mutate();
        dr.setTint(pinSet() ? 0xFF66BB6A : getColor(R.color.hud_text2));
        int s = (int) dp(20);
        dr.setBounds(0, 0, s, s);
        t.setCompoundDrawables(dr, null, null, null);
    }

    private void showPinSettings() {
        sheet(pinSet() ? "Защита" : "Включить ПИН-код");
        final EditText p1 = hInput("Новый ПИН (4–8 цифр)", "");
        p1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        final EditText p2 = hInput("Повторите ПИН", "");
        p2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        sheetContent.addView(p1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(8);
        sheetContent.addView(p2, lp);
        if (bioAvailable()) {
            hToggle("Отпечаток пальца", "Разблокировка без ввода ПИН-кода", prefs.getBoolean("pinBio", false),
                    on -> prefs.edit().putBoolean("pinBio", on).apply());
        }
        TextView note = new TextView(this);
        note.setText("ПИН-код запрашивается при запуске и при возврате в приложение через 30 секунд. "
                + "Если забудете ПИН — придётся переустановить приложение (точки пропадут, сделайте резервную копию).");
        note.setTextColor(getColor(R.color.hud_text2));
        note.setTextSize(12);
        note.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(note);
        if (pinSet()) {
            hChipRow(hChip("Выключить защиту", R.drawable.ic_close, false, v -> {
                prefs.edit().remove("pinHash").remove("pinSalt").remove("pinLen").apply();
                updatePinRow();
                closeSheet();
                Toast.makeText(this, "Защита выключена", Toast.LENGTH_SHORT).show();
            }));
        }
        footer("Отмена", pinSet() ? "Сменить ПИН" : "Включить", v -> {
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < 4 || a.length() > 8) {
                Toast.makeText(this, "ПИН — от 4 до 8 цифр", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!a.equals(b)) {
                Toast.makeText(this, "ПИН-коды не совпадают", Toast.LENGTH_SHORT).show();
                return;
            }
            String salt = Long.toString(new java.security.SecureRandom().nextLong(), 36);
            prefs.edit().putString("pinSalt", salt).putString("pinHash", sha(salt + a)).putInt("pinLen", a.length()).apply();
            unlocked = true;
            updatePinRow();
            closeSheet();
            Toast.makeText(this, "ПИН-код установлен", Toast.LENGTH_SHORT).show();
        });
    }

    // ================= Проверка обновлений =================

    private void checkUpdates() {
        if (offline()) return;
        long last = prefs.getLong("updCheckedAt", 0);
        if (System.currentTimeMillis() - last < 6L * 3600 * 1000) {
            showUpdateRow();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(httpGet("https://api.github.com/repos/TRO9II/lbs-kml/releases/tags/latest"));
                String name = o.optString("name", "") + " " + o.optString("body", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("v(\\d+(?:\\.\\d+)+)").matcher(name);
                String ver = m.find() ? m.group(1) : "";
                prefs.edit().putLong("updCheckedAt", System.currentTimeMillis()).putString("updVer", ver).apply();
                runOnUiThread(this::showUpdateRow);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static int cmpVer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parseIntSafe(x[i]) : 0, q = i < y.length ? parseIntSafe(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean updateAvailable() {
        String v = prefs.getString("updVer", "");
        String mine = "";
        try {
            mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return !v.isEmpty() && !mine.isEmpty() && cmpVer(v, mine) > 0;
    }

    private void showUpdateRow() {
        boolean av = updateAvailable();
        View row = findViewById(R.id.updateRow);
        row.setVisibility(av ? View.VISIBLE : View.GONE);
        if (av) {
            ((TextView) findViewById(R.id.updateText)).setText("Доступна версия v" + prefs.getString("updVer", "") + " — скачать");
            findViewById(R.id.menuBadge).setVisibility(View.VISIBLE);
        }
    }

    private void downloadUpdate() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/TRO9II/lbs-kml/releases/download/latest/LbsKml.apk")));
        } catch (Exception e) {
            Toast.makeText(this, "Нет браузера для скачивания", Toast.LENGTH_LONG).show();
        }
    }

    // ================= Импорт и резервная копия =================

    private void pickFile(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для выбора файла", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_IMPORT) importFrom(uri);
        else if (req == REQ_RESTORE) restoreFrom(uri);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_LOC) {
            if (hasLocPerm()) startLocation(pendingCenter);
            else Toast.makeText(this, "Без разрешения на геолокацию «Моё местоположение» не работает", Toast.LENGTH_LONG).show();
        }
    }

    private byte[] readUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("файл не открылся");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int k;
            while ((k = in.read(buf)) > 0) {
                bo.write(buf, 0, k);
                if (bo.size() > 40 * 1024 * 1024) throw new Exception("файл слишком большой");
            }
            return bo.toByteArray();
        }
    }

    private void handleViewIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) importFrom(i.getData());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleViewIntent(intent);
    }

    private void importFrom(Uri uri) {
        new Thread(() -> {
            try {
                Importer.Result r = Importer.parse(readUri(uri), uri.getLastPathSegment());
                runOnUiThread(() -> showImport(r));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось прочитать файл (нужен KML или GPX): "
                        + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showImport(Importer.Result r) {
        sheet("Импорт");
        LinearLayout card = hCard();
        addKV(card, "Точек", Integer.toString(r.points.size()));
        addKV(card, "Линий и маршрутов", Integer.toString(r.routes.size()));
        sheetContent.addView(card);
        if (r.points.isEmpty() && r.routes.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("В файле не найдено точек или линий.");
            t.setTextColor(getColor(R.color.hud_text2));
            t.setPadding((int) dp(4), (int) dp(12), 0, 0);
            sheetContent.addView(t);
            return;
        }
        int shown = 0;
        hSection("СОДЕРЖИМОЕ");
        for (UserPoints.Point p : r.points) {
            if (shown++ >= 8) break;
            hRow(hIcon(R.drawable.ic_place, 0xFF29B6F6, 20), p.name, Sk42.format(p.lat, p.lon), null, null);
        }
        for (Routes.Route rt : r.routes) {
            if (shown++ >= 12) break;
            hRow(routeSwatch(rt.color), rt.name, Geo.dist(Routes.length(rt.pts)), null, null);
        }
        footer("Отмена", "Добавить на карту", v -> {
            long now = System.currentTimeMillis();
            int k = 0;
            for (UserPoints.Point p : r.points) {
                p.id = Long.toString(now + k++, 36);
                p.created = now;
            }
            for (Routes.Route rt : r.routes) {
                rt.id = Long.toString(now + k++, 36);
                rt.created = now;
            }
            userPoints.addAll(r.points);
            routes.addAll(r.routes);
            savePoints();
            saveRoutes();
            closeSheet();
            fitAll(r);
            Toast.makeText(this, "Добавлено: " + r.points.size() + " точек, " + r.routes.size() + " маршрутов", Toast.LENGTH_LONG).show();
        });
    }

    private void fitAll(Importer.Result r) {
        double n = -90, s = 90, e = -180, w = 180;
        for (UserPoints.Point p : r.points) { n = Math.max(n, p.lat); s = Math.min(s, p.lat); e = Math.max(e, p.lon); w = Math.min(w, p.lon); }
        for (Routes.Route rt : r.routes) for (double[] p : rt.pts) { n = Math.max(n, p[0]); s = Math.min(s, p[0]); e = Math.max(e, p[1]); w = Math.min(w, p[1]); }
        if (n < s) return;
        double pl = Math.max(0.01, (n - s) * 0.15), pn = Math.max(0.01, (e - w) * 0.15);
        map.zoomToBoundingBox(new BoundingBox(n + pl, e + pn, s - pl, w - pn), true, (int) dp(40));
    }

    private void makeBackup() {
        try {
            savePoints();
            saveRoutes();
            JSONObject o = new JSONObject();
            o.put("app", "lbs-kml");
            o.put("version", 1);
            o.put("created", System.currentTimeMillis());
            File pf = new File(getFilesDir(), "points.json"), rf = new File(getFilesDir(), "routes.json");
            o.put("points", pf.exists() ? new JSONArray(new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8)) : new JSONArray());
            o.put("routes", rf.exists() ? new JSONArray(new String(java.nio.file.Files.readAllBytes(rf.toPath()), StandardCharsets.UTF_8)) : new JSONArray());
            String name = "LBS_backup_" + new SimpleDateFormat("dd_MM_yyyy_HHmm", Locale.US).format(new Date()) + ".json";
            saveAndShare(name, o.toString(), "Сохранить резервную копию", "application/json");
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось сделать копию: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreFrom(Uri uri) {
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(new String(readUri(uri), StandardCharsets.UTF_8));
                if (!o.has("points") && !o.has("routes")) throw new Exception("это не резервная копия ЛБС KML");
                final JSONArray pts = o.optJSONArray("points"), rts = o.optJSONArray("routes");
                runOnUiThread(() -> {
                    sheet("Восстановление");
                    LinearLayout card = hCard();
                    addKV(card, "В копии точек", Integer.toString(pts == null ? 0 : pts.length()));
                    addKV(card, "В копии маршрутов", Integer.toString(rts == null ? 0 : rts.length()));
                    addKV(card, "Сейчас на телефоне", userPoints.size() + " точек, " + routes.size() + " маршрутов");
                    sheetContent.addView(card);
                    hChipRow(hChip("Добавить к текущим", R.drawable.ic_add, false, v -> applyRestore(pts, rts, false)));
                    footer("Отмена", "Заменить всё", v -> applyRestore(pts, rts, true));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось восстановить: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void applyRestore(JSONArray pts, JSONArray rts, boolean replace) {
        try {
            List<UserPoints.Point> np = new ArrayList<>();
            List<Routes.Route> nr = new ArrayList<>();
            if (pts != null) {
                for (int i = 0; i < pts.length(); i++) {
                    JSONObject o = pts.getJSONObject(i);
                    UserPoints.Point p = new UserPoints.Point();
                    p.id = o.optString("id");
                    p.name = o.optString("name");
                    p.note = o.optString("note");
                    p.color = o.optInt("color", UserPoints.COLORS[0]);
                    p.icon = o.optString("icon", "");
                    p.lat = o.getDouble("lat");
                    p.lon = o.getDouble("lon");
                    p.created = o.optLong("created");
                    np.add(p);
                }
            }
            if (rts != null) {
                for (int i = 0; i < rts.length(); i++) {
                    JSONObject o = rts.getJSONObject(i);
                    Routes.Route r = new Routes.Route();
                    r.id = o.optString("id");
                    r.name = o.optString("name");
                    r.color = o.optInt("color", Routes.COLORS[0]);
                    r.visible = o.optBoolean("visible", true);
                    r.created = o.optLong("created");
                    JSONArray p = o.getJSONArray("pts");
                    for (int j = 0; j < p.length(); j++)
                        r.pts.add(new double[]{p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)});
                    nr.add(r);
                }
            }
            if (replace) {
                userPoints.clear();
                routes.clear();
            }
            userPoints.addAll(np);
            routes.addAll(nr);
            savePoints();
            saveRoutes();
            closeSheet();
            Toast.makeText(this, "Восстановлено: " + np.size() + " точек, " + nr.size() + " маршрутов", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка восстановления: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("unused")
    private static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
