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
import java.util.Arrays;
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
        rotGesture = rot;
        rot.setEnabled(true);
        map.getOverlays().add(rot);
        roadsOverlay = new RoadsOverlay(this, d);
        roadsOverlay.setEnabled(false);
        roadsOverlay.setStatus(t -> {
            TextView at = findViewById(R.id.attribution);
            if (attrBase.isEmpty() || !at.getText().toString().startsWith(attrBase)) attrBase = at.getText().toString();
            at.setText(t == null ? attrBase : attrBase + " · " + t);
        });
        map.getOverlays().add(roadsOverlay);
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
        findViewById(R.id.navStop).setOnClickListener(v -> { if (guideOn()) stopGuide(true); else stopNav(); });
        findViewById(R.id.navPanel).setOnClickListener(v -> { if (guideOn()) guideAdjust(); });
        findViewById(R.id.targetCard).setOnLongClickListener(v -> {
            try {
                startNav(Collections.singletonList(new double[]{Double.parseDouble(prefs.getString("targetLat", "0")),
                        Double.parseDouble(prefs.getString("targetLon", "0"))}), 0, prefs.getString("targetName", "Цель"));
            } catch (Exception ignored) {
            }
            return true;
        });
        findViewById(R.id.toolProfile).setOnClickListener(v -> showProfile(toolPts, tool == TOOL_RULER ? "линейка" : "маршрут"));
        findViewById(R.id.dlCancel).setOnClickListener(v -> cancelDownload());
        findViewById(R.id.coordCard).setOnClickListener(v -> copyCenterCoords());
        findViewById(R.id.targetClear).setOnClickListener(v -> clearTarget());

        findViewById(R.id.dockLayers).setOnClickListener(v -> showLayers());
        findViewById(R.id.dockRuler).setOnClickListener(v -> showTools());
        findViewById(R.id.toolRoad).setOnClickListener(v -> roadMenu());
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

    /** Снимок из архива Esri Wayback (конкретный выпуск). */
    private static final class WaybackSource extends OnlineTileSourceBase {
        private final String tpl;

        WaybackSource(int num, String tpl) {
            super("Wayback" + num, 0, 18, 256, ".jpg", new String[]{tpl});
            this.tpl = tpl;
        }

        @Override
        public String getTileURLString(long idx) {
            return tpl.replace("{level}", Integer.toString(MapTileIndex.getZoom(idx)))
                    .replace("{row}", Integer.toString(MapTileIndex.getY(idx)))
                    .replace("{col}", Integer.toString(MapTileIndex.getX(idx)));
        }
    }

    private OnlineTileSourceBase satSource() {
        int num = prefs.getInt("wbNum", 0);
        String url = prefs.getString("wbUrl", "");
        return num > 0 && !url.isEmpty() ? new WaybackSource(num, url) : SAT;
    }
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

    // ---------- слои поверх подложки ----------
    private static final class OvSource extends OnlineTileSourceBase {
        private final boolean zyx;

        OvSource(String name, int maxZ, String ext, boolean zyx, String... urls) {
            super(name, 0, maxZ, 256, ext, urls);
            this.zyx = zyx;
        }

        @Override
        public String getTileURLString(long idx) {
            int z = MapTileIndex.getZoom(idx), x = MapTileIndex.getX(idx), y = MapTileIndex.getY(idx);
            return getBaseUrl() + z + "/" + (zyx ? y + "/" + x : x + "/" + y + imageFilenameEnding());
        }
    }

    /** Ключ, название, описание, источник, прозрачность (0..1). */
    private static final String[] OV_KEYS = {"ovRoadsV", "ovRail", "ovOsm", "ovPlaces"};
    private static final String[] OV_NAMES = {"Дороги", "Железные дороги", "Схема поверх (полупрозрачно)", "Границы и названия"};
    private static final String[] OV_SUBS = {"Только линии дорог: трассы, улицы, грунтовки (OpenStreetMap)", "Пути, станции, переезды (OpenRailwayMap)",
            "Всё из OpenStreetMap: реки, лес, дома, дороги", "Границы областей, названия (Esri, латиницей)"};
    private static final OnlineTileSourceBase[] OV_SRC = {
            new OvSource("OvRoads", 19, ".png", true, ESRI + "Reference/World_Transportation/MapServer/tile/"),
            new OvSource("OvRail", 19, ".png", false, "https://a.tiles.openrailwaymap.org/standard/",
                    "https://b.tiles.openrailwaymap.org/standard/", "https://c.tiles.openrailwaymap.org/standard/"),
            new OvSource("OvOsm", 19, ".png", false, "https://tile.openstreetmap.org/"),
            new OvSource("OvPlaces", 19, ".png", true, ESRI + "Reference/World_Boundaries_and_Places/MapServer/tile/")};
    private static final float[] OV_ALPHA = {1f, 0.95f, 0.45f, 1f};
    private final List<TilesOverlay> ovLayers = new ArrayList<>();
    private RoadsOverlay roadsOverlay;
    private String attrBase = "";

    private void applyOverlays() {
        for (TilesOverlay o : ovLayers) {
            map.getOverlays().remove(o);
            o.onDetach(map);
        }
        ovLayers.clear();
        int at = map.getOverlays().indexOf(lbsFolder);
        if (at < 0) at = 2;
        if (roadsOverlay != null) roadsOverlay.setEnabled(prefs.getBoolean(OV_KEYS[0], false));
        for (int i = 1; i < OV_KEYS.length; i++) {
            if (!prefs.getBoolean(OV_KEYS[i], false)) continue;
            if (i == 3 && esriLabels != null) continue; // уже показаны как подписи
            MapTileProviderBasic prov = new MapTileProviderBasic(getApplicationContext(), OV_SRC[i]);
            TilesOverlay o = new TilesOverlay(prov, getApplicationContext());
            o.setLoadingBackgroundColor(Color.TRANSPARENT);
            o.setLoadingLineColor(Color.TRANSPARENT);
            if (OV_ALPHA[i] < 1f) {
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setScale(1f, 1f, 1f, OV_ALPHA[i]);
                o.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            }
            map.getOverlays().add(at++, o);
            ovLayers.add(o);
        }
        map.invalidate();
    }
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
        if (style != STYLE_SAT && compareOn()) stopCompare();
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
            map.setTileSource(satSource());
            if (!ru) {
                MapTileProviderBasic prov = new MapTileProviderBasic(getApplicationContext(), LABELS);
                esriLabels = new TilesOverlay(prov, getApplicationContext());
                esriLabels.setLoadingBackgroundColor(Color.TRANSPARENT);
                esriLabels.setLoadingLineColor(Color.TRANSPARENT);
                map.getOverlays().add(2, esriLabels);
            }
            attr.setText(prefs.getInt("wbNum", 0) > 0
                    ? "Архив Esri Wayback · снимок " + prefs.getString("wbLabel", "")
                    : "Esri, Maxar, Earthstar Geographics");
        }
        attrBase = attr.getText().toString();
        applyHillshade();
        applyOverlays();
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
        if (qrShown) {
            qrShown = false;
            applyNight();
        }
        stopRecording();
        stopPlaying();
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
            android.view.ViewGroup.LayoutParams own = lead.getLayoutParams();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    own != null ? own.width : LinearLayout.LayoutParams.WRAP_CONTENT,
                    own != null ? own.height : LinearLayout.LayoutParams.WRAP_CONTENT);
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
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        hRow(hIcon(R.drawable.ic_refresh, getColor(R.color.hud_text), 22), "Дата снимка",
                prefs.getInt("wbNum", 0) > 0 ? "Архив: " + prefs.getString("wbLabel", "") : "Самый свежий (по умолчанию)",
                null, v -> showWayback());
        hRow(hIcon(R.drawable.ic_compare, getColor(R.color.hud_text), 22), "Сравнить два снимка",
                compareOn() ? "Сейчас включено — нажмите, чтобы выключить" : "Шторка: слева архив, справа текущий",
                null, v -> { if (compareOn()) { stopCompare(); closeSheet(); } else showWayback(true); });

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

        hSection("ПОВЕРХ КАРТЫ");
        for (int i = 0; i < OV_KEYS.length; i++) {
            final String key = OV_KEYS[i];
            hToggle(OV_NAMES[i], OV_SUBS[i], prefs.getBoolean(key, false), on -> {
                prefs.edit().putBoolean(key, on).apply();
                applyOverlays();
            });
        }
        TextView ovHint = new TextView(this);
        ovHint.setText("Слои работают на любой подложке и сохраняются в памяти по мере просмотра. "
                + "«Схема поверх» удобна на спутнике: видно названия улиц, реки и лесополосы.");
        ovHint.setTextColor(getColor(R.color.hud_text2));
        ovHint.setTextSize(12);
        ovHint.setPadding((int) dp(12), (int) dp(2), (int) dp(12), 0);
        sheetContent.addView(ovHint);

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
        n.setText("Рубеж");
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
        if (prefs.getInt("wbNum", 0) > 0) {
            cb.on("Спутник: архивный снимок " + prefs.getString("wbLabel", ""));
            return;
        }
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
                + (Double.isNaN(centerElev) ? (offline() ? "   ↑ нет данных" : "   ↑ …") : "   ↑ " + Math.round(centerElev) + " м"));
    }

    private void updateCoords() {
        if (reticleOverlay == null || map == null) return;
        GeoPoint c = (GeoPoint) map.getMapCenter();
        double lat = c.getLatitude(), lon = c.getLongitude();
        // высота «на лету» из тайлов в памяти; если тайла ещё нет — догружаем и показываем чуть позже
        elevHandler.removeCallbacks(elevTask);
        double hNow = Elevation.peek(this, lat, lon);
        centerElev = hNow;
        if (Double.isNaN(hNow)) elevHandler.postDelayed(elevTask, 120);
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
        Routes.Route hit = routeAt(a.x, a.y);
        if (hit != null) {
            routeActions(hit);
            return true;
        }
        return false;
    }

    /** Маршрут или зона под пальцем (линия в пределах ~16 dp или нажатие внутри зоны). */
    private Routes.Route routeAt(float x, float y) {
        Projection pj = map.getProjection();
        float tol = dp(16);
        Routes.Route best = null, inside = null;
        double bestD = tol;
        GeoPoint g = new GeoPoint(0.0, 0.0);
        for (Routes.Route r : routes) {
            if (!r.visible || r.pts.size() < 2) continue;
            int n = r.pts.size();
            float[] xs = new float[n], ys = new float[n];
            for (int i = 0; i < n; i++) {
                g.setCoords(r.pts.get(i)[0], r.pts.get(i)[1]);
                android.graphics.Point q = pj.toPixels(g, null);
                xs[i] = q.x;
                ys[i] = q.y;
            }
            int segs = r.area ? n : n - 1;
            for (int i = 0; i < segs; i++) {
                int j = (i + 1) % n;
                double d = segDist(x, y, xs[i], ys[i], xs[j], ys[j]);
                if (d < bestD) { bestD = d; best = r; }
            }
            if (r.area && inside == null) {
                boolean in = false;
                for (int i = 0, j = n - 1; i < n; j = i++)
                    if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) in = !in;
                if (in) inside = r;
            }
        }
        return best != null ? best : inside;
    }

    private static double segDist(float px, float py, float ax, float ay, float bx, float by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + dx * t), py - (ay + dy * t));
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
        final File draftFile = voiceFile(null);
        draftFile.delete();
        final File[] vfile = {existing != null ? voiceFile(existing.id) : draftFile};
        final LinearLayout voiceRow = new LinearLayout(this);
        LinearLayout.LayoutParams vrl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vrl.topMargin = (int) dp(8);
        sheetContent.addView(voiceRow, vrl);
        fillVoiceRow(voiceRow, vfile, note);

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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(38), 1);
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

        final TextView iconLabel = hSection("ЗНАЧОК · " + iconName(icon[0]).toUpperCase(new Locale("ru")));
        final List<ImageView> iconViews = new ArrayList<>();
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
            ImageView t = new ImageView(this);
            t.setImageDrawable(new PinIcons.IconDrawable(ic, getColor(R.color.hud_text), 0xFF22262C));
            int ip = (int) dp(9);
            t.setPadding(ip, ip, ip, ip);
            t.setTag(ic);
            t.setContentDescription(icName);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(44), 1);
            lp.setMarginEnd((int) dp(7));
            t.setOnClickListener(v -> {
                icon[0] = ic;
                for (ImageView tv : iconViews) styleIconHud(tv, tv == v);
                preview.setImageBitmap(UserPoints.pin(chosen[0], den * 1.3f, icon[0]));
                iconLabel.setText("ЗНАЧОК · " + icName.toUpperCase(new Locale("ru")));
            });
            iconViews.add(t);
            icRow.addView(t, lp);
            styleIconHud(t, ic.equals(icon[0]));
        }

        coordsCard(lat, lon);

        hChipRow(
                hChip("Вести", R.drawable.ic_nav, true, v -> {
                    String nm = name.getText().toString().trim();
                    closeSheet();
                    startNav(Collections.singletonList(new double[]{lat, lon}), 0, nm.isEmpty() ? "Точка" : nm);
                }),
                hChip("Навести", R.drawable.ic_flag, false, v -> {
                    String nm = name.getText().toString().trim();
                    closeSheet();
                    setTarget(lat, lon, nm.isEmpty() ? "Точка" : nm);
                }),
                hChip("Линейка", R.drawable.ic_ruler, false, v -> {
                    closeSheet();
                    startTool(TOOL_RULER, null);
                    toolAdd(lat, lon);
                }));
        hChipRow(
                hChip("Поделиться", R.drawable.ic_share, false, v -> {
                    String nm = name.getText().toString().trim();
                    sharePointText(nm.isEmpty() ? "Точка" : nm, lat, lon);
                }),
                hChip("QR-код", R.drawable.ic_qr, false, v -> {
                    UserPoints.Point q = new UserPoints.Point();
                    String nm = name.getText().toString().trim();
                    q.name = nm.isEmpty() ? "Точка" : nm;
                    q.note = existing != null ? existing.note : "";
                    q.color = chosen[0];
                    q.icon = icon[0];
                    q.lat = lat;
                    q.lon = lon;
                    showQr(QrCodec.encodePoint(q), q.name);
                }));
        if (existing != null) {
            hChipRow(hChip("Удалить точку", R.drawable.ic_delete, false, v -> new AlertDialog.Builder(this)
                    .setTitle("Удалить «" + existing.name + "»?")
                    .setPositiveButton("Удалить", (d2, w2) -> {
                        userPoints.remove(existing);
                        voiceFile(existing.id).delete();
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
            stopRecording();
            stopPlaying();
            if (vfile[0] == draftFile && draftFile.exists()) draftFile.renameTo(voiceFile(p.id));
            prefs.edit().putInt("lastColor", chosen[0]).putString("lastIcon", icon[0]).apply();
            savePoints();
            closeSheet();
        });
    }

    private void styleIconHud(ImageView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        int bg = on ? getColor(R.color.hud_accent) : 0xFF22262C;
        g.setColor(bg);
        g.setStroke((int) dp(1), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
        t.setBackground(g);
        if (t.getDrawable() instanceof PinIcons.IconDrawable)
            ((PinIcons.IconDrawable) t.getDrawable()).setColors(on ? Color.WHITE : getColor(R.color.hud_text), bg);
    }

    private static String iconName(String ic) {
        for (int i = 0; i < UserPoints.ICONS.length; i++) if (UserPoints.ICONS[i].equals(ic)) return UserPoints.ICON_NAMES[i];
        return "Свой";
    }

    /** Круглый образец цвета: всегда ровный круг; выбранный — с кольцом и галочкой. */
    private android.graphics.drawable.Drawable swatchHud(int color, boolean sel) {
        final float d = getResources().getDisplayMetrics().density;
        final int accent = getColor(R.color.hud_accent), line = getColor(R.color.hud_line);
        return new android.graphics.drawable.Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                float cx = b.exactCenterX(), cy = b.exactCenterY();
                float r = Math.min(Math.min(b.width(), b.height()) / 2f, 17 * d);
                p.setStyle(Paint.Style.FILL);
                if (sel) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.2f * d);
                    p.setColor(Color.WHITE);
                    c.drawCircle(cx, cy, r - 1.1f * d, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(color);
                    c.drawCircle(cx, cy, r - 4.5f * d, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.2f * d);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setColor(UserPoints.light(color) ? 0xFF1B1F24 : Color.WHITE);
                    float k = r * 0.32f;
                    android.graphics.Path ck = new android.graphics.Path();
                    ck.moveTo(cx - k, cy + 0.05f * k);
                    ck.lineTo(cx - 0.3f * k, cy + 0.72f * k);
                    ck.lineTo(cx + k, cy - 0.6f * k);
                    c.drawPath(ck, p);
                } else {
                    p.setColor(color);
                    c.drawCircle(cx, cy, r - 4.5f * d, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1f * d);
                    p.setColor(line);
                    c.drawCircle(cx, cy, r - 4.5f * d, p);
                }
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private void showPointsList() {
        sheet("Мои точки" + (userPoints.isEmpty() ? "" : " · " + userPoints.size()));
        hChipRow(
                hChip("Точка в центре", R.drawable.ic_add, true, v -> { closeSheet(); newPointAtCenter(); }),
                hChip("Экспорт KML", R.drawable.ic_share, false, v -> { closeSheet(); exportPoints(); }));
        hChipRow(hChip("Принять по QR", R.drawable.ic_qr, false, v -> { closeSheet(); scanQr(); }));
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

    private static final int TOOL_NONE = 0, TOOL_RULER = 1, TOOL_ROUTE = 2, TOOL_AREA = 3;
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
                : route != null ? route.color : which == TOOL_AREA ? Routes.COLORS[3] : Routes.COLORS[0], which == TOOL_AREA);
        findViewById(R.id.dock).setVisibility(View.GONE);
        findViewById(R.id.toolPanel).setVisibility(View.VISIBLE);
        findViewById(R.id.toolCross).setVisibility(View.VISIBLE);
        findViewById(R.id.centerMark).setVisibility(View.GONE);
        findViewById(R.id.toolSave).setVisibility(which != TOOL_RULER ? View.VISIBLE : View.GONE);
        findViewById(R.id.toolProfile).setVisibility(which != TOOL_AREA ? View.VISIBLE : View.GONE);
        findViewById(R.id.toolRoad).setVisibility(which != TOOL_AREA ? View.VISIBLE : View.GONE);
        toolSteps.clear();
        for (int i = 0; i < toolPts.size(); i++) toolSteps.add(1);
        roadLast = "";
        updateToolTitle();
        applyGrid();
        updateToolPanel();
        map.invalidate();
        if (toolPts.isEmpty())
            Toast.makeText(this, "Нажимайте на карту или на свои точки, чтобы ставить вершины", Toast.LENGTH_SHORT).show();
    }

    private void stopTool() {
        boolean editable = tool == TOOL_ROUTE || tool == TOOL_AREA;
        if (editable && editingRoute != null) editingRoute.visible = true;
        if (editable && editingRoute == null && toolPts.size() > (tool == TOOL_AREA ? 2 : 1)) {
            new AlertDialog.Builder(this)
                    .setTitle(tool == TOOL_AREA ? "Зона не сохранена" : "Маршрут не сохранён")
                    .setMessage("Сохранить перед выходом?")
                    .setPositiveButton("Сохранить", (d, w) -> toolSave())
                    .setNegativeButton("Не сохранять", (d, w) -> finishTool())
                    .show();
            return;
        }
        finishTool();
    }

    private void finishTool() {
        tool = TOOL_NONE;
        roadMode = "";
        toolSteps.clear();
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

    /** Сколько точек добавило каждое действие (для «отменить» после прокладки по дорогам). */
    private final List<Integer> toolSteps = new ArrayList<>();
    /** Режим авто-маршрута: "" — прямые, "foot" / "car" — по дорогам. */
    private String roadMode = "";
    private boolean roadBusy;

    private void toolAdd(double lat, double lon) {
        if (!roadMode.isEmpty() && !toolPts.isEmpty() && tool != TOOL_AREA) {
            if (roadBusy) {
                Toast.makeText(this, "Подождите, прокладываю предыдущий участок…", Toast.LENGTH_SHORT).show();
                return;
            }
            double[] last = toolPts.get(toolPts.size() - 1);
            roadSegment(Arrays.asList(last, new double[]{lat, lon}), roadMode, false);
            return;
        }
        toolPts.add(new double[]{lat, lon});
        toolSteps.add(1);
        updateToolPanel();
        map.invalidate();
    }

    private void toolUndo() {
        if (roadBusy) return;
        int k = toolSteps.isEmpty() ? 1 : toolSteps.remove(toolSteps.size() - 1);
        if (k < 0) { // откат «весь маршрут по дорогам»
            toolPts.clear();
            toolPts.addAll(roadBackup);
            toolSteps.clear();
            for (int i = 0; i < toolPts.size(); i++) toolSteps.add(1);
        } else {
            for (int i = 0; i < k && !toolPts.isEmpty(); i++) toolPts.remove(toolPts.size() - 1);
        }
        updateToolPanel();
        map.invalidate();
    }

    private final List<double[]> roadBackup = new ArrayList<>();

    /**
     * Прокладка по дорогам (OSRM, данные OpenStreetMap). GPS не нужен, нужен интернет.
     * whole=false — добавить участок к линии; whole=true — заменить всю линию дорожным вариантом.
     */
    private void roadSegment(List<double[]> via, String mode, boolean whole) {
        if (offline()) {
            Toast.makeText(this, "Прокладка по дорогам работает только с интернетом. Выключите «Без сети».", Toast.LENGTH_LONG).show();
            if (!whole) {
                double[] p = via.get(via.size() - 1);
                toolPts.add(p);
                toolSteps.add(1);
                updateToolPanel();
                map.invalidate();
            }
            return;
        }
        roadBusy = true;
        ((TextView) findViewById(R.id.toolSub)).setText("Прокладываю по дорогам…");
        final List<double[]> pts = new ArrayList<>(via);
        new Thread(() -> {
            String err = null;
            List<double[]> geom = new ArrayList<>();
            double dist = 0, dur = 0;
            try {
                StringBuilder u = new StringBuilder("https://routing.openstreetmap.de/routed-")
                        .append(mode.equals("car") ? "car" : "foot").append("/route/v1/driving/");
                for (int i = 0; i < pts.size(); i++) {
                    if (i > 0) u.append(';');
                    u.append(String.format(Locale.US, "%.6f,%.6f", pts.get(i)[1], pts.get(i)[0]));
                }
                u.append("?overview=full&geometries=geojson");
                JSONObject o = new JSONObject(httpGet(u.toString()));
                if (!"Ok".equals(o.optString("code"))) throw new Exception(o.optString("message", "дорога не найдена"));
                JSONObject r = o.getJSONArray("routes").getJSONObject(0);
                dist = r.optDouble("distance");
                dur = r.optDouble("duration");
                JSONArray c = r.getJSONObject("geometry").getJSONArray("coordinates");
                for (int i = 0; i < c.length(); i++) {
                    double[] p = {c.getJSONArray(i).getDouble(1), c.getJSONArray(i).getDouble(0)};
                    if (!geom.isEmpty() && i < c.length() - 1) {
                        double[] q = geom.get(geom.size() - 1);
                        if (Geo.inverse(q[0], q[1], p[0], p[1])[0] < 8) continue;
                    }
                    geom.add(p);
                }
                if (geom.size() < 2) throw new Exception("пустой ответ");
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fe = err;
            final double fd = dist, fdur = dur;
            runOnUiThread(() -> {
                roadBusy = false;
                if (tool == TOOL_NONE) return;
                if (fe != null) {
                    Toast.makeText(this, "Не удалось проложить по дорогам (" + fe + ")"
                            + (whole ? "" : " — участок поставлен прямой"), Toast.LENGTH_LONG).show();
                    if (!whole) {
                        toolPts.add(pts.get(pts.size() - 1));
                        toolSteps.add(1);
                    }
                } else if (whole) {
                    roadBackup.clear();
                    roadBackup.addAll(toolPts);
                    toolPts.clear();
                    toolPts.addAll(geom);
                    toolSteps.clear();
                    toolSteps.add(-1);
                    Toast.makeText(this, "По дорогам: " + Geo.dist(fd) + ", ~" + durText(fdur)
                            + ". «Отменить» вернёт прямые участки", Toast.LENGTH_LONG).show();
                } else {
                    // первая точка ответа ≈ последняя наша — её пропускаем
                    toolPts.addAll(geom.subList(1, geom.size()));
                    toolSteps.add(geom.size() - 1);
                    roadLast = "+" + Geo.dist(fd) + ", ~" + durText(fdur);
                }
                updateToolPanel();
                map.invalidate();
            });
        }).start();
    }

    private String roadLast = "";

    private static String durText(double sec) {
        long m = Math.round(sec / 60);
        return m < 60 ? m + " мин" : (m / 60) + " ч " + (m % 60) + " мин";
    }

    /** Кнопка «По дорогам» на панели: выбор способа и прокладка всей линии. */
    private void roadMenu() {
        sheet("По дорогам");
        TextView t = new TextView(this);
        t.setText("Линия пройдёт по дорогам и тропам из OpenStreetMap. GPS не нужен, нужен интернет. "
                + "Можно включить авто-режим: каждая новая точка сразу соединяется с предыдущей по дорогам.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12.5f);
        t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(t);
        int tx = getColor(R.color.hud_text);
        if (toolPts.size() >= 2) {
            hSection("ПРОЛОЖИТЬ ВСЮ ЛИНИЮ (" + toolPts.size() + " точек)");
            hRow(hIcon(R.drawable.ic_road, tx, 22), "Пешком", "Тропы, грунтовки, дороги", null, v -> {
                closeSheet();
                roadWhole("foot");
            });
            hRow(hIcon(R.drawable.ic_road, tx, 22), "На машине", "Только проезжие дороги", null, v -> {
                closeSheet();
                roadWhole("car");
            });
        }
        hSection("АВТО-РЕЖИМ ДЛЯ НОВЫХ ТОЧЕК");
        hToggle("Пешком по дорогам", null, roadMode.equals("foot"), on -> { setRoadMode(on ? "foot" : ""); closeSheet(); });
        hToggle("На машине по дорогам", null, roadMode.equals("car"), on -> { setRoadMode(on ? "car" : ""); closeSheet(); });
    }

    private void roadWhole(String mode) {
        if (toolPts.size() > 25) {
            // много вершин (например, импортированный трек) — берём каждую n-ю, концы сохраняем
            List<double[]> v = new ArrayList<>();
            double step = (toolPts.size() - 1) / 24.0;
            for (int i = 0; i < 25; i++) v.add(toolPts.get((int) Math.round(i * step)));
            roadSegment(v, mode, true);
        } else {
            roadSegment(new ArrayList<>(toolPts), mode, true);
        }
    }

    private void setRoadMode(String mode) {
        roadMode = mode;
        roadLast = "";
        updateToolTitle();
        updateToolPanel();
        if (!mode.isEmpty())
            Toast.makeText(this, "Авто-маршрут " + (mode.equals("car") ? "на машине" : "пешком")
                    + ": ставьте точки — линия пойдёт по дорогам", Toast.LENGTH_LONG).show();
    }

    private void updateToolTitle() {
        String base = tool == TOOL_RULER ? "ЛИНЕЙКА" : tool == TOOL_AREA ? "ЗОНА" : "МАРШРУТ";
        if (editingRoute != null && tool != TOOL_RULER) base += " · " + editingRoute.name;
        if (!roadMode.isEmpty() && tool != TOOL_AREA) base += roadMode.equals("car") ? " · ПО ДОРОГАМ (АВТО)" : " · ПО ДОРОГАМ (ПЕШКОМ)";
        ((TextView) findViewById(R.id.toolTitle)).setText(base);
    }

    private void toolClear() {
        toolSteps.clear();
        roadLast = "";
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
        if (tool == TOOL_AREA) {
            List<double[]> withC = new ArrayList<>(toolPts);
            GeoPoint cc = (GeoPoint) map.getMapCenter();
            withC.add(new double[]{cc.getLatitude(), cc.getLongitude()});
            total.setText(n >= 3 ? Routes.areaText(Routes.area(toolPts)) : "—");
            sub.setText((n >= 3 ? "Периметр " + Geo.dist(Routes.perimeter(toolPts)) + " · " : "") + n + " верш."
                    + (n >= 2 ? "\nС прицелом: " + Routes.areaText(Routes.area(withC)) + " · периметр " + Geo.dist(Routes.perimeter(withC)) : "\nСтавьте углы зоны — минимум 3"));
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
        if (!roadLast.isEmpty()) sb.append("По дорогам: последний участок ").append(roadLast).append("\n");
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
        final boolean isArea = tool == TOOL_AREA;
        if (toolPts.size() < (isArea ? 3 : 2)) {
            Toast.makeText(this, isArea ? "Нужно минимум 3 угла" : "Нужно минимум 2 точки", Toast.LENGTH_SHORT).show();
            return;
        }
        int zones = 0;
        for (Routes.Route x : routes) if (x.area) zones++;
        sheet(isArea ? (editingRoute != null ? "Изменить зону" : "Сохранить зону")
                : editingRoute != null ? "Изменить маршрут" : "Сохранить маршрут");
        final EditText name = hInput(isArea ? "Название зоны" : "Название маршрута",
                editingRoute != null ? editingRoute.name : isArea ? "Зона " + (zones + 1) : "Маршрут " + (routes.size() - zones + 1));
        name.setSingleLine(true);
        name.setSelectAllOnFocus(true);
        sheetContent.addView(name);

        LinearLayout info = hCard();
        TextView t = new TextView(this);
        t.setText(isArea ? Routes.areaText(Routes.area(toolPts)) + " · периметр " + Geo.dist(Routes.perimeter(toolPts))
                + " · " + toolPts.size() + " углов"
                : Geo.dist(Routes.length(toolPts)) + " · " + (toolPts.size() - 1) + " уч. · " + toolPts.size() + " точек");
        t.setTextColor(getColor(R.color.hud_text));
        t.setTextSize(14);
        info.addView(t);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        il.topMargin = (int) dp(10);
        sheetContent.addView(info, il);

        hSection("ЦВЕТ ЛИНИИ");
        final int[] chosen = {editingRoute != null ? editingRoute.color : isArea ? Routes.COLORS[3] : Routes.COLORS[0]};
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
            r.name = nm.isEmpty() ? (isArea ? "Зона" : "Маршрут") : nm;
            r.color = chosen[0];
            r.area = isArea;
            r.pts.clear();
            r.pts.addAll(toolPts);
            r.visible = true;
            saveRoutes();
            Toast.makeText(this, (isArea ? "Зона «" + r.name + "» сохранена" : "Маршрут «" + r.name + "» сохранён"), Toast.LENGTH_SHORT).show();
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
        hChipRow(hChip("Новый маршрут", R.drawable.ic_add, true, v -> { closeSheet(); startTool(TOOL_ROUTE, null); }),
                hChip("Новая зона", R.drawable.ic_area, false, v -> { closeSheet(); startTool(TOOL_AREA, null); }));
        hChipRow(hChip("Принять по QR", R.drawable.ic_qr, false, v -> { closeSheet(); scanQr(); }));
        if (track != null && track.pts.size() > 1)
            hChipRow(hChip("Вернуться по треку", R.drawable.ic_nav, true, v -> { closeSheet(); navRoute(track, true); }));
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
            hRow(routeSwatch(r.color), r.name, r.area
                    ? "Зона · " + Routes.areaText(Routes.area(r.pts)) + " · периметр " + Geo.dist(Routes.perimeter(r.pts))
                    : Geo.dist(Routes.length(r.pts)) + " · " + Math.max(0, r.pts.size() - 1) + " уч.", eye, v -> routeActions(r));
        }
    }

    private void routeActions(Routes.Route r) {
        sheet(r.name);
        LinearLayout info = hCard();
        TextView t = new TextView(this);
        List<Routes.Leg> legs = Routes.legs(r.pts);
        StringBuilder sb = new StringBuilder(Geo.dist(Routes.length(r.pts))).append(" · ").append(legs.size()).append(" уч.");
        if (r.area) sb = new StringBuilder("Площадь ").append(Routes.areaText(Routes.area(r.pts))).append("\nПериметр ")
                .append(Geo.dist(Routes.perimeter(r.pts))).append(" · ").append(r.pts.size()).append(" углов");
        else if (!legs.isEmpty())
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
        if (!r.area) {
            hRow(hIcon(R.drawable.ic_nav, 0xFF42A5F5, 22), "Вести по маршруту", "Стрелка, расстояние и время до каждой КТ", null,
                    v -> { closeSheet(); navRoute(r, false); });
            hRow(hIcon(R.drawable.ic_undo, 0xFF42A5F5, 22), "Вести обратно", "Возврат тем же путём (по треку назад)", null,
                    v -> { closeSheet(); navRoute(r, true); });
        }
        hRow(hIcon(r.area ? R.drawable.ic_area : R.drawable.ic_route, tx, 22), "Изменить точки", null, null,
                v -> { closeSheet(); startTool(r.area ? TOOL_AREA : TOOL_ROUTE, r); });
        hRow(hIcon(R.drawable.ic_qr, tx, 22), "Передать по QR-коду", "На другой телефон без интернета", null,
                v -> showQr(QrCodec.encodeRoute(r), r.name));
        hRow(hIcon(R.drawable.ic_table, tx, 22), "Таблица участков", "Длины, азимуты, дир. углы, координаты", null, v -> {
            closeSheet();
            List<double[]> keep = new ArrayList<>(toolPts);
            toolPts.clear();
            toolPts.addAll(r.pts);
            toolTable();
            toolPts.clear();
            toolPts.addAll(keep);
        });
        if (!r.area)
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
            ((TextView) findViewById(R.id.menuVersion)).setText("Рубеж · v"
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
        if (roadsOverlay != null && prefs.getBoolean(OV_KEYS[0], false)) {
            final List<String> keys = RoadsOverlay.keysFor(bb, 13, 80);
            final RoadsOverlay ro = roadsOverlay;
            new Thread(() -> {
                int ok = 0;
                for (String k : keys) {
                    try {
                        ro.download(k);
                        ok++;
                    } catch (Exception ignored) {
                    }
                }
                final int fo = ok;
                runOnUiThread(() -> Toast.makeText(this, "Дороги района сохранены: " + fo + " из " + keys.size() + " квадратов",
                        Toast.LENGTH_LONG).show());
            }).start();
        }
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
        if (compareOn()) {
            stopCompare();
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
        if (navOn() && sensors != null) {
            android.hardware.Sensor rv2 = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
            if (rv2 != null) sensors.registerListener(navSensor, rv2, android.hardware.SensorManager.SENSOR_DELAY_UI);
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
        if (sensors != null) sensors.unregisterListener(navSensor);
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
        if (locMgr != null) locMgr.removeUpdates(locListener);
        if (track != null) setTracking(false);
        if (sensors != null) {
            sensors.unregisterListener(navSensor);
            sensors.unregisterListener(stepListener);
        }
        gHandler.removeCallbacksAndMessages(null);
        if (tts != null) tts.shutdown();
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
        if (navOn()) stopNav();
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
        if (navOn()) updateNav(l);
        if (guideOn()) guideLocation(l);
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
        final boolean los = pts.size() == 2;
        sheet((los ? "Профиль и видимость · " : "Профиль высот · ") + title);
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

        final TextView losText = new TextView(this);
        final double[] hObs = {prefs.getFloat("losObs", 2f)}, hTgt = {prefs.getFloat("losTgt", 0f)};
        final double[][] data = new double[2][];
        final Runnable[] recalc = new Runnable[1];
        if (los) {
            hSection("ПРЯМАЯ ВИДИМОСТЬ: ТОЧКА 1 → ТОЧКА 2");
            LinearLayout lc = hCard();
            losText.setTextColor(getColor(R.color.hud_text));
            losText.setTextSize(13.5f);
            losText.setLineSpacing(dp(3), 1f);
            losText.setText("Жду высоты…");
            lc.addView(losText);
            sheetContent.addView(lc);
            heightChips("Высота наблюдателя над землёй", new double[]{0, 2, 5, 10, 20, 50}, hObs, v -> {
                prefs.edit().putFloat("losObs", (float) v).apply();
                if (recalc[0] != null) recalc[0].run();
            });
            heightChips("Высота цели над землёй", new double[]{0, 2, 5, 10, 20, 50}, hTgt, v -> {
                prefs.edit().putFloat("losTgt", (float) v).apply();
                if (recalc[0] != null) recalc[0].run();
            });
        }
        TextView hint = new TextView(this);
        hint.setText(los ? "Зелёный пунктир — луч видит цель, красный — луч перекрыт рельефом. "
                + "Красным на рельефе — участки, которые не видны из точки 1. Учтены кривизна Земли и рефракция; "
                + "лес и постройки не учитываются."
                : "Проведите пальцем по графику — высота и расстояние в этом месте. "
                + "Для расчёта видимости поставьте линейкой ровно 2 точки.");
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
            int samples = los ? Math.max(160, Math.min(400, (int) (total / 15))) : 160;
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
            final boolean ok = known > 0;
            if (!ok) {
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
                if (los && ok) {
                    data[0] = d;
                    data[1] = e;
                    recalc[0] = () -> losText.setText(computeLos(pv, data[0], data[1], hObs[0], hTgt[0]));
                    recalc[0].run();
                } else if (los) {
                    losText.setText("Нет высот — видимость не посчитать.");
                }
            });
        }).start();
    }

    private interface DoubleCb {
        void on(double v);
    }

    /** Ряд кнопок выбора высоты (м). */
    private void heightChips(String label, double[] values, double[] cur, DoubleCb cb) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.hud_text2));
        l.setTextSize(12);
        l.setPadding((int) dp(4), (int) dp(10), 0, (int) dp(4));
        sheetContent.addView(l);
        LinearLayout row = new LinearLayout(this);
        final List<TextView> all = new ArrayList<>();
        for (double v : values) {
            TextView c = new TextView(this);
            c.setText(Math.round(v) + " м");
            c.setGravity(android.view.Gravity.CENTER);
            c.setTextSize(13);
            c.setPadding(0, (int) dp(8), 0, (int) dp(8));
            c.setTag(v);
            all.add(c);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            p.setMarginEnd((int) dp(6));
            row.addView(c, p);
            c.setOnClickListener(x -> {
                cur[0] = v;
                for (TextView o : all) styleHeightChip(o, (double) o.getTag() == v);
                cb.on(v);
            });
            styleHeightChip(c, v == cur[0]);
        }
        sheetContent.addView(row);
    }

    private void styleHeightChip(TextView c, boolean on) {
        c.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        c.setTextColor(on ? getColor(R.color.hud_accent) : getColor(R.color.hud_text));
    }

    /** Прямая видимость точка 1 → точка 2 по профилю; рисует луч на графике, возвращает текст. */
    private String computeLos(ProfileView pv, double[] d, double[] e, double hObs, double hTgt) {
        int n = d.length;
        double D = d[n - 1];
        final double RE = 6371000.0 / (1 - 0.13); // эффективный радиус Земли с рефракцией
        int i0 = 0, i1 = n - 1;
        while (i0 < n && Double.isNaN(e[i0])) i0++;
        while (i1 > 0 && Double.isNaN(e[i1])) i1--;
        if (i0 >= i1) return "Недостаточно высот.";
        double a = e[i0] + hObs, b = e[i1] + hTgt;
        double[] sight = new double[n];
        boolean[] hidden = new boolean[n];
        boolean blocked = false;
        double firstBlock = -1, worst = 0, worstAt = 0;
        double maxSlope = -Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            double bulge = d[k] * (D - d[k]) / (2 * RE);
            sight[k] = a + (b - a) * d[k] / D - bulge;
            if (Double.isNaN(e[k])) { sight[k] = Double.NaN; continue; }
            if (k > i0 && k < i1 && e[k] > sight[k]) {
                blocked = true;
                if (firstBlock < 0) firstBlock = d[k];
                if (e[k] - sight[k] > worst) { worst = e[k] - sight[k]; worstAt = d[k]; }
            }
            if (k > i0) {
                double slope = (e[k] - d[k] * d[k] / (2 * RE) - a) / d[k];
                hidden[k] = slope < maxSlope - 1e-6;
                maxSlope = Math.max(maxSlope, slope);
            }
        }
        // как высоко нужно поднять цель, чтобы её стало видно
        double need = 0;
        if (blocked) {
            for (int k = i0 + 1; k < i1; k++) {
                if (Double.isNaN(e[k])) continue;
                double bulge = d[k] * (D - d[k]) / (2 * RE);
                double t = d[k] / D;
                double reqB = (e[k] + bulge - a * (1 - t)) / t;
                need = Math.max(need, reqB - e[i1]);
            }
        }
        int hiddenCount = 0, total = 0;
        for (int k = i0 + 1; k <= i1; k++) { total++; if (hidden[k]) hiddenCount++; }
        pv.setSight(sight, hidden);
        StringBuilder sb = new StringBuilder();
        if (!blocked) {
            sb.append("✔ Точка 2 видна из точки 1\n");
        } else {
            sb.append("✖ Точка 2 не видна из точки 1\n")
              .append("Первое препятствие: ").append(Geo.dist(firstBlock)).append(" от точки 1\n")
              .append("Сильнее всего закрывает на ").append(Geo.dist(worstAt)).append(" (выше луча на ")
              .append(Math.round(worst)).append(" м)\n")
              .append("Цель станет видна на высоте ≈ ").append(Math.round(Math.ceil(need))).append(" м над землёй\n");
        }
        sb.append("Не видно с точки 1: ").append(Math.round(100.0 * hiddenCount / Math.max(1, total))).append("% линии");
        return sb.toString();
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
        if (roadsOverlay != null) roadsOverlay.setOffline(off);
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
                .setTitle("Вход в Рубеж")
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
        if (req == REQ_DICT) {
            if (res == RESULT_OK && data != null && dictTarget != null) {
                java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) {
                    String cur = dictTarget.getText().toString().trim();
                    String add = r.get(0);
                    dictTarget.setText(cur.isEmpty() ? Character.toUpperCase(add.charAt(0)) + add.substring(1) : cur + " " + add);
                    dictTarget.setSelection(dictTarget.getText().length());
                }
            }
            return;
        }
        if (req == REQ_QR) {
            if (res == RESULT_OK && data != null && data.getStringExtra("qr") != null) onQrResult(data.getStringExtra("qr"));
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_IMPORT) importFrom(uri);
        else if (req == REQ_RESTORE) restoreFrom(uri);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_MIC) {
            Toast.makeText(this, checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    ? "Микрофон разрешён — нажмите «Записать голос» ещё раз" : "Без микрофона голосовые заметки не записать", Toast.LENGTH_LONG).show();
        }
        if (req == REQ_STEPS) {
            if (pendingGuide != null && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                startGuide(pendingGuide, pendingBack, GUIDE_STEPS);
            else Toast.makeText(this, "Без доступа к шагомеру — выберите ведение по скорости или GPS", Toast.LENGTH_LONG).show();
            pendingGuide = null;
        }
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
                if (!o.has("points") && !o.has("routes")) throw new Exception("это не резервная копия Рубежа");
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

    // ================= Навигация к точке и по маршруту =================

    private static final int REQ_QR = 33;
    private List<double[]> navPts;
    private int navIdx;
    private String navName = "";
    private float devHeading = Float.NaN;
    private double speedEma = 0;
    private long navSensorAt = 0;
    private float navDecl = 0;

    private final android.hardware.SensorEventListener navSensor = new android.hardware.SensorEventListener() {
        private final float[] rm = new float[9], or = new float[3];
        private double s, c;
        private boolean init;

        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            android.hardware.SensorManager.getRotationMatrixFromVector(rm, e.values);
            android.hardware.SensorManager.getOrientation(rm, or);
            double az = Math.toRadians(Math.toDegrees(or[0]) + navDecl);
            if (!init) { s = Math.sin(az); c = Math.cos(az); init = true; }
            s += (Math.sin(az) - s) * 0.15;
            c += (Math.cos(az) - c) * 0.15;
            devHeading = (float) ((Math.toDegrees(Math.atan2(s, c)) + 360) % 360);
            long now = System.currentTimeMillis();
            if (now - navSensorAt > 120) {
                navSensorAt = now;
                myLoc.setHeading(devHeading);
                updateNav(null);
                map.invalidate();
            }
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor s, int a) {}
    };

    private boolean navOn() {
        return navPts != null;
    }

    private void startNav(List<double[]> pts, int idx, String name) {
        if (pts == null || pts.isEmpty()) return;
        if (guideOn()) stopGuide(false);
        navPts = new ArrayList<>(pts);
        navIdx = Math.max(0, Math.min(idx, navPts.size() - 1));
        navName = name;
        speedEma = 0;
        if (!locOn) startLocation(false);
        if (sensors == null) sensors = getSystemService(android.hardware.SensorManager.class);
        android.hardware.Sensor rv = sensors == null ? null : sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) {
            GeoPoint c = (GeoPoint) map.getMapCenter();
            navDecl = new android.hardware.GeomagneticField((float) c.getLatitude(), (float) c.getLongitude(), 0,
                    System.currentTimeMillis()).getDeclination();
            sensors.registerListener(navSensor, rv, android.hardware.SensorManager.SENSOR_DELAY_UI);
        }
        findViewById(R.id.navPanel).setVisibility(View.VISIBLE);
        myLoc.setNavTarget(navPts.get(navIdx));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateNav(null);
        map.invalidate();
    }

    private void stopNav() {
        navPts = null;
        if (sensors != null) sensors.unregisterListener(navSensor);
        devHeading = Float.NaN;
        myLoc.setHeading(Float.NaN);
        myLoc.setNavTarget(null);
        findViewById(R.id.navPanel).setVisibility(View.GONE);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        map.invalidate();
    }

    private void vibrate(long ms) {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    /** Обновить панель навигации; l — новая координата (или null, если сработал компас). */
    private void updateNav(android.location.Location l) {
        if (!navOn()) return;
        if (l != null && l.hasSpeed()) speedEma = speedEma == 0 ? l.getSpeed() : speedEma + (l.getSpeed() - speedEma) * 0.2;
        android.location.Location me = myLoc.getLocation();
        TextView dist = findViewById(R.id.navDist), sub = findViewById(R.id.navSub);
        ImageView arrow = findViewById(R.id.navArrow);
        String part = navPts.size() > 1 ? " · КТ " + (navIdx + 1) + "/" + navPts.size() : "";
        if (me == null) {
            dist.setText("Ищу спутники…");
            sub.setText(navName + part);
            arrow.setAlpha(0.35f);
            return;
        }
        double[] wp = navPts.get(navIdx);
        double[] inv = Geo.inverse(me.getLatitude(), me.getLongitude(), wp[0], wp[1]);
        if (l != null) {
            double radius = Math.max(12, Math.min(30, me.getAccuracy()));
            if (inv[0] <= radius) {
                if (navIdx < navPts.size() - 1) {
                    vibrate(250);
                    Toast.makeText(this, "КТ " + (navIdx + 1) + " пройдена", Toast.LENGTH_SHORT).show();
                    navIdx++;
                    myLoc.setNavTarget(navPts.get(navIdx));
                    updateNav(null);
                } else {
                    vibrate(700);
                    Toast.makeText(this, "Вы на месте: " + navName, Toast.LENGTH_LONG).show();
                    stopNav();
                }
                return;
            }
        }
        double remain = inv[0];
        for (int i = navIdx + 1; i < navPts.size(); i++) {
            double[] a = navPts.get(i - 1), b = navPts.get(i);
            remain += Geo.inverse(a[0], a[1], b[0], b[1])[0];
        }
        // направление «вверх» экрана: компас → курс GPS → ориентация карты
        float up;
        String src;
        if (!Float.isNaN(devHeading)) { up = devHeading; src = ""; }
        else if (me.hasBearing() && me.getSpeed() > 0.8f) { up = me.getBearing(); src = " · по ходу движения"; }
        else { up = -map.getMapOrientation(); src = " · стрелка по карте"; }
        arrow.setAlpha(1f);
        arrow.setRotation((float) (inv[1] - up));
        double sp = speedEma > 0.4 ? speedEma : 1.25;
        long min = Math.round(remain / sp / 60);
        String eta = min < 1 ? "<1 мин" : min < 60 ? min + " мин" : (min / 60) + " ч " + (min % 60) + " мин";
        dist.setText(Geo.dist(inv[0]) + (navPts.size() > 1 && navIdx < navPts.size() - 1 ? "  · всего " + Geo.dist(remain) : ""));
        sub.setText(String.format(Locale.US, "%.0f°", inv[1]) + " · д.у. "
                + Geo.mils(Geo.gridBearing(me.getLatitude(), me.getLongitude(), wp[0], wp[1]))
                + " · ~" + eta + (speedEma > 0.4 ? "" : " пешком") + part + "\n" + navName + src);
    }

    /** Индекс ближайшей к моему местоположению вершины. */
    private int nearestIdx(List<double[]> pts) {
        android.location.Location me = myLoc.getLocation();
        if (me == null) return 0;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double dd = Geo.inverse(me.getLatitude(), me.getLongitude(), pts.get(i)[0], pts.get(i)[1])[0];
            if (dd < bd) { bd = dd; best = i; }
        }
        return best;
    }

    private void navRoute(Routes.Route r, boolean back) {
        guideOptions(r, back);
    }

    // ================= Обмен по QR-коду =================

    private boolean qrShown;

    private void showQr(String payload, String title) {
        android.graphics.Bitmap bmp;
        try {
            bmp = QrCodec.bitmap(payload, (int) (getResources().getDisplayMetrics().widthPixels * 0.82));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось сделать QR-код: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        sheet("QR-код · " + title);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setBackgroundColor(Color.WHITE);
        int p = (int) dp(10);
        iv.setPadding(p, p, p, p);
        sheetContent.addView(iv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(this);
        t.setText("На другом телефоне: Меню → «Принять по QR» и наведите камеру на этот код. Интернет не нужен.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12.5f);
        t.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(t);
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);
        findViewById(R.id.nightFilter).setVisibility(View.GONE);
        qrShown = true;
    }

    private void scanQr() {
        try {
            startActivityForResult(new Intent(this, QrScanActivity.class), REQ_QR);
        } catch (Exception e) {
            Toast.makeText(this, "Сканер не запустился", Toast.LENGTH_LONG).show();
        }
    }

    private void onQrResult(String text) {
        QrCodec.Item it;
        try {
            it = QrCodec.decode(text);
        } catch (Exception e) {
            Toast.makeText(this, "Не получилось прочитать: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        long now = System.currentTimeMillis();
        if (it.point != null) {
            UserPoints.Point p = it.point;
            sheet("Получена точка");
            LinearLayout card = hCard();
            addKV(card, "Название", p.name);
            addKV(card, "СК-42", Sk42.format(p.lat, p.lon));
            addKV(card, "WGS-84", String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon));
            if (p.note != null && !p.note.isEmpty()) addKV(card, "Заметка", p.note);
            sheetContent.addView(card);
            footer("Отмена", "Добавить", v -> {
                p.id = Long.toString(now, 36);
                p.created = now;
                userPoints.add(p);
                savePoints();
                closeSheet();
                map.getController().animateTo(new GeoPoint(p.lat, p.lon), Math.max(14.0, map.getZoomLevelDouble()), 600L);
                Toast.makeText(this, "Точка «" + p.name + "» добавлена", Toast.LENGTH_SHORT).show();
            });
        } else {
            Routes.Route r = it.route;
            sheet(r.area ? "Получена зона" : "Получен маршрут");
            LinearLayout card = hCard();
            addKV(card, "Название", r.name);
            if (r.area) {
                addKV(card, "Площадь", Routes.areaText(Routes.area(r.pts)));
                addKV(card, "Периметр", Geo.dist(Routes.perimeter(r.pts)));
            } else {
                addKV(card, "Длина", Geo.dist(Routes.length(r.pts)));
            }
            addKV(card, "Точек", Integer.toString(r.pts.size()));
            sheetContent.addView(card);
            footer("Отмена", "Добавить", v -> {
                r.id = Long.toString(now, 36);
                r.created = now;
                routes.add(r);
                saveRoutes();
                closeSheet();
                fitRoute(r);
                Toast.makeText(this, "«" + r.name + "» добавлен(а)", Toast.LENGTH_SHORT).show();
            });
        }
    }

    // ================= Солнце и Луна =================

    private void showSun() {
        android.location.Location me = myLoc.getLocation();
        double lat, lon;
        String where;
        if (me != null) { lat = me.getLatitude(); lon = me.getLongitude(); where = "по моему местоположению"; }
        else { GeoPoint c = (GeoPoint) map.getMapCenter(); lat = c.getLatitude(); lon = c.getLongitude(); where = "по центру карты"; }
        sheet("Солнце и Луна");
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat day = new SimpleDateFormat("d MMMM, EEEE", new Locale("ru"));
        long now = System.currentTimeMillis();
        double alt = Astro.sunAltitude(now, lat, lon);
        String state = alt > 0 ? "день" : alt > -6 ? "гражданские сумерки" : alt > -12 ? "навигационные сумерки" : "ночь";
        TextView head = new TextView(this);
        head.setText("Сейчас: " + state + " · Солнце " + String.format(Locale.US, "%+.0f°", alt)
                + "\nРасчёт " + where + ", время телефона");
        head.setTextColor(getColor(R.color.hud_text2));
        head.setTextSize(12.5f);
        head.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(4));
        sheetContent.addView(head);

        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int k = 0; k < 2; k++) {
            java.util.Calendar c = (java.util.Calendar) cal.clone();
            c.add(java.util.Calendar.DAY_OF_YEAR, k);
            c.set(java.util.Calendar.HOUR_OF_DAY, 12);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            long noon = c.getTimeInMillis();
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            long midnight = c.getTimeInMillis();
            long[] s = Astro.sunTimes(noon, lat, lon);
            java.util.Calendar c2 = (java.util.Calendar) c.clone();
            c2.add(java.util.Calendar.DAY_OF_YEAR, 1);
            c2.set(java.util.Calendar.HOUR_OF_DAY, 12);
            long[] next = Astro.sunTimes(c2.getTimeInMillis(), lat, lon);
            hSection((k == 0 ? "СЕГОДНЯ · " : "ЗАВТРА · ") + day.format(new Date(noon)).toUpperCase(new Locale("ru")));
            LinearLayout card = hCard();
            addKV(card, "Начало навиг. сумерек", t(hm, s[4]));
            addKV(card, "Рассвет (гражд. сумерки)", t(hm, s[2]));
            addKV(card, "Восход Солнца", t(hm, s[0]));
            addKV(card, "Заход Солнца", t(hm, s[1]));
            addKV(card, "Конец гражд. сумерек", t(hm, s[3]));
            addKV(card, "Конец навиг. сумерек", t(hm, s[5]));
            if (s[0] > 0 && s[1] > 0) addKV(card, "Светлое время", dur(s[1] - s[0]));
            if (s[3] > 0 && next[2] > 0) addKV(card, "Тёмное время (ночь)", t(hm, s[3]) + "–" + t(hm, next[2]) + " · " + dur(next[2] - s[3]));
            long[] m = Astro.moonTimes(midnight, lat, lon);
            double[] il = Astro.moonIllumination(k == 0 ? now : midnight + 22 * 3600000L);
            addKV(card, "Восход Луны", t(hm, m[0]));
            addKV(card, "Заход Луны", t(hm, m[1]));
            addKV(card, k == 0 ? "Луна сейчас" : "Луна ночью", Astro.phaseName(il[1]) + " · освещена " + Math.round(il[0] * 100) + "%");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sheetContent.addView(card, lp);
        }
        double ma = Astro.moonAltitudeDeg(now, lat, lon);
        TextView foot = new TextView(this);
        foot.setText("Луна сейчас " + (ma > 0 ? "над горизонтом (" + Math.round(ma) + "°)" : "под горизонтом")
                + ". Точность времени — около 1–2 минут; рельеф и облачность не учитываются.");
        foot.setTextColor(getColor(R.color.hud_text2));
        foot.setTextSize(12);
        foot.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(foot);
    }

    private static String t(SimpleDateFormat f, long ms) {
        return ms <= 0 ? "—" : f.format(new Date(ms));
    }

    private static String dur(long ms) {
        long m = ms / 60000;
        return (m / 60) + " ч " + (m % 60) + " мин";
    }

    // ================= Инструменты =================

    private void showTools() {
        sheet("Инструменты");
        int tx = getColor(R.color.hud_text), blue = 0xFF42A5F5;
        hSection("ИЗМЕРЕНИЯ");
        toolTiles(
                toolTile(R.drawable.ic_ruler, "Линейка", tx, () -> startTool(TOOL_RULER, null)),
                toolTile(R.drawable.ic_area, "Зона и площадь", tx, () -> startTool(TOOL_AREA, null)),
                toolTile(R.drawable.ic_eye_los, "Видимость", tx, () -> {
                    startTool(TOOL_RULER, null);
                    Toast.makeText(this, "Поставьте 2 точки (откуда и куда смотреть), затем нажмите ⛰ «Профиль»", Toast.LENGTH_LONG).show();
                }));
        hSection("МАРШРУТЫ");
        toolTiles(
                toolTile(R.drawable.ic_route, "Маршрут по прямым", tx, () -> startTool(TOOL_ROUTE, null)),
                toolTile(R.drawable.ic_road, "Авто-маршрут пешком", blue, () -> { startTool(TOOL_ROUTE, null); setRoadMode("foot"); }),
                toolTile(R.drawable.ic_road, "Авто-маршрут на машине", blue, () -> { startTool(TOOL_ROUTE, null); setRoadMode("car"); }));
        toolTiles(
                toolTile(R.drawable.ic_nav, "Вести к цели", tx, () -> {
                    if (!hasTarget()) {
                        Toast.makeText(this, "Сначала выберите цель: точка → «Навести»", Toast.LENGTH_LONG).show();
                        return;
                    }
                    startNav(Collections.singletonList(new double[]{Double.parseDouble(prefs.getString("targetLat", "0")),
                            Double.parseDouble(prefs.getString("targetLon", "0"))}), 0, prefs.getString("targetName", "Цель"));
                }),
                toolTile(R.drawable.ic_route, "Мои маршруты и зоны", tx, this::showRoutes),
                toolTile(R.drawable.ic_my_location, track == null ? "Записать трек" : "Остановить трек", tx,
                        () -> setTracking(track == null)));
        hSection("ПРОЧЕЕ");
        toolTiles(
                toolTile(R.drawable.ic_sun, "Солнце и Луна", tx, this::showSun),
                toolTile(R.drawable.ic_qr, "Принять по QR", tx, this::scanQr),
                toolTile(R.drawable.ic_import, "Импорт KML/GPX", tx, () -> pickFile(REQ_IMPORT)));
    }

    private View toolTile(int icon, String label, int tint, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_tile);
        t.setPadding((int) dp(4), (int) dp(12), (int) dp(4), (int) dp(10));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        t.addView(iv, new LinearLayout.LayoutParams((int) dp(26), (int) dp(26)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTextColor(getColor(R.color.hud_text));
        tv.setTextSize(12);
        tv.setMaxLines(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(6);
        t.addView(tv, lp);
        t.setOnClickListener(v -> {
            closeSheet();
            action.run();
        });
        return t;
    }

    private void toolTiles(View... tiles) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        for (int i = 0; i < tiles.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(84), 1);
            if (i < tiles.length - 1) lp.setMarginEnd((int) dp(8));
            row.addView(tiles[i], lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.bottomMargin = (int) dp(8);
        sheetContent.addView(row, rl);
    }

    // ================= Ведение по маршруту (с GPS или без: по шагам / по скорости) =================

    private static final int GUIDE_GPS = 0, GUIDE_STEPS = 1, GUIDE_SPEED = 2, REQ_STEPS = 22;

    private static final class Key {
        int idx;
        double at;      // расстояние от начала, м
        String name;
        double turn;    // угол поворота, ° (+ направо), NaN — финиш
        boolean passed;
        final boolean[] said = new boolean[4];
    }

    private static final double[] SAY_AT = {500, 200, 100, 30};

    private List<double[]> gPts;
    private double[] gCum;
    private final List<Key> gKeys = new ArrayList<>();
    private int gSrc;
    private double gS;            // пройдено вдоль маршрута, м
    private double gSpeed = 1.3;  // м/с для режима «по скорости»
    private double gStep = 0.75;  // м на шаг
    private String gName = "";
    private boolean gPaused, gOffWarned, gBack;
    private long gStartAt;
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsOk;
    private float lastStepCount = -1;
    private Routes.Route pendingGuide;
    private boolean pendingBack;
    private final android.os.Handler gHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable gTick = new Runnable() {
        @Override
        public void run() {
            if (!guideOn()) return;
            if (gSrc == GUIDE_SPEED && !gPaused) {
                gS += gSpeed;
                updateGuide();
            }
            gHandler.postDelayed(this, 1000);
        }
    };

    private final android.hardware.SensorEventListener stepListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            if (!guideOn() || gSrc != GUIDE_STEPS || gPaused) return;
            int steps;
            if (e.sensor.getType() == android.hardware.Sensor.TYPE_STEP_DETECTOR) {
                steps = 1;
            } else {
                float v = e.values[0];
                steps = lastStepCount < 0 ? 0 : Math.round(v - lastStepCount);
                lastStepCount = v;
            }
            if (steps <= 0) return;
            gS += steps * gStep;
            updateGuide();
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor s, int a) {}
    };

    private boolean guideOn() {
        return gPts != null;
    }

    /** Выбор способа ведения по маршруту. */
    private void guideOptions(Routes.Route r, boolean back) {
        sheet((back ? "Обратно · " : "Вести · ") + r.name);
        TextView t = new TextView(this);
        t.setText("Голосом и вибрацией предупреждаю о поворотах и контрольных точках: за 500, 200, 100 и 30 м. "
                + "Без GPS положение считается по шагам или по скорости — поправить его можно, нажав на панель ведения.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12.5f);
        t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(t);
        int tx = getColor(R.color.hud_text);
        hRow(hIcon(R.drawable.ic_my_location, 0xFF42A5F5, 22), "По GPS", "Самый точный: положение со спутников", null,
                v -> { closeSheet(); startGuide(r, back, GUIDE_GPS); });
        hRow(hIcon(R.drawable.ic_route, tx, 22), "Без GPS — по шагам", "Шагомер телефона, шаг " + fmtM(gStep()), null,
                v -> { closeSheet(); startGuide(r, back, GUIDE_STEPS); });
        hSection("БЕЗ GPS — ПО СКОРОСТИ");
        final double[] spd = {prefs.getFloat("guideSpeed", 5f)};
        heightChipsUnits("Скорость, км/ч", new double[]{3, 5, 10, 20, 40, 60}, spd, " км/ч",
                v -> prefs.edit().putFloat("guideSpeed", (float) v).apply());
        hChipRow(hChip("Начать по скорости", R.drawable.ic_nav, true, v -> {
            closeSheet();
            gSpeed = spd[0] / 3.6;
            startGuide(r, back, GUIDE_SPEED);
        }));
    }

    private double gStep() {
        return prefs.getFloat("stepLen", 0.75f);
    }

    private static String fmtM(double m) {
        return String.format(Locale.US, "%.2f м", m).replace('.', ',');
    }

    /** Ряд кнопок выбора значения с единицами. */
    private void heightChipsUnits(String label, double[] values, double[] cur, String unit, DoubleCb cb) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.hud_text2));
        l.setTextSize(12);
        l.setPadding((int) dp(4), (int) dp(6), 0, (int) dp(4));
        sheetContent.addView(l);
        LinearLayout row = new LinearLayout(this);
        final List<TextView> all = new ArrayList<>();
        for (double v : values) {
            TextView c = new TextView(this);
            c.setText(Math.round(v) + unit);
            c.setGravity(android.view.Gravity.CENTER);
            c.setTextSize(12.5f);
            c.setSingleLine(true);
            c.setPadding(0, (int) dp(8), 0, (int) dp(8));
            c.setTag(v);
            all.add(c);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            p.setMarginEnd((int) dp(5));
            row.addView(c, p);
            c.setOnClickListener(x -> {
                cur[0] = v;
                for (TextView o : all) styleHeightChip(o, (double) o.getTag() == v);
                cb.on(v);
            });
            styleHeightChip(c, v == cur[0]);
        }
        sheetContent.addView(row);
    }

    private void startGuide(Routes.Route r, boolean back, int src) {
        if (r.pts.size() < 2) return;
        if (src == GUIDE_STEPS && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            pendingGuide = r;
            pendingBack = back;
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_STEPS);
            return;
        }
        if (navOn()) stopNav();
        if (guideOn()) stopGuide(false);
        if (back && r == track) setTracking(false);
        gPts = new ArrayList<>(r.pts);
        if (back) Collections.reverse(gPts);
        gName = (back ? "Обратно: " : "") + r.name;
        gBack = back;
        gSrc = src;
        gStep = gStep();
        gPaused = false;
        gOffWarned = false;
        gStartAt = System.currentTimeMillis();
        int n = gPts.size();
        gCum = new double[n];
        for (int i = 1; i < n; i++)
            gCum[i] = gCum[i - 1] + Geo.inverse(gPts.get(i - 1)[0], gPts.get(i - 1)[1], gPts.get(i)[0], gPts.get(i)[1])[0];
        buildKeys();
        gS = 0;
        if (src == GUIDE_GPS) {
            if (!locOn) startLocation(false);
            android.location.Location me = myLoc.getLocation();
            if (me != null) gS = project(me.getLatitude(), me.getLongitude(), -1);
        } else {
            if (locOn) stopLocation();
            if (src == GUIDE_STEPS) {
                if (sensors == null) sensors = getSystemService(android.hardware.SensorManager.class);
                android.hardware.Sensor det = sensors == null ? null : sensors.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR);
                android.hardware.Sensor cnt = sensors == null ? null : sensors.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER);
                lastStepCount = -1;
                if (det != null) sensors.registerListener(stepListener, det, android.hardware.SensorManager.SENSOR_DELAY_FASTEST);
                else if (cnt != null) sensors.registerListener(stepListener, cnt, android.hardware.SensorManager.SENSOR_DELAY_FASTEST);
                else {
                    Toast.makeText(this, "В телефоне нет шагомера — выберите ведение по скорости", Toast.LENGTH_LONG).show();
                    gPts = null;
                    return;
                }
            }
        }
        for (Key k : gKeys) if (k.at < gS - 1) k.passed = true;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        findViewById(R.id.navPanel).setVisibility(View.VISIBLE);
        follow = true;
        gHandler.removeCallbacks(gTick);
        gHandler.post(gTick);
        initTts();
        Key first = nextKey();
        say("Начинаю ведение. " + gName + ". Всего " + spokenDist(gCum[n - 1])
                + (first != null ? ". Первая точка " + first.name + " через " + spokenDist(first.at - gS) : ""));
        updateGuide();
    }

    private void stopGuide(boolean speak) {
        if (!guideOn()) return;
        gPts = null;
        gHandler.removeCallbacks(gTick);
        if (sensors != null) sensors.unregisterListener(stepListener);
        findViewById(R.id.navPanel).setVisibility(View.GONE);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (gSrc != GUIDE_GPS) {
            myLoc.setLocation(null);
            myLoc.setHeading(Float.NaN);
        }
        if (speak) say("Ведение остановлено");
        map.invalidate();
    }

    /** Ключевые точки: вершины с заметным поворотом, свои точки рядом с маршрутом, финиш. */
    private void buildKeys() {
        gKeys.clear();
        int n = gPts.size();
        boolean sparse = n <= 40;
        for (int i = 1; i < n - 1; i++) {
            double[] inOut = turnAt(i, sparse ? 0 : 30);
            double turn = inOut[0];
            String own = ownPointNear(gPts.get(i), 40);
            if (sparse || Math.abs(turn) >= 35 || own != null) {
                if (!sparse && own == null && !gKeys.isEmpty() && gCum[i] - gKeys.get(gKeys.size() - 1).at < 40) continue;
                Key k = new Key();
                k.idx = i;
                k.at = gCum[i];
                k.turn = turn;
                k.name = own != null ? own : sparse ? "КТ " + (gBack ? n - i : i + 1) : "поворот";
                gKeys.add(k);
            }
        }
        Key fin = new Key();
        fin.idx = n - 1;
        fin.at = gCum[n - 1];
        fin.turn = Double.NaN;
        String own = ownPointNear(gPts.get(n - 1), 40);
        fin.name = own != null ? own : "конечная точка";
        gKeys.add(fin);
    }

    /** Угол поворота в вершине i; look — сколько метров брать до и после (0 — соседние вершины). */
    private double[] turnAt(int i, double look) {
        int a = i - 1, b = i + 1;
        if (look > 0) {
            while (a > 0 && gCum[i] - gCum[a] < look) a--;
            while (b < gPts.size() - 1 && gCum[b] - gCum[i] < look) b++;
        }
        double[] p = gPts.get(i);
        double azIn = Geo.inverse(gPts.get(a)[0], gPts.get(a)[1], p[0], p[1])[1];
        double azOut = Geo.inverse(p[0], p[1], gPts.get(b)[0], gPts.get(b)[1])[1];
        double d = ((azOut - azIn) % 360 + 540) % 360 - 180;
        return new double[]{d, azOut};
    }

    private String ownPointNear(double[] p, double radius) {
        String best = null;
        double bd = radius;
        for (UserPoints.Point up : userPoints) {
            double d = Geo.inverse(p[0], p[1], up.lat, up.lon)[0];
            if (d <= bd) { bd = d; best = up.name; }
        }
        return best;
    }

    private static String turnWords(double t) {
        if (Double.isNaN(t)) return "";
        double a = Math.abs(t);
        String side = t > 0 ? "направо" : "налево";
        if (a < 20) return "прямо";
        if (a < 45) return "плавно " + side;
        if (a < 135) return side;
        if (a < 165) return "резко " + side;
        return "разворот";
    }

    private Key nextKey() {
        for (Key k : gKeys) if (!k.passed) return k;
        return null;
    }

    /** Точка на маршруте на расстоянии s от начала и азимут участка. */
    private double[] pointAt(double s) {
        int n = gPts.size();
        if (s <= 0) return new double[]{gPts.get(0)[0], gPts.get(0)[1], Geo.inverse(gPts.get(0)[0], gPts.get(0)[1], gPts.get(1)[0], gPts.get(1)[1])[1]};
        int i = 1;
        while (i < n - 1 && gCum[i] < s) i++;
        double span = gCum[i] - gCum[i - 1];
        double t = span <= 0 ? 1 : Math.min(1, (s - gCum[i - 1]) / span);
        double[] a = gPts.get(i - 1), b = gPts.get(i);
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, Geo.inverse(a[0], a[1], b[0], b[1])[1]};
    }

    /** Проекция координаты на маршрут → расстояние от начала. near≥0 — искать рядом с этим местом. Отклонение — в gOff. */
    private double gOff;

    private double project(double lat, double lon, double near) {
        double best = Double.MAX_VALUE, bestS = 0;
        double kx = Math.cos(Math.toRadians(lat)) * 111320, ky = 110574;
        for (int i = 1; i < gPts.size(); i++) {
            if (near >= 0 && (gCum[i] < near - 300 || gCum[i - 1] > near + 800)) continue;
            double[] a = gPts.get(i - 1), b = gPts.get(i);
            double ax = (a[1] - lon) * kx, ay = (a[0] - lat) * ky, bx = (b[1] - lon) * kx, by = (b[0] - lat) * ky;
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
            double px = ax + dx * t, py = ay + dy * t;
            double d = Math.hypot(px, py);
            if (d < best) { best = d; bestS = gCum[i - 1] + (gCum[i] - gCum[i - 1]) * t; }
        }
        gOff = best;
        return bestS;
    }

    private void guideLocation(android.location.Location l) {
        if (!guideOn() || gSrc != GUIDE_GPS) return;
        double s = project(l.getLatitude(), l.getLongitude(), gS);
        if (gOff > 80) s = project(l.getLatitude(), l.getLongitude(), -1);
        if (gOff > 60) {
            if (!gOffWarned) {
                gOffWarned = true;
                vibrate(500);
                say("Внимание. Вы ушли с маршрута на " + spokenDist(gOff));
            }
        } else {
            gOffWarned = false;
            gS = Math.max(gS - 30, s); // не прыгать назад из-за погрешности
        }
        updateGuide();
    }

    private void updateGuide() {
        if (!guideOn()) return;
        double total = gCum[gCum.length - 1];
        gS = Math.max(0, Math.min(total, gS));
        double[] here = pointAt(gS);
        if (gSrc != GUIDE_GPS) {
            android.location.Location fake = new android.location.Location("route");
            fake.setLatitude(here[0]);
            fake.setLongitude(here[1]);
            fake.setAccuracy(0);
            myLoc.setLocation(fake);
            myLoc.setHeading((float) here[2]);
            if (follow) map.getController().animateTo(new GeoPoint(here[0], here[1]));
        }
        // объявления
        boolean gps = gSrc == GUIDE_GPS;
        Key k = nextKey();
        while (k != null) {
            double dk = k.at - gS;
            double passR = gps ? 12 : 2;
            if (dk <= passR) {
                k.passed = true;
                vibrate(Double.isNaN(k.turn) ? 800 : 300);
                if (Double.isNaN(k.turn)) {
                    say("Вы прибыли. " + k.name);
                    Toast.makeText(this, "Маршрут пройден", Toast.LENGTH_LONG).show();
                    stopGuide(false);
                    return;
                }
                Key nx = nextKey();
                say(k.name + ". " + capital(turnWords(k.turn))
                        + (nx != null ? ". Дальше " + spokenDist(nx.at - k.at) + " до " + nx.name : ""));
                k = nx;
                continue;
            }
            Key prev = null;
            for (Key x : gKeys) { if (x == k) break; prev = x; }
            double legLen = k.at - (prev == null ? 0 : prev.at);
            int jj = -1;
            for (int j = 0; j < SAY_AT.length; j++) if (SAY_AT[j] >= dk) jj = j;
            if (jj >= 0 && !k.said[jj]) {
                for (int j = 0; j <= jj; j++) k.said[j] = true;
                boolean last = jj == SAY_AT.length - 1;
                if (last || legLen >= SAY_AT[jj] + 40) {
                    String tw = turnWords(k.turn);
                    say("Через " + spokenDist(last ? Math.max(10, dk) : SAY_AT[jj]) + " " + k.name
                            + (Double.isNaN(k.turn) ? ", финиш" : tw.equals("прямо") ? "" : ", " + tw));
                    if (jj >= 2) vibrate(150);
                }
            }
            break;
        }
        // панель
        TextView dist = findViewById(R.id.navDist), sub = findViewById(R.id.navSub);
        ImageView arrow = findViewById(R.id.navArrow);
        arrow.setAlpha(1f);
        if (k != null) {
            double dk = Math.max(0, k.at - gS);
            dist.setText(Geo.dist(dk) + " · " + k.name);
            String tw = Double.isNaN(k.turn) ? "финиш" : turnWords(k.turn);
            arrow.setRotation(dk < 250 && !Double.isNaN(k.turn) ? (float) k.turn : 0f);
            double sp = gSrc == GUIDE_SPEED ? gSpeed : gps && speedEma > 0.4 ? speedEma : 1.25;
            if (gSrc == GUIDE_STEPS) {
                double el = (System.currentTimeMillis() - gStartAt) / 1000.0;
                if (el > 60 && gS > 50) sp = gS / el;
            }
            long min = Math.round((total - gS) / sp / 60);
            String src = gps ? "GPS" : gSrc == GUIDE_STEPS ? "по шагам" : "по скорости " + Math.round(gSpeed * 3.6) + " км/ч";
            sub.setText("затем " + tw + (Double.isNaN(k.turn) ? "" : String.format(Locale.US, " %.0f°", Math.abs(k.turn)))
                    + " · пройдено " + Geo.dist(gS) + " из " + Geo.dist(total) + " · ~" + durText(min * 60.0)
                    + "\n" + gName + " · " + src + (gPaused ? " · ПАУЗА" : "") + (gps ? "" : " · нажмите для поправки"));
        }
        map.invalidate();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String spokenDist(double m) {
        if (m < 1000) return (Math.round(m / 10.0) * 10) + " метров";
        return String.format(Locale.US, "%.1f", m / 1000).replace(".0", "").replace('.', ',') + " километра";
    }

    /** Поправка положения без GPS. */
    private void guideAdjust() {
        if (!guideOn()) return;
        sheet("Поправка положения");
        TextView t = new TextView(this);
        t.setText("Сейчас: пройдено " + Geo.dist(gS) + ". Если метка на маршруте отстаёт или убежала вперёд — поправьте.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12.5f);
        t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(t);
        hChipRow(hChip("−100 м", 0, false, v -> shiftGuide(-100)), hChip("−20 м", 0, false, v -> shiftGuide(-20)),
                hChip("+20 м", 0, false, v -> shiftGuide(20)), hChip("+100 м", 0, false, v -> shiftGuide(100)));
        hChipRow(hChip("Я у следующей точки", R.drawable.ic_flag, true, v -> {
            Key k = nextKey();
            if (k != null) { gS = k.at - (gSrc == GUIDE_GPS ? 12 : 1.5); updateGuide(); }
            closeSheet();
        }));
        hChipRow(hChip("Я здесь — по прицелу карты", R.drawable.ic_crosshair_add, false, v -> {
            closeSheet();
            follow = false;
            Toast.makeText(this, "Наведите прицел на своё место на маршруте и нажмите на панель ещё раз → «Поставить по прицелу»", Toast.LENGTH_LONG).show();
            aimMode = true;
        }));
        if (aimMode) {
            hChipRow(hChip("Поставить по прицелу", R.drawable.ic_check, true, v -> {
                GeoPoint c = (GeoPoint) map.getMapCenter();
                double s = project(c.getLatitude(), c.getLongitude(), -1);
                for (Key k : gKeys) {
                    k.passed = k.at < s - 1;
                    java.util.Arrays.fill(k.said, k.passed);
                }
                gS = s;
                aimMode = false;
                follow = true;
                closeSheet();
                updateGuide();
                Toast.makeText(this, gOff > 100 ? "Прицел далеко от маршрута — взята ближайшая точка маршрута" : "Положение поправлено",
                        Toast.LENGTH_SHORT).show();
            }));
        }
        hChipRow(hChip(gPaused ? "Продолжить" : "Пауза", 0, false, v -> {
            gPaused = !gPaused;
            closeSheet();
            say(gPaused ? "Пауза" : "Продолжаю");
            updateGuide();
        }), hChip("Остановить", R.drawable.ic_close, false, v -> { closeSheet(); stopGuide(true); }));
        if (gSrc == GUIDE_STEPS) {
            hSection("ДЛИНА ШАГА");
            final double[] st = {gStep};
            heightChipsUnits("Подберите, если метка отстаёт или спешит", new double[]{0.6, 0.65, 0.7, 0.75, 0.8, 0.9}, st, "",
                    vv -> { gStep = vv; prefs.edit().putFloat("stepLen", (float) vv).apply(); });
            // подписи «0,6 м» вместо округлённых
            LinearLayout last = (LinearLayout) sheetContent.getChildAt(sheetContent.getChildCount() - 1);
            for (int i = 0; i < last.getChildCount(); i++) {
                TextView c = (TextView) last.getChildAt(i);
                c.setText(String.format(Locale.US, "%.2f", (double) c.getTag()).replace('.', ','));
            }
        }
        if (gSrc == GUIDE_SPEED) {
            hSection("СКОРОСТЬ");
            final double[] sp = {Math.round(gSpeed * 3.6)};
            heightChipsUnits("км/ч", new double[]{3, 5, 10, 20, 40, 60}, sp, "", vv -> gSpeed = vv / 3.6);
        }
    }

    private boolean aimMode;

    private void shiftGuide(double m) {
        gS += m;
        for (Key k : gKeys) if (k.at > gS + 1) { k.passed = false; java.util.Arrays.fill(k.said, false); }
        updateGuide();
    }

    private void initTts() {
        if (tts != null) return;
        tts = new android.speech.tts.TextToSpeech(this, status -> {
            if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                Toast.makeText(this, "Голосовые подсказки недоступны — только экран и вибрация", Toast.LENGTH_LONG).show();
                return;
            }
            int r = tts.setLanguage(new Locale("ru", "RU"));
            ttsOk = r != android.speech.tts.TextToSpeech.LANG_MISSING_DATA && r != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ttsOk)
                Toast.makeText(this, "Нет русского голоса в телефоне (Настройки → Синтез речи) — подсказки только на экране",
                        Toast.LENGTH_LONG).show();
        });
    }

    private void say(String text) {
        ((TextView) findViewById(R.id.navSub)).setContentDescription(text);
        if (!prefs.getBoolean("guideVoice", true)) return;
        if (tts != null && ttsOk) tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "g" + System.nanoTime());
        else if (tts == null) initTts();
    }

    // ================= Архив снимков (Esri Wayback) =================

    private Thread wbThread;

    private void showWayback() {
        showWayback(false);
    }

    private void showWayback(final boolean compare) {
        if (offline()) {
            Toast.makeText(this, "Архив снимков доступен только с интернетом", Toast.LENGTH_LONG).show();
            return;
        }
        GeoPoint c = (GeoPoint) map.getMapCenter();
        final double lat = c.getLatitude(), lon = c.getLongitude();
        sheet(compare ? "Сравнить: снимок слева" : "Дата снимка");
        TextView head = new TextView(this);
        head.setText("Снимки, которые были в этом месте (центр карты) в разные годы. "
                + "Выбранная дата применяется ко всей карте — в других местах будет снимок того же выпуска.");
        head.setTextColor(getColor(R.color.hud_text2));
        head.setTextSize(12.5f);
        head.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(head);
        int cur = prefs.getInt("wbNum", 0);
        hRow(hIcon(cur == 0 ? R.drawable.ic_check : R.drawable.ic_layers, cur == 0 ? getColor(R.color.hud_accent) : getColor(R.color.hud_text), 22),
                "Самый свежий", "Обычная подложка «Спутник»", null,
                v -> { if (compare) { closeSheet(); startCompare(0, "", ""); } else chooseWayback(0, "", ""); });
        final TextView st = new TextView(this);
        st.setText("Ищу снимки разных лет…");
        st.setTextColor(getColor(R.color.hud_text2));
        st.setTextSize(13);
        st.setPadding((int) dp(4), (int) dp(12), (int) dp(4), (int) dp(4));
        sheetContent.addView(st);
        final LinearLayout host = sheetContent;
        if (wbThread != null) wbThread.interrupt();
        wbThread = new Thread(() -> {
            try {
                List<Wayback.Version> vs = Wayback.scan(getApplicationContext(), lat, lon,
                        t -> runOnUiThread(() -> st.setText(t)));
                runOnUiThread(() -> {
                    if (sheetContent != host || !sheetOpen()) return;
                    st.setText(vs.isEmpty() ? "Архивных снимков здесь не найдено." : "НАЙДЕНО СНИМКОВ: " + vs.size());
                    int now = prefs.getInt("wbNum", 0);
                    for (Wayback.Version v : vs) {
                        String date = v.captured.isEmpty() ? "дата неизвестна" : v.captured;
                        String sub = (v.res.isEmpty() ? "" : v.res + " · ") + (v.provider.isEmpty() ? "" : v.provider + " · ")
                                + "выпуск " + v.rel.date;
                        boolean on = v.rel.num == now;
                        hRow(hIcon(on ? R.drawable.ic_check : R.drawable.ic_layers, on ? getColor(R.color.hud_accent) : getColor(R.color.hud_text), 22),
                                "Снимок " + date, sub, null, x -> {
                                    if (compare) { closeSheet(); startCompare(v.rel.num, v.rel.url, date); }
                                    else chooseWayback(v.rel.num, v.rel.url, date);
                                });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> st.setText("Не удалось загрузить архив: " + e.getMessage()));
            }
        });
        wbThread.start();
    }

    private void chooseWayback(int num, String url, String label) {
        if (num == 0) prefs.edit().remove("wbNum").remove("wbUrl").remove("wbLabel").apply();
        else prefs.edit().putInt("wbNum", num).putString("wbUrl", url).putString("wbLabel", label).apply();
        closeSheet();
        applyStyle(STYLE_SAT);
        Toast.makeText(this, num == 0 ? "Самый свежий снимок" : "Снимок " + label, Toast.LENGTH_SHORT).show();
    }

    // ================= Сравнение двух снимков (шторка) =================

    private CompareOverlay cmp;
    private android.widget.FrameLayout cmpLayer;
    private RotationGestureOverlay rotGesture;

    private boolean compareOn() {
        return cmp != null;
    }

    /** Слева от шторки — снимок num (0 — самый свежий), справа — текущая подложка. */
    private void startCompare(int num, String url, String label) {
        stopCompare();
        if (currentStyle != STYLE_SAT) applyStyle(STYLE_SAT);
        if (compassMode) setCompassMode(false);
        resetNorth();
        if (rotGesture != null) rotGesture.setEnabled(false);
        OnlineTileSourceBase src = num > 0 ? new WaybackSource(num, url) : SAT;
        MapTileProviderBasic prov = new MapTileProviderBasic(getApplicationContext(), src);
        cmp = new CompareOverlay(prov, getApplicationContext());
        map.getOverlays().add(1, cmp);

        View root = findViewById(R.id.mapRoot);
        cmpLayer = new android.widget.FrameLayout(this);
        cmpLayer.setElevation(dp(8));
        ((android.widget.FrameLayout) root).addView(cmpLayer, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // шторка: линия + круглая ручка
        final android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        View line = new View(this);
        line.setBackgroundColor(Color.WHITE);
        android.widget.FrameLayout.LayoutParams ll = new android.widget.FrameLayout.LayoutParams((int) dp(2.5f), -1, android.view.Gravity.CENTER_HORIZONTAL);
        bar.addView(line, ll);
        TextView knob = new TextView(this);
        knob.setText("‹ ›");
        knob.setTextSize(18);
        knob.setTextColor(0xFF1B1F24);
        knob.setGravity(android.view.Gravity.CENTER);
        GradientDrawable kb = new GradientDrawable();
        kb.setShape(GradientDrawable.OVAL);
        kb.setColor(Color.WHITE);
        kb.setStroke((int) dp(2), 0x55000000);
        knob.setBackground(kb);
        knob.setElevation(dp(4));
        bar.addView(knob, new android.widget.FrameLayout.LayoutParams((int) dp(46), (int) dp(46), android.view.Gravity.CENTER));
        final int barW = (int) dp(52);
        cmpLayer.addView(bar, new android.widget.FrameLayout.LayoutParams(barW, -1));

        final TextView left = cmpLabel("◀ " + (num > 0 ? "Снимок " + label : "Самый свежий"));
        final TextView right = cmpLabel((prefs.getInt("wbNum", 0) > 0 ? "Снимок " + prefs.getString("wbLabel", "") : "Сейчас") + " ▶");
        int top = findViewById(R.id.topBar).getBottom() + (int) dp(64);
        android.widget.FrameLayout.LayoutParams lp1 = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.START | android.view.Gravity.TOP);
        lp1.setMargins((int) dp(12), top, 0, 0);
        cmpLayer.addView(left, lp1);
        android.widget.FrameLayout.LayoutParams lp2 = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END | android.view.Gravity.TOP);
        lp2.setMargins(0, top, (int) dp(12), 0);
        cmpLayer.addView(right, lp2);

        TextView close = cmpLabel("✕  Закончить сравнение");
        close.setTextSize(14);
        int p = (int) dp(14);
        close.setPadding(p, (int) dp(10), p, (int) dp(10));
        close.setOnClickListener(v -> stopCompare());
        android.widget.FrameLayout.LayoutParams lp3 = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM);
        lp3.bottomMargin = findViewById(R.id.bottomBar).getHeight() + (int) dp(12);
        cmpLayer.addView(close, lp3);

        final float[] pos = {map.getWidth() / 2f};
        Runnable place = () -> {
            bar.setTranslationX(pos[0] - barW / 2f);
            cmp.setSplit(pos[0]);
            map.invalidate();
        };
        bar.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE || e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                pos[0] = Math.max(dp(8), Math.min(map.getWidth() - dp(8), e.getRawX() - root.getLeft()));
                bar.setTranslationX(pos[0] - barW / 2f);
                cmp.setSplit(pos[0]);
                map.invalidate();
            }
            return true;
        });
        cmpLayer.post(place);
        Toast.makeText(this, "Двигайте шторку пальцем. Поворот карты на время сравнения выключен", Toast.LENGTH_LONG).show();
    }

    private TextView cmpLabel(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(12.5f);
        v.setBackgroundResource(R.drawable.bg_hud_solid_pill);
        int p = (int) dp(10);
        v.setPadding(p, (int) dp(6), p, (int) dp(6));
        v.setElevation(dp(4));
        return v;
    }

    private void stopCompare() {
        if (cmp != null) {
            map.getOverlays().remove(cmp);
            cmp.onDetach(map);
            cmp = null;
        }
        if (cmpLayer != null) {
            ((android.widget.FrameLayout) findViewById(R.id.mapRoot)).removeView(cmpLayer);
            cmpLayer = null;
        }
        if (rotGesture != null) rotGesture.setEnabled(true);
        map.invalidate();
    }

    // ================= Голосовые заметки к точкам =================

    private static final int REQ_MIC = 23, REQ_DICT = 34;
    private android.media.MediaRecorder recorder;
    private android.media.MediaPlayer player;
    private long recStart;
    private EditText dictTarget;
    private final android.os.Handler recHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private File voiceFile(String id) {
        File d = new File(getFilesDir(), "voice");
        if (!d.exists()) d.mkdirs();
        return new File(d, (id == null ? "_draft" : id) + ".m4a");
    }

    private static String mmss(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private long voiceDuration(File f) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            r.release();
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ряд кнопок голосовой заметки в окне точки. draft[0] — файл записи (черновик или файл точки). */
    private void fillVoiceRow(LinearLayout row, File[] file, EditText note) {
        row.removeAllViews();
        List<View> chips = new ArrayList<>();
        chips.add(hChip("Надиктовать", R.drawable.ic_mic, false, v -> dictate(note)));
        if (recorder != null) {
            TextView stop = hChip("Стоп " + mmss(System.currentTimeMillis() - recStart), R.drawable.ic_stop, true, v -> {
                stopRecording();
                fillVoiceRow(row, file, note);
            });
            chips.add(stop);
            recHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (recorder == null || !stop.isAttachedToWindow()) return;
                    long el = System.currentTimeMillis() - recStart;
                    stop.setText("Стоп " + mmss(el));
                    if (el > 180_000) { stopRecording(); fillVoiceRow(row, file, note); return; }
                    recHandler.postDelayed(this, 500);
                }
            }, 500);
        } else if (file[0].exists() && file[0].length() > 0) {
            boolean playing = player != null && player.isPlaying();
            chips.add(hChip(playing ? "Стоп" : "Слушать " + mmss(voiceDuration(file[0])), playing ? R.drawable.ic_stop : R.drawable.ic_play,
                    true, v -> {
                        if (player != null) stopPlaying();
                        else playVoice(file[0], () -> fillVoiceRow(row, file, note));
                        fillVoiceRow(row, file, note);
                    }));
            chips.add(hChip("Удалить", R.drawable.ic_delete, false, v -> {
                stopPlaying();
                file[0].delete();
                fillVoiceRow(row, file, note);
            }));
        } else {
            chips.add(hChip("Записать голос", R.drawable.ic_rec, false, v -> {
                if (startRecording(file[0])) fillVoiceRow(row, file, note);
            }));
        }
        for (int i = 0; i < chips.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(42), 1);
            if (i < chips.size() - 1) lp.setMarginEnd((int) dp(8));
            row.addView(chips.get(i), lp);
        }
    }

    private boolean startRecording(File f) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return false;
        }
        stopPlaying();
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new android.media.MediaRecorder(this) : new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setAudioSamplingRate(22050);
            recorder.setOutputFile(f.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recStart = System.currentTimeMillis();
            vibrate(60);
            return true;
        } catch (Exception e) {
            if (recorder != null) recorder.release();
            recorder = null;
            Toast.makeText(this, "Микрофон недоступен: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void stopRecording() {
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        recorder.release();
        recorder = null;
        vibrate(60);
    }

    private void playVoice(File f, Runnable onDone) {
        stopPlaying();
        try {
            player = new android.media.MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> { stopPlaying(); onDone.run(); });
            player.prepare();
            player.start();
        } catch (Exception e) {
            stopPlaying();
            Toast.makeText(this, "Не удалось воспроизвести", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlaying() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }

    /** Надиктовать текст заметки (распознавание речи телефона). */
    private void dictate(EditText note) {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говорите заметку");
        dictTarget = note;
        try {
            startActivityForResult(i, REQ_DICT);
        } catch (Exception e) {
            Toast.makeText(this, "В телефоне нет распознавания речи — используйте «Записать голос»", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("unused")
    private static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
