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
import android.view.ViewGroup;
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
import java.util.Map;
import java.util.HashMap;
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
        roadsOverlay.setLevel(prefs.getInt("roadsLevel", 1));
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
        findViewById(R.id.rightCol).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) ->
                placesOverlay.setExclude(new android.graphics.RectF(l - dp(6), t - dp(6), r + dp(6), b + dp(6))));
        map.getOverlays().add(placesOverlay);
        pathOverlay = new PathOverlay(d);
        map.getOverlays().add(pathOverlay);
        groupPtsOverlay = new PointsOverlay(d);
        groupPtsOverlay.setGroup(true);
        map.getOverlays().add(groupPtsOverlay);
        pointsOverlay = new PointsOverlay(d);
        map.getOverlays().add(pointsOverlay);
        membersOverlay = new MembersOverlay(d);
        membersOverlay.setListener(this::showMember);
        map.getOverlays().add(membersOverlay);
        myLoc = new MyLocationOverlay(d);
        map.getOverlays().add(myLoc);
        reticleOverlay = new ReticleOverlay(d);
        map.getOverlays().add(reticleOverlay);
        map.getOverlays().add(new CompassSync());

        userPoints = UserPoints.load(this);
        pointsOverlay.setPoints(userPoints);
        pointsOverlay.setListener(this::onPointTap);
        routes = Routes.load(this);
        pathOverlay.setSaved(allRoutes());
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
        setupChat();
        refreshGroupMap();
        groupPtsOverlay.setListener(p -> {
            ChatClient.GObj g = chat.object(p.id);
            if (g != null) groupObjSheet(g);
        });
        if (prefs.getBoolean("locOn", false) && hasLocPerm()) startLocation(false);
        checkUpdates();
        if (savedInstanceState == null) handleViewIntent(getIntent());
        if (savedInstanceState == null) map.post(() -> handleGroupIntent(getIntent()));

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
            map.setTileSource(TileSourceFactory.MAPNIK); // CARTO теперь требует ключ
            attr.setText("© OpenStreetMap");
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
        placesOverlay.setEnabled(ru && style != STYLE_SCHEME); // на схеме свои подписи уже есть
        updateCoords();
        map.invalidate();
    }

    // ================= Панели в своём стиле (вместо стандартных окон) =================

    private LinearLayout sheetContent, sheetFooter;

    private int insetTop;

    /** Панель на весь экран (чат) или по содержимому (остальные). */
    private void setSheetFull(boolean full) {
        View panel = findViewById(R.id.genPanel), sc = findViewById(R.id.genScroll);
        android.widget.FrameLayout.LayoutParams pl = (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
        pl.height = full ? android.widget.FrameLayout.LayoutParams.MATCH_PARENT : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        pl.topMargin = full ? insetTop + (int) dp(6) : 0;
        panel.setLayoutParams(pl);
        LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) sc.getLayoutParams();
        sl.height = full ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT;
        sl.weight = full ? 1 : 0;
        sc.setLayoutParams(sl);
    }

    private View chatHeader;

    private void sheet(String title) {
        setSheetFull(false);
        if (chatHeader != null) {
            ((ViewGroup) chatHeader.getParent()).removeView(chatHeader);
            chatHeader = null;
        }
        hideEmoji();
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
        chatOpen = false;
        chatList = null;
        chatStatus = null;
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

    private LinearLayout hChipRow(View... chipsIn) {
        List<View> nn = new ArrayList<>();
        for (View v : chipsIn) if (v != null) nn.add(v);
        View[] chips = nn.toArray(new View[0]);
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
        // ---------- подложка: карточки с миниатюрой текущего места ----------
        hSection("ПОДЛОЖКА");
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        final String[] names = {"Спутник", "Рельеф", "Топо", "Схема"};
        final int[] idx = {STYLE_SAT, STYLE_RELIEF, STYLE_TOPO, STYLE_SCHEME};
        final int[] icons = {R.drawable.ic_layers, R.drawable.ic_mountain, R.drawable.ic_lbs, R.drawable.ic_grid};
        final List<View> cards = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int st = idx[i];
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            card.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(6));
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageResource(icons[i]);
            img.setImageTintList(ColorStateList.valueOf(getColor(R.color.hud_text2)));
            int ip = (int) dp(18);
            img.setPadding(ip, ip, ip, ip);
            GradientDrawable ib = new GradientDrawable();
            ib.setCornerRadius(dp(10));
            ib.setColor(0xFF22262C);
            img.setBackground(ib);
            img.setClipToOutline(true);
            card.addView(img, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(62)));
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(12.5f);
            t.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tl.topMargin = (int) dp(5);
            card.addView(t, tl);
            card.setTag(st);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i < 3) lp.setMarginEnd((int) dp(8));
            card.setOnClickListener(v -> {
                applyStyle(st);
                for (View c : cards) styleLayerCard(c, (int) c.getTag() == currentStyle);
                showLayers();
            });
            cards.add(card);
            seg.addView(card, lp);
            styleLayerCard(card, st == currentStyle);
            loadThumb(st, img);
        }
        sheetContent.addView(seg);

        if (currentStyle == STYLE_SAT) {
            LinearLayout box = layerBox();
            layerLink(box, R.drawable.ic_refresh, "Дата снимка",
                    prefs.getInt("wbNum", 0) > 0 ? "архив · " + prefs.getString("wbLabel", "") : "самый свежий", v -> showWayback());
            layerDivider(box);
            layerLink(box, R.drawable.ic_compare, "Сравнить два снимка", compareOn() ? "включено" : "шторкой",
                    v -> { if (compareOn()) { stopCompare(); closeSheet(); } else showWayback(true); });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bl.topMargin = (int) dp(10);
            sheetContent.addView(box, bl);
        }

        // ---------- слои плитками-переключателями ----------
        hSection("ПОВЕРХ КАРТЫ");
        roadsCard();
        layerGrid(
                layerTile(R.drawable.ic_rail, "Ж/д пути", OV_KEYS[1], this::applyOverlays),
                layerTile(R.drawable.ic_mountain, "Отмывка рельефа", "hillshade", () -> { applyHillshade(); map.invalidate(); }),
                layerTile(R.drawable.ic_grid, "Сетка СК-42", "gridOn", this::applyGrid),
                layerTile(R.drawable.ic_label, "Подписи (рус.)", "labelsRu", true, () -> applyStyle(currentStyle)),
                layerTile(R.drawable.ic_border, "Границы (лат.)", OV_KEYS[3], this::applyOverlays),
                layerTile(R.drawable.ic_layers, "Схема поверх", OV_KEYS[2], this::applyOverlays),
                layerTile(R.drawable.ic_my_location, "Карта группы", "showGroupMap", true, this::refreshGroupMap),
                layerTile(R.drawable.ic_nav, "Кнопки областей", "showChips", () ->
                        findViewById(R.id.chipsScroll).setVisibility(prefs.getBoolean("showChips", false) ? View.VISIBLE : View.GONE)));

        hSection("ЛИНИЯ ФРОНТА" + (lbsDate.isEmpty() ? "" : " · " + lbsDate));
        layerGrid(
                layerTile(R.drawable.ic_lbs, "ЛБС", "showLbs", true, this::forceReloadLbs),
                layerTile(R.drawable.ic_refresh, "Изменения", "showDiff", true, this::forceReloadLbs),
                layerTile(R.drawable.ic_area, "Серая зона", "showGray", true, this::forceReloadLbs));
    }

    /** Карточка «Дороги»: переключатель и, если включено, насыщенность линий — всё в одном месте. */
    private void roadsCard() {
        final boolean on = prefs.getBoolean(OV_KEYS[0], false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(on ? 0x26FF5A4E : 0xFF1E2228);
        g.setStroke((int) dp(1), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
        box.setBackground(g);

        View head = layerTile(R.drawable.ic_road, "Дороги", OV_KEYS[0], () -> {
            applyOverlays();
            final ScrollView sc = findViewById(R.id.genScroll);
            final int y = sc.getScrollY();
            showLayers();
            sc.post(() -> sc.scrollTo(0, y));
        });
        head.setBackground(null);
        box.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(48)));
        if (on) {
            TextView cap = new TextView(this);
            cap.setText("Насыщенность линий");
            cap.setTextSize(12);
            cap.setTextColor(getColor(R.color.hud_text2));
            cap.setPadding((int) dp(14), 0, (int) dp(14), (int) dp(6));
            box.addView(cap);
            LinearLayout seg = new LinearLayout(this);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding((int) dp(4), (int) dp(4), (int) dp(4), (int) dp(4));
            GradientDrawable sg = new GradientDrawable();
            sg.setCornerRadius(dp(10));
            sg.setColor(0xFF15181D);
            seg.setBackground(sg);
            final String[] lbl = {"Слабо", "Средне", "Ярко"};
            final List<TextView> bs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final int lv = i;
                TextView b = new TextView(this);
                b.setText(lbl[i]);
                b.setTextSize(13);
                b.setGravity(android.view.Gravity.CENTER);
                b.setPadding(0, (int) dp(7), 0, (int) dp(7));
                b.setOnClickListener(v -> {
                    prefs.edit().putInt("roadsLevel", lv).apply();
                    roadsOverlay.setLevel(lv);
                    map.invalidate();
                    for (int k = 0; k < bs.size(); k++) styleSeg(bs.get(k), k == lv);
                });
                bs.add(b);
                seg.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                styleSeg(b, i == prefs.getInt("roadsLevel", 1));
            }
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sl.setMargins((int) dp(10), 0, (int) dp(10), (int) dp(10));
            box.addView(seg, sl);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.bottomMargin = (int) dp(8);
        sheetContent.addView(box, bl);
    }

    private void styleSeg(TextView b, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(on ? getColor(R.color.hud_accent) : Color.TRANSPARENT);
        b.setBackground(g);
        b.setTextColor(on ? Color.WHITE : getColor(R.color.hud_text2));
        b.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
    }

    private void styleLayerCard(View card, boolean on) {
        LinearLayout c = (LinearLayout) card;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(13));
        g.setColor(on ? 0x33FF5A4E : Color.TRANSPARENT);
        g.setStroke((int) dp(on ? 2 : 1), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
        c.setBackground(g);
        ((TextView) c.getChildAt(1)).setTextColor(on ? getColor(R.color.hud_text) : getColor(R.color.hud_text2));
        ((TextView) c.getChildAt(1)).setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
    }

    private final Map<String, android.graphics.Bitmap> thumbs = new HashMap<>();

    /** Миниатюра подложки: квадрат z13 вокруг центра карты. */
    private void loadThumb(int style, ImageView img) {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        int z = style == STYLE_RELIEF ? 13 : 14, n = 1 << z;
        int x = (int) Math.floor((c.getLongitude() + 180) / 360 * n);
        double r = Math.toRadians(c.getLatitude());
        int y = (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n);
        OnlineTileSourceBase src = style == STYLE_SAT ? satSource() : style == STYLE_TOPO ? TOPO : style == STYLE_RELIEF ? RELIEF
                : TileSourceFactory.MAPNIK;
        final String url = src.getTileURLString(MapTileIndex.getTileIndex(z, x, y));
        android.graphics.Bitmap have = thumbs.get(url);
        if (have != null) { setThumb(img, have); return; }
        if (offline()) return;
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestProperty("User-Agent", getPackageName() + " (https://github.com/TRO9II/lbs-kml)");
                con.setConnectTimeout(8000);
                con.setReadTimeout(10000);
                android.graphics.Bitmap b;
                try (InputStream in = con.getInputStream()) {
                    b = android.graphics.BitmapFactory.decodeStream(in);
                }
                if (b == null) return;
                runOnUiThread(() -> {
                    thumbs.put(url, b);
                    setThumb(img, b);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void setThumb(ImageView img, android.graphics.Bitmap b) {
        img.setImageTintList(null);
        img.setPadding(0, 0, 0, 0);
        img.setImageBitmap(b);
    }

    private LinearLayout layerBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0xFF1E2228);
        g.setStroke((int) dp(1), getColor(R.color.hud_line));
        box.setBackground(g);
        return box;
    }

    private void layerDivider(LinearLayout box) {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.hud_line));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart((int) dp(48));
        box.addView(v, lp);
    }

    private void layerLink(LinearLayout box, int icon, String title, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(14), (int) dp(12), (int) dp(10), (int) dp(12));
        row.setBackgroundResource(R.drawable.bg_hud_btn);
        row.addView(hIcon(icon, getColor(R.color.hud_text), 20));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(getColor(R.color.hud_text));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tl.setMarginStart((int) dp(14));
        row.addView(t, tl);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(getColor(R.color.hud_text2));
        v.setSingleLine(true);
        row.addView(v);
        row.addView(hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18));
        row.setOnClickListener(click);
        box.addView(row);
    }

    private View layerTile(int icon, String title, String key, Runnable apply) {
        return layerTile(icon, title, key, false, apply);
    }

    /** Плитка-переключатель: значок и название, включена — подсвечена. */
    private View layerTile(int icon, String title, String key, boolean def, Runnable apply) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setPadding((int) dp(12), 0, (int) dp(10), 0);
        ImageView iv = hIcon(icon, 0, 20);
        t.addView(iv);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13.5f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tl.setMarginStart((int) dp(10));
        t.addView(tv, tl);
        View dot = new View(this);
        t.addView(dot, new LinearLayout.LayoutParams((int) dp(8), (int) dp(8)));
        Runnable paint = () -> {
            boolean on = prefs.getBoolean(key, def);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(on ? 0x26FF5A4E : 0xFF1E2228);
            g.setStroke((int) dp(1), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
            t.setBackground(g);
            int col = on ? getColor(R.color.hud_text) : getColor(R.color.hud_text2);
            iv.setImageTintList(ColorStateList.valueOf(on ? getColor(R.color.hud_accent) : getColor(R.color.hud_text2)));
            tv.setTextColor(col);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(on ? getColor(R.color.hud_accent) : 0x00000000);
            dg.setStroke((int) dp(1.2f), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
            dot.setBackground(dg);
        };
        paint.run();
        t.setOnClickListener(v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
            paint.run();
            apply.run();
        });
        return t;
    }

    /** Плитки по две в ряд (последний ряд может быть из одной). */
    private void layerGrid(View... tiles) {
        for (int i = 0; i < tiles.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = i; k < Math.min(i + 2, tiles.length); k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(48), 1);
                if (k == i) lp.setMarginEnd((int) dp(8));
                row.addView(tiles[k], lp);
            }
            if (tiles.length - i == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rl.bottomMargin = (int) dp(8);
            sheetContent.addView(row, rl);
        }
    }

    // ================= Меню =================

    private void showAbout() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        sheet("О приложении");
        hChipRow(hChip("Проверить обновления", R.drawable.ic_refresh, true, v -> checkUpdatesNow()));
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
        if (follow) { follow = false; styleMyLoc(); } // иначе карта сразу вернётся к GPS
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
            if (hit.id != null && hit.id.startsWith("g:")) {
                ChatClient.GObj g = chat.object(hit.id.substring(2));
                if (g != null) groupObjSheet(g);
            } else routeActions(hit);
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
        for (Routes.Route r : allRoutes()) {
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

        coordsCard(lat, lon);

        actionRow(
                vAction(R.drawable.ic_nav, "Вести", true, v -> {
                    String nm = name.getText().toString().trim();
                    closeSheet();
                    startNav(Collections.singletonList(new double[]{lat, lon}), 0, nm.isEmpty() ? "Точка" : nm);
                }),
                vAction(R.drawable.ic_flag, "Навести", false, v -> {
                    String nm = name.getText().toString().trim();
                    closeSheet();
                    setTarget(lat, lon, nm.isEmpty() ? "Точка" : nm);
                }),
                vAction(R.drawable.ic_ruler, "Линейка", false, v -> {
                    closeSheet();
                    startTool(TOOL_RULER, null);
                    toolAdd(lat, lon);
                }),
                vAction(R.drawable.ic_share, "Текстом", false, v -> {
                    String nm = name.getText().toString().trim();
                    sharePointText(nm.isEmpty() ? "Точка" : nm, lat, lon);
                }),
                vAction(R.drawable.ic_qr, "QR-код", false, v -> {
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
        if (chat != null && chat.active())
            hChipRow(hChip("На общую карту группы", R.drawable.ic_my_location, false, v -> {
                UserPoints.Point q = new UserPoints.Point();
                String nm = name.getText().toString().trim();
                q.name = nm.isEmpty() ? "Точка" : nm;
                q.note = note.getText().toString().trim();
                q.color = chosen[0];
                q.icon = icon[0];
                q.lat = lat;
                q.lon = lon;
                shareToGroupMap(objFromPoint(q));
            }));
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

    /** Кнопка-действие: значок над подписью (для ряда из 4–5 действий). */
    private View vAction(int icon, String label, boolean accent, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundResource(accent ? R.drawable.bg_accent_btn : R.drawable.bg_sheet_chip);
        ImageView iv = hIcon(icon, accent ? Color.WHITE : getColor(R.color.hud_text), 20);
        t.addView(iv);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11.5f);
        tv.setSingleLine(true);
        tv.setTextColor(accent ? Color.WHITE : getColor(R.color.hud_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(3);
        t.addView(tv, lp);
        t.setTag("tall");
        t.setOnClickListener(click);
        return t;
    }

    /** Ряд действий: кнопки делят ширину, квадратная иконка — справа. null пропускается. */
    private void actionRow(View... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<View> vs = new ArrayList<>();
        for (View v : items) if (v != null) vs.add(v);
        for (int i = 0; i < vs.size(); i++) {
            View v = vs.get(i);
            boolean square = "square".equals(v.getTag()), tall = "tall".equals(v.getTag());
            LinearLayout.LayoutParams lp = square ? new LinearLayout.LayoutParams((int) dp(46), (int) dp(44))
                    : new LinearLayout.LayoutParams(0, (int) dp(tall ? 58 : 44), 1);
            if (i < vs.size() - 1) lp.setMarginEnd((int) dp(tall ? 6 : 8));
            row.addView(v, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = (int) dp(4);
        rl.bottomMargin = (int) dp(4);
        sheetContent.addView(row, rl);
    }

    private View iconAction(int icon, String desc, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(getColor(R.color.hud_text)));
        int p = (int) dp(11);
        iv.setPadding(p, p, p, p);
        iv.setBackgroundResource(R.drawable.bg_sheet_chip);
        iv.setContentDescription(desc);
        iv.setTag("square");
        iv.setOnClickListener(click);
        iv.setOnLongClickListener(v -> { Toast.makeText(this, desc, Toast.LENGTH_SHORT).show(); return true; });
        return iv;
    }

    /** Пустой список: значок в круге, заголовок, подсказка — по центру. */
    private void emptyState(int icon, String title, String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding((int) dp(16), (int) dp(22), (int) dp(16), (int) dp(12));
        ImageView iv = hIcon(icon, getColor(R.color.hud_text2), 26);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF22262C);
        iv.setBackground(g);
        int p = (int) dp(14);
        iv.setPadding(p, p, p, p);
        box.addView(iv, new LinearLayout.LayoutParams((int) dp(56), (int) dp(56)));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15.5f);
        t.setTextColor(getColor(R.color.hud_text));
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.topMargin = (int) dp(12);
        box.addView(t, tl);
        TextView d = new TextView(this);
        d.setText(text);
        d.setTextSize(13);
        d.setTextColor(getColor(R.color.hud_text2));
        d.setGravity(android.view.Gravity.CENTER);
        d.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) dp(6);
        box.addView(d, dl);
        sheetContent.addView(box);
    }

    private void showPointsList() {
        sheet("Мои точки" + (userPoints.isEmpty() ? "" : " · " + userPoints.size()));
        actionRow(hChip("Точка в центре", R.drawable.ic_add, true, v -> { closeSheet(); newPointAtCenter(); }),
                userPoints.isEmpty() ? null : hChip("Экспорт", R.drawable.ic_share, false, v -> { closeSheet(); exportPoints(); }),
                iconAction(R.drawable.ic_qr, "Принять по QR", v -> { closeSheet(); scanQr(); }));
        if (chat != null && chat.active() && !chat.objects().isEmpty())
            hRow(hIcon(R.drawable.ic_my_location, 0xFF42A5F5, 22), "Карта группы", chat.objects().size() + " общих объектов",
                    hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18), v -> showGroupObjects());
        if (userPoints.isEmpty()) {
            emptyState(R.drawable.ic_place, "Точек пока нет",
                    "Нажмите на прицел в центре карты — точка встанет туда.\nИли подержите палец на нужном месте.");
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
        pathOverlay.setSaved(allRoutes());
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
        actionRow(hChip("Маршрут", R.drawable.ic_add, true, v -> { closeSheet(); startTool(TOOL_ROUTE, null); }),
                hChip("Зона", R.drawable.ic_area, false, v -> { closeSheet(); startTool(TOOL_AREA, null); }),
                iconAction(R.drawable.ic_qr, "Принять по QR", v -> { closeSheet(); scanQr(); }));
        if (track != null && track.pts.size() > 1)
            hChipRow(hChip("Вернуться по треку", R.drawable.ic_nav, true, v -> { closeSheet(); navRoute(track, true); }));
        if (routes.isEmpty()) {
            emptyState(R.drawable.ic_route, "Маршрутов и зон пока нет",
                    "Нажмите «Маршрут» и ставьте точки на карте — получите длины участков, азимуты и дирекционные углы. "
                            + "Авто-маршрут по дорогам — в «Инструментах».");
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
        if (chat != null && chat.active())
            hRow(hIcon(R.drawable.ic_my_location, tx, 22), "На общую карту группы", "Увидят все участники", null,
                    v -> { closeSheet(); shareToGroupMap(objFromRoute(r)); });
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
        findViewById(R.id.tileUpdate).setOnClickListener(v -> { openMenu(false); checkUpdatesNow(); });
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
            ((TextView) findViewById(R.id.menuVersion)).setText("Версия "
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
            final List<String> keys = new ArrayList<>(RoadsOverlay.keysFor(bb, 11, 60));
            keys.addAll(RoadsOverlay.keysFor(bb, 13, 150));
            keys.addAll(RoadsOverlay.keysFor(bb, 15, 300));
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
            insetTop = t;
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
        visible = true;
        checkUpdates();
        if (chat != null && chat.configured() && !offline()) {
            chat.start();
            GroupService.start(this);
            if (chat.purgeExpired() && chatOpen) openChat();
            if (chatOpen) chat.markRead();
        }
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.cancel(2002);
    }

    /** Карта на экране (для фонового сервиса: не дублировать уведомления). */
    static volatile boolean visible;

    @Override
    protected void onStop() {
        visible = false;
        if (chat != null && !GroupService.wanted(this)) chat.stop();
        lastStop = System.currentTimeMillis();
        if (track != null) saveRoutes();
        super.onStop();
    }

    @Override
    protected void onPause() {
        visible = false;
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
        ageHandler.removeCallbacksAndMessages(null);
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
        maybeShare(l);
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
        else pathOverlay.setSaved(allRoutes());
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
        c.setTextColor(on ? Color.WHITE : getColor(R.color.hud_text));
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
        if (chat != null) {
            if (off) { chat.stop(); GroupService.stop(this); }
            else if (chat.configured()) { chat.start(); GroupService.start(this); }
        }
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
            if (prefs.contains("wipeHash") && sha(prefs.getString("pinSalt", "") + "wipe|" + pinInput).equals(prefs.getString("wipeHash", ""))) {
                // код стирания: всё удаляется, приложение открывается как новое — без лишних сообщений
                unlocked = true;
                findViewById(R.id.lockView).setVisibility(View.GONE);
                wipeAll();
                return;
            }
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
            hSection("КОД СТИРАНИЯ");
            TextView wn = new TextView(this);
            wn.setText("Второй ПИН такой же длины. Если ввести его на экране блокировки, приложение молча сотрёт все данные "
                    + "и откроется пустым, как после установки. "
                    + (prefs.contains("wipeHash") ? "Код стирания задан." : "Сейчас не задан."));
            wn.setTextColor(getColor(R.color.hud_text2));
            wn.setTextSize(12);
            wn.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
            sheetContent.addView(wn);
            final EditText w1 = hInput("Код стирания (" + prefs.getInt("pinLen", 4) + " цифр)", "");
            w1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            sheetContent.addView(w1);
            hChipRow(hChip(prefs.contains("wipeHash") ? "Сменить код стирания" : "Задать код стирания", R.drawable.ic_lock, false, v -> {
                String c = w1.getText().toString();
                if (c.length() != prefs.getInt("pinLen", 4)) {
                    Toast.makeText(this, "Код должен быть той же длины, что и ПИН", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (sha(prefs.getString("pinSalt", "") + c).equals(prefs.getString("pinHash", ""))) {
                    Toast.makeText(this, "Код стирания не должен совпадать с ПИН-кодом", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString("wipeHash", sha(prefs.getString("pinSalt", "") + "wipe|" + c)).apply();
                hideKeyboard();
                Toast.makeText(this, "Код стирания задан", Toast.LENGTH_SHORT).show();
                showPinSettings();
            }), prefs.contains("wipeHash") ? hChip("Убрать", R.drawable.ic_close, false, v -> {
                prefs.edit().remove("wipeHash").apply();
                showPinSettings();
            }) : null);
            hSection("ЗАЩИТА");
            hChipRow(hChip("Выключить защиту", R.drawable.ic_close, false, v -> {
                prefs.edit().remove("pinHash").remove("pinSalt").remove("pinLen").remove("wipeHash").apply();
                updatePinRow();
                closeSheet();
                Toast.makeText(this, "Защита выключена", Toast.LENGTH_SHORT).show();
            }));
        }
        hSection("ЭКСТРЕННО");
        hChipRow(hChip("Стереть всё сейчас", R.drawable.ic_delete, false, v -> confirmWipe()));
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
            prefs.edit().putString("pinSalt", salt).putString("pinHash", sha(salt + a)).putInt("pinLen", a.length()).remove("wipeHash").apply();
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
        if (System.currentTimeMillis() - last < 20L * 60 * 1000) {
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

    private boolean updBusy;

    /** Проверить сейчас: есть новая версия — сразу окно обновления. */
    private void checkUpdatesNow() {
        if (offline()) {
            Toast.makeText(this, "Включён режим «Без сети»", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(httpGet("https://api.github.com/repos/TRO9II/lbs-kml/releases/tags/latest"));
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("v(\\d+(?:\\.\\d+)+)")
                        .matcher(o.optString("name", "") + " " + o.optString("body", ""));
                String ver = m.find() ? m.group(1) : "";
                prefs.edit().putLong("updCheckedAt", System.currentTimeMillis()).putString("updVer", ver).apply();
                runOnUiThread(() -> {
                    showUpdateRow();
                    if (updateAvailable()) downloadUpdate();
                    else Toast.makeText(this, "У вас последняя версия" + (ver.isEmpty() ? "" : " (v" + ver + ")"), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось проверить: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** Обновление прямо из приложения: скачать и поставить поверх. */
    private void downloadUpdate() {
        openMenu(false);
        sheet("Обновление");
        String mine = "";
        try {
            mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        LinearLayout card = hCard();
        addKV(card, "Установлена", "v" + mine);
        addKV(card, "Доступна", prefs.getString("updVer", "").isEmpty() ? "проверяю…" : "v" + prefs.getString("updVer", ""));
        sheetContent.addView(card);
        final TextView st = new TextView(this);
        st.setTextColor(getColor(R.color.hud_text2));
        st.setTextSize(13);
        st.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        String err = prefs.getString("updError", "");
        st.setText(err.isEmpty() ? "Новая версия скачается и встанет поверх — точки, маршруты и группа сохранятся." : err);
        sheetContent.addView(st);
        footer("Позже", "Скачать и установить", v -> {
            if (updBusy) return;
            if (!getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "Разрешите «Рубежу» устанавливать приложения и вернитесь сюда", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
                return;
            }
            updBusy = true;
            prefs.edit().remove("updError").apply();
            new Thread(() -> {
                try {
                    File apk = Updater.download(this, (done, total) -> runOnUiThread(() -> st.setText(
                            "Скачиваю: " + (total > 0 ? (done * 100 / total) + "% · " : "") + String.format(Locale.US, "%.1f МБ", done / 1048576.0))));
                    runOnUiThread(() -> st.setText("Устанавливаю… подтвердите в окне системы"));
                    Updater.install(this, apk);
                } catch (Exception e) {
                    runOnUiThread(() -> st.setText("Не удалось: " + e.getMessage()));
                } finally {
                    updBusy = false;
                }
            }).start();
        });
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
        if (req == REQ_CAM) {
            if (res == RESULT_OK && camUri != null) sendPhoto(camUri, true);
            else if (camUri != null) getContentResolver().delete(camUri, null, null);
            camUri = null;
            return;
        }
        if (req == REQ_GAL) {
            if (res == RESULT_OK && data != null && data.getData() != null) sendPhoto(data.getData(), false);
            return;
        }
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
        if (req == REQ_CAMPERM) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto();
            else Toast.makeText(this, "Без доступа к камере сфотографировать не получится — можно выбрать фото из галереи", Toast.LENGTH_LONG).show();
            return;
        }
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
        handleGroupIntent(intent);
    }

    /** Открытие из уведомления: чат или SOS. */
    private void handleGroupIntent(Intent i) {
        if (i == null || chat == null) return;
        long sos = i.getLongExtra("sosSeq", -1);
        if (sos >= 0) {
            for (ChatClient.Msg m : chat.history()) if (m.seq == sos) { showSos(m); return; }
        }
        if (i.getBooleanExtra("openChat", false)) map.post(this::openChat);
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
            if (chat != null && chat.configured()) {
                JSONObject g = new JSONObject();
                for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet())
                    if (e.getKey().startsWith("c2") || e.getKey().equals("chatCall") || e.getKey().startsWith("share"))
                        g.put(e.getKey(), e.getValue() instanceof Boolean ? e.getValue() : e.getValue() instanceof Integer ? e.getValue()
                                : e.getValue() instanceof Long ? e.getValue() : String.valueOf(e.getValue()));
                JSONObject types = new JSONObject();
                for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet())
                    if (g.has(e.getKey())) types.put(e.getKey(), e.getValue() instanceof Long ? "l" : e.getValue() instanceof Integer ? "i"
                            : e.getValue() instanceof Boolean ? "b" : "s");
                o.put("group", g);
                o.put("groupTypes", types);
            }
            String name = "Rubezh_backup_" + new SimpleDateFormat("dd_MM_yyyy_HHmm", Locale.US).format(new Date()) + ".json";
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
                final JSONObject grp = o.optJSONObject("group"), gty = o.optJSONObject("groupTypes");
                runOnUiThread(() -> {
                    sheet("Восстановление");
                    LinearLayout card = hCard();
                    addKV(card, "В копии точек", Integer.toString(pts == null ? 0 : pts.length()));
                    addKV(card, "В копии маршрутов", Integer.toString(rts == null ? 0 : rts.length()));
                    if (grp != null) addKV(card, "Группа", "«" + grp.optString("c2Group", "Группа") + "», позывной " + grp.optString("chatCall"));
                    addKV(card, "Сейчас на телефоне", userPoints.size() + " точек, " + routes.size() + " маршрутов");
                    sheetContent.addView(card);
                    if (grp != null && gty != null && !chat.configured())
                        hChipRow(hChip("Вернуть доступ к группе", R.drawable.ic_my_location, true, v -> {
                            restoreGroup(grp, gty);
                            Toast.makeText(this, "Доступ к группе восстановлен", Toast.LENGTH_LONG).show();
                        }));
                    hChipRow(hChip("Добавить к текущим", R.drawable.ic_add, false, v -> applyRestore(pts, rts, false)));
                    footer("Отмена", "Заменить всё", v -> applyRestore(pts, rts, true));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось восстановить: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void restoreGroup(JSONObject g, JSONObject types) {
        try {
            SharedPreferences.Editor ed = prefs.edit();
            for (java.util.Iterator<String> it = g.keys(); it.hasNext(); ) {
                String k = it.next();
                switch (types.optString(k, "s")) {
                    case "l": ed.putLong(k, g.getLong(k)); break;
                    case "i": ed.putInt(k, g.getInt(k)); break;
                    case "b": ed.putBoolean(k, g.getBoolean(k)); break;
                    default: ed.putString(k, g.getString(k));
                }
            }
            ed.commit();
            chat.reload();
            chat.start();
            GroupService.start(this);
            refreshGroupMap();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось вернуть группу: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
        if (ChatClient.isInvite(text)) {
            joinInvite(text);
            return;
        }
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
                toolTile(R.drawable.ic_area, "Площадь", tx, () -> startTool(TOOL_AREA, null)),
                toolTile(R.drawable.ic_eye_los, "Видимость", tx, () -> {
                    startTool(TOOL_RULER, null);
                    Toast.makeText(this, "Поставьте 2 точки (откуда и куда смотреть), затем нажмите ⛰ «Профиль»", Toast.LENGTH_LONG).show();
                }));
        hSection("МАРШРУТЫ");
        toolTiles(
                toolTile(R.drawable.ic_route, "По прямым", tx, () -> startTool(TOOL_ROUTE, null)),
                toolTile(R.drawable.ic_road, "По дорогам пешком", tx, () -> { startTool(TOOL_ROUTE, null); setRoadMode("foot"); }),
                toolTile(R.drawable.ic_road, "По дорогам на авто", tx, () -> { startTool(TOOL_ROUTE, null); setRoadMode("car"); }));
        toolTiles(
                toolTile(R.drawable.ic_nav, "Вести к цели", tx, () -> {
                    if (!hasTarget()) {
                        Toast.makeText(this, "Сначала выберите цель: точка → «Навести»", Toast.LENGTH_LONG).show();
                        return;
                    }
                    startNav(Collections.singletonList(new double[]{Double.parseDouble(prefs.getString("targetLat", "0")),
                            Double.parseDouble(prefs.getString("targetLon", "0"))}), 0, prefs.getString("targetName", "Цель"));
                }),
                toolTile(R.drawable.ic_eye, "Мои маршруты", tx, this::showRoutes),
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

    // ================= Чат группы =================

    private ChatClient chat;
    private MembersOverlay membersOverlay;
    private long lastShareAt;
    private double[] lastShared;
    private final Runnable ageTick = new Runnable() {
        @Override
        public void run() {
            if (membersOverlay != null && !chat.members().isEmpty()) map.invalidate();
            ageHandler.postDelayed(this, 30_000);
        }
    };
    private final android.os.Handler ageHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean chatOpen;
    private int chatUnread;
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private TextView chatStatus;

    private void setupChat() {
        chat = ChatClient.get(this);
        chatUnread = prefs.getInt("chatUnread", 0);
        showChatBadge();
        findViewById(R.id.btnChat).setOnClickListener(v -> openChat());
        chat.setListener(new ChatClient.Listener() {
            @Override
            public void onMessages(List<ChatClient.Msg> added) {
                int fromOthers = 0;
                for (ChatClient.Msg m : added) {
                    if (!m.mine) fromOthers++;
                    if (!m.mine && m.kind.equals("sos")) showSos(m);
                }
                if (chatOpen) chat.markRead();
                if (chatOpen && chatList != null) {
                    for (ChatClient.Msg m : added) addBubble(m);
                    scrollChatDown();
                } else if (fromOthers > 0) {
                    chatUnread += fromOthers;
                    prefs.edit().putInt("chatUnread", chatUnread).apply();
                    showChatBadge();
                    vibrate(120);
                    ChatClient.Msg last = added.get(added.size() - 1);
                    Toast.makeText(MapActivity.this, (last.from.isEmpty() ? "Сообщение" : last.from) + ": "
                            + (last.kind.equals("pt") ? "📍 " + last.ptName : last.text), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onMembers() {
                membersOverlay.setMembers(chat.members());
                map.invalidate();
            }

            @Override
            public void onObjects() {
                refreshGroupMap();
            }

            @Override
            public void onReads() {
                refreshReadMarks();
            }

            @Override
            public void onPeople(int pending) {
                int seen = prefs.getInt("c2PendSeen", 0);
                if (chat.isAdmin() && pending > seen) {
                    vibrate(200);
                    Toast.makeText(MapActivity.this, "Новый участник просит вступить в группу — Чат → ≡ → Участники",
                            Toast.LENGTH_LONG).show();
                }
                prefs.edit().putInt("c2PendSeen", pending).apply();
                chatPending = chat.isAdmin() ? pending : 0;
                showChatBadge();
            }

            @Override
            public void onRole(String role) {
                if (role.equals("removed") || role.equals("left")) {
                    prefs.edit().putBoolean("shareLoc", false).apply();
                    membersOverlay.setMembers(new ArrayList<>());
                    map.invalidate();
                    if (role.equals("removed"))
                        Toast.makeText(MapActivity.this, "Администратор удалил вас из группы", Toast.LENGTH_LONG).show();
                    if (chatOpen || sheetOpen()) closeSheet();
                } else if (role.equals("member") || role.equals("admin")) {
                    Toast.makeText(MapActivity.this, role.equals("admin") ? "Вы теперь администратор группы"
                            : "Вас приняли в группу «" + chat.groupName() + "»", Toast.LENGTH_LONG).show();
                    vibrate(200);
                    if (chatOpen) openChat();
                }
            }

            @Override
            public void onStatus(String status, boolean ok) {
                if (chatStatus != null) {
                    setChatStatus(status, ok);
                    return;
                }
                if (chatStatus != null) {
                    chatStatus.setText((ok ? "● " : "○ ") + status);
                    chatStatus.setTextColor(ok ? 0xFF66BB6A : 0xFFFFB74D);
                }
            }
        });
        if (chat.configured() && !offline()) chat.start();
        membersOverlay.setMembers(chat.members());
        ageHandler.postDelayed(ageTick, 30_000);
        if (prefs.getBoolean("shareLoc", false) && chat.configured() && hasLocPerm() && !locOn) startLocation(false);
    }

    // ---------- метки группы ----------

    private boolean sharing() {
        return prefs.getBoolean("shareLoc", false) && chat != null && chat.configured() && !offline();
    }

    /** Вызывается при каждой новой координате GPS. */
    private void maybeShare(android.location.Location l) {
        ShareLoc.onLocation(this, l);
    }

    private void setSharing(boolean on) {
        prefs.edit().putBoolean("shareLoc", on).apply();
        if (on) {
            ShareLoc.reset();
            if (!locOn) startLocation(false);
            android.location.Location l = myLoc.getLocation();
            if (l != null) maybeShare(l);
            askNotifPermission();
            Toast.makeText(this, prefs.getBoolean("groupBg", true)
                    ? "Группа видит вас на карте — и когда приложение свёрнуто" : "Группа видит вас на карте, пока приложение открыто",
                    Toast.LENGTH_LONG).show();
        } else {
            chat.sendLoc(0, 0, 0, Float.NaN, true);
            ShareLoc.clearTail();
            Toast.makeText(this, "Показ местоположения выключен", Toast.LENGTH_SHORT).show();
        }
        restartGroupService();
    }

    private void restartGroupService() {
        GroupService.stop(this);
        if (GroupService.wanted(this)) map.postDelayed(() -> GroupService.start(this), 300);
    }

    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    private void showMembers() {
        List<ChatClient.Member> ms = chat.members();
        sheet("На карте · " + ms.size());
        if (ms.isEmpty()) {
            emptyState(R.drawable.ic_my_location, "Пока никого",
                    "Участники появятся здесь, когда включат «Показывать меня» в настройках группы.");
            return;
        }
        android.location.Location me = myLoc.getLocation();
        Collections.sort(ms, (a, b) -> Long.compare(b.recv, a.recv));
        for (ChatClient.Member m : ms) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(MembersOverlay.color(m.name));
            dot.setBackground(g);
            dot.setLayoutParams(new LinearLayout.LayoutParams((int) dp(14), (int) dp(14)));
            String sub = MembersOverlay.age(m.recv) + (MembersOverlay.age(m.recv).equals("сейчас") ? "" : " назад");
            if (me != null) sub += " · " + Geo.dist(Geo.inverse(me.getLatitude(), me.getLongitude(), m.lat, m.lon)[0]) + " от вас";
            hRow(dot, m.name.isEmpty() ? "без позывного" : m.name, sub, null, v -> {
                closeSheet();
                follow = false;
                map.getController().animateTo(new GeoPoint(m.lat, m.lon), Math.max(14.0, map.getZoomLevelDouble()), 600L);
            });
        }
    }

    private void showMember(ChatClient.Member m) {
        sheet(m.name.isEmpty() ? "Участник" : m.name);
        LinearLayout card = hCard();
        addKV(card, "Обновлено", MembersOverlay.age(m.recv) + (MembersOverlay.age(m.recv).equals("сейчас") ? "" : " назад")
                + " (" + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.recv)) + ")");
        if (m.acc > 0) addKV(card, "Точность GPS", "±" + Math.round(m.acc) + " м");
        android.location.Location me = myLoc.getLocation();
        if (me != null) {
            double[] inv = Geo.inverse(me.getLatitude(), me.getLongitude(), m.lat, m.lon);
            addKV(card, "От меня", Geo.dist(inv[0]) + " · " + String.format(Locale.US, "%.0f°", inv[1]));
        }
        sheetContent.addView(card);
        coordsCard(m.lat, m.lon);
        actionRow(
                vAction(R.drawable.ic_nav, "Вести", true, v -> {
                    closeSheet();
                    startNav(Collections.singletonList(new double[]{m.lat, m.lon}), 0, m.name);
                }),
                vAction(R.drawable.ic_flag, "Навести", false, v -> { closeSheet(); setTarget(m.lat, m.lon, m.name); }),
                vAction(R.drawable.ic_chat, "В чат", false, v -> openChat()),
                vAction(R.drawable.ic_add, "В точки", false, v -> editPoint(null, m.lat, m.lon, m.name)));
    }

    private int chatPending;

    private void showChatBadge() {
        TextView b = findViewById(R.id.chatBadge);
        int n = chatUnread + chatPending;
        b.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        b.setText(n > 99 ? "99+" : Integer.toString(n));
    }

    private void openChat() {
        if (chat.legacy()) {
            chatOpen = false;
            sheet("Чат группы");
            emptyState(R.drawable.ic_chat, "Группа старой версии",
                    "В новой версии появились администраторы, одобрение новичков и удаление участников. "
                            + "Для этого группу нужно создать заново (сервер тот же) и пригласить всех снова.");
            hChipRow(hChip("Создать новую группу", R.drawable.ic_add, true, v -> { chat.dropLegacy(); createGroup(); }));
            return;
        }
        if (!chat.configured()) {
            chatSetup();
            return;
        }
        if (chat.pending()) {
            chat.start();
            chatOpen = true;
            sheet(chat.groupName());
            emptyState(R.drawable.ic_lock, "Ожидает подтверждения",
                    "Заявка отправлена. Как только администратор примет вас, откроется чат — придёт уведомление.\n\nВаш позывной: "
                            + chat.callsign());
            hChipRow(hChip("Отменить заявку", R.drawable.ic_close, false, v -> chat.leave(() -> {
                closeSheet();
                Toast.makeText(this, "Заявка отменена", Toast.LENGTH_SHORT).show();
            })));
            return;
        }
        if (offline()) Toast.makeText(this, "Включён режим «Без сети» — сообщения не уйдут", Toast.LENGTH_LONG).show();
        if (chat.callsign().isEmpty()) {
            askCallsign(this::openChat);
            return;
        }
        chat.start();
        chat.purgeExpired();
        readMarks.clear();
        sheet(chat.groupName());
        setSheetFull(true);
        chatOpen = true;
        chat.markRead();
        chatUnread = 0;
        prefs.edit().putInt("chatUnread", 0).apply();
        showChatBadge();

        buildChatHeader();

        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setPadding(0, (int) dp(2), 0, (int) dp(8));
        lastBubbleDay = "";
        lastBubbleDev = "";
        lastBubbleT = 0;
        chatList.setMinimumHeight(0);
        sheetContent.addView(chatList);
        List<ChatClient.Msg> hist = chat.history();
        if (hist.isEmpty()) {
            LinearLayout em = new LinearLayout(this);
            em.setOrientation(LinearLayout.VERTICAL);
            em.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            em.setPadding((int) dp(16), (int) dp(48), (int) dp(16), 0);
            em.setTag("empty");
            ImageView ic = hIcon(R.drawable.ic_chat, getColor(R.color.hud_text2), 30);
            GradientDrawable cg = new GradientDrawable();
            cg.setShape(GradientDrawable.OVAL);
            cg.setColor(0xFF22262C);
            ic.setBackground(cg);
            int ip = (int) dp(16);
            ic.setPadding(ip, ip, ip, ip);
            em.addView(ic, new LinearLayout.LayoutParams((int) dp(64), (int) dp(64)));
            TextView t1 = new TextView(this);
            t1.setText("Сообщений пока нет");
            t1.setTextSize(16);
            t1.setTextColor(getColor(R.color.hud_text));
            t1.setPadding(0, (int) dp(14), 0, (int) dp(4));
            em.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText("Всё шифруется на телефоне — посторонние,\nв том числе сервер, видят только набор символов.");
            t2.setTextSize(12.5f);
            t2.setGravity(android.view.Gravity.CENTER);
            t2.setTextColor(getColor(R.color.hud_text2));
            em.addView(t2);
            LinearLayout qa = new LinearLayout(this);
            qa.setOrientation(LinearLayout.HORIZONTAL);
            qa.setGravity(android.view.Gravity.CENTER);
            qa.setPadding(0, (int) dp(18), 0, 0);
            TextView q1 = headChip("📍 Моё место", getColor(R.color.hud_text), v -> {
                android.location.Location me = myLoc.getLocation();
                if (me == null) { Toast.makeText(this, "Включите GPS кнопкой ◎ на карте", Toast.LENGTH_SHORT).show(); return; }
                sendPoint("Я здесь", me.getLatitude(), me.getLongitude());
            });
            TextView q2 = headChip("⚑ Статус", getColor(R.color.hud_text), v -> showStatuses());
            qa.addView(q1);
            qa.addView(q2);
            if (chat.isAdmin()) qa.addView(headChip("＋ Пригласить", getColor(R.color.hud_text), v -> showInvite()));
            em.addView(qa);
            chatList.addView(em);
        }
        int from = Math.max(0, hist.size() - 200);
        for (int i = from; i < hist.size(); i++) addBubble(hist.get(i));
        chatScroll = findViewById(R.id.genScroll);
        scrollChatDown();

        // строка ввода внизу
        sheetFooter.removeAllViews();
        sheetFooter.setVisibility(View.VISIBLE);
        sheetFooter.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // «+» — вложения, статусы и SOS; поле ввода на всю ширину; справа микрофон, а при наборе текста — «отправить»
        ImageView plus = hIconButton(R.drawable.ic_add, getColor(R.color.hud_text), v -> chatAttach());
        plus.setContentDescription("Вложения, статус, SOS");
        sheetFooter.addView(plus, new LinearLayout.LayoutParams((int) dp(42), (int) dp(42)));
        final EditText input = hInput("Сообщение", "");
        final ImageView smile = hIconButton(R.drawable.ic_smile, getColor(R.color.hud_text2), null);
        smile.setContentDescription("Смайлы");
        input.setMinHeight((int) dp(48));
        input.setMaxLines(6);
        input.setTextSize(16);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        GradientDrawable ib = new GradientDrawable();
        ib.setCornerRadius(dp(22));
        ib.setColor(0xFF22262C);
        ib.setStroke((int) dp(1), getColor(R.color.hud_line));
        input.setBackground(ib);
        input.setPadding((int) dp(16), (int) dp(10), (int) dp(16), (int) dp(10));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        il.setMarginStart((int) dp(6));
        il.setMarginEnd((int) dp(8));
        android.widget.FrameLayout inWrap = new android.widget.FrameLayout(this);
        inWrap.addView(input, new android.widget.FrameLayout.LayoutParams(-1, -2));
        input.setPadding((int) dp(16), (int) dp(10), (int) dp(44), (int) dp(10));
        inWrap.addView(smile, new android.widget.FrameLayout.LayoutParams((int) dp(40), (int) dp(40),
                android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL));
        smile.setOnClickListener(v -> toggleEmoji(input));
        input.setOnClickListener(v -> hideEmoji());
        input.setOnFocusChangeListener((v, f) -> { if (f) hideEmoji(); });
        sheetFooter.addView(inWrap, il);
        final ImageView act = new ImageView(this);
        act.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        int p = (int) dp(11);
        act.setPadding(p, p, p, p);
        GradientDrawable sb = new GradientDrawable();
        sb.setShape(GradientDrawable.OVAL);
        sb.setColor(getColor(R.color.hud_accent));
        act.setBackground(sb);
        act.setImageResource(R.drawable.ic_mic);
        act.setContentDescription("Голосовое сообщение");
        sheetFooter.addView(act, new LinearLayout.LayoutParams((int) dp(48), (int) dp(48)));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a1, int a2, int a3) {}
            @Override public void onTextChanged(CharSequence c, int a1, int a2, int a3) {}
            @Override
            public void afterTextChanged(Editable e) {
                boolean has = e.toString().trim().length() > 0;
                act.setImageResource(has ? R.drawable.ic_send : R.drawable.ic_mic);
                act.setContentDescription(has ? "Отправить" : "Голосовое сообщение");
            }
        });
        act.setOnClickListener(v -> {
            String txt = input.getText().toString().trim();
            if (txt.isEmpty()) {
                chatVoiceStart();
                return;
            }
            ChatClient.Msg m = new ChatClient.Msg();
            m.text = txt;
            input.setText("");
            sendChat(m);
        });
    }

    private void sendChat(ChatClient.Msg m) {
        chat.send(m, () -> { }, err -> Toast.makeText(this, "Не отправлено: " + err, Toast.LENGTH_LONG).show());
    }

    private void scrollChatDown() {
        if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Неподвижная строка под заголовком чата: связь и участники слева, значки справа. */
    private void buildChatHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, (int) dp(8));
        chatStatus = new TextView(this);
        setChatStatus(chat.lastStatus(), chat.lastOk());
        chatStatus.setTextSize(12.5f);
        chatStatus.setSingleLine(true);
        chatStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(chatStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        chatStatus.setOnClickListener(v -> {
            if (chat.isAdmin()) Toast.makeText(this, chat.lastStatus() + (chat.lastError().isEmpty() ? "" : "\n" + chat.lastError()), Toast.LENGTH_LONG).show();
        });
        head.addView(headIcon(R.drawable.ic_my_location, chat.members().size(), sharing() ? 0xFF66BB6A : getColor(R.color.hud_text), "Люди на карте",
                v -> { chatOpen = false; sheetFooter.setVisibility(View.GONE); showMembers(); }));
        head.addView(headIcon(R.drawable.ic_place, chat.objects().size(), getColor(R.color.hud_text), "Карта группы",
                v -> { chatOpen = false; sheetFooter.setVisibility(View.GONE); showGroupObjects(); }));
        int pend = 0;
        if (chat.isAdmin()) for (ChatClient.Person p : chat.people()) if (p.role.equals("pending")) pend++;
        head.addView(headIcon(R.drawable.ic_menu, pend, pend > 0 ? 0xFFFFB74D : getColor(R.color.hud_text), "Настройки группы", v -> chatSettings()));
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.hud_line));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(head);
        box.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout panel = findViewById(R.id.genPanel);
        panel.addView(box, panel.indexOfChild(findViewById(R.id.genScroll)));
        chatHeader = box;
    }

    /** Статус связи без технических подробностей. */
    private void setChatStatus(String st, boolean ok) {
        if (chatStatus == null) return;
        int people = 0;
        for (ChatClient.Person p : chat.people()) if (p.active()) people++;
        String txt = ok ? (st.contains("ждём") ? "ждём подтверждения" : "на связи" + (people > 0 ? " · " + people + " " + plural(people, "участник", "участника", "участников") : ""))
                : "нет связи — переподключаюсь";
        chatStatus.setText((ok ? "● " : "○ ") + txt);
        chatStatus.setTextColor(ok ? 0xFF66BB6A : 0xFFFFB74D);
    }

    private static String plural(int n, String one, String few, String many) {
        int a = n % 100, b = n % 10;
        if (a >= 11 && a <= 14) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }

    /** Круглый значок в шапке чата со счётчиком. */
    private View headIcon(int icon, int count, int tint, String desc, View.OnClickListener click) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        ImageView iv = hIconButton(icon, tint, click);
        iv.setContentDescription(desc);
        iv.setOnLongClickListener(v -> { Toast.makeText(this, desc, Toast.LENGTH_SHORT).show(); return true; });
        f.addView(iv, new android.widget.FrameLayout.LayoutParams((int) dp(40), (int) dp(40)));
        if (count > 0) {
            TextView b = new TextView(this);
            b.setText(count > 99 ? "99+" : Integer.toString(count));
            b.setTextSize(10);
            b.setTextColor(Color.WHITE);
            b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            b.setGravity(android.view.Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            g.setColor(getColor(R.color.hud_accent));
            b.setBackground(g);
            b.setPadding((int) dp(4), 0, (int) dp(4), 0);
            android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (int) dp(16), android.view.Gravity.TOP | android.view.Gravity.END);
            b.setMinWidth((int) dp(16));
            f.addView(b, bl);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) dp(44), (int) dp(40));
        lp.setMarginStart((int) dp(4));
        f.setLayoutParams(lp);
        return f;
    }

    private TextView headChip(String text, int color, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setBackgroundResource(R.drawable.bg_sheet_chip);
        t.setPadding((int) dp(10), (int) dp(6), (int) dp(10), (int) dp(6));
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) dp(6));
        t.setLayoutParams(lp);
        return t;
    }

    private String lastBubbleDay = "", lastBubbleDev = "";
    private long lastBubbleT;

    private static String dayLabel(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance(), d = java.util.Calendar.getInstance();
        d.setTimeInMillis(t);
        if (c.get(java.util.Calendar.YEAR) == d.get(java.util.Calendar.YEAR)) {
            int diff = c.get(java.util.Calendar.DAY_OF_YEAR) - d.get(java.util.Calendar.DAY_OF_YEAR);
            if (diff == 0) return "Сегодня";
            if (diff == 1) return "Вчера";
        }
        return new SimpleDateFormat("d MMMM", new Locale("ru")).format(new Date(t));
    }

    private TextView linkChip(String text, int icon, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(Color.WHITE);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(0x33FFFFFF);
        t.setBackground(g);
        t.setPadding((int) dp(10), (int) dp(6), (int) dp(12), (int) dp(6));
        if (icon != 0) {
            android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
            d.setTint(Color.WHITE);
            int sz = (int) dp(15);
            d.setBounds(0, 0, sz, sz);
            t.setCompoundDrawables(d, null, null, null);
            t.setCompoundDrawablePadding((int) dp(6));
        }
        t.setOnClickListener(click);
        return t;
    }

    private void addBubble(ChatClient.Msg m) {
        if (chatList == null) return;
        if (chatList.getChildCount() == 1 && "empty".equals(chatList.getChildAt(0).getTag())) chatList.removeAllViews();
        String day = dayLabel(m.t);
        if (!day.equals(lastBubbleDay)) {
            TextView sep = new TextView(this);
            sep.setText(day);
            sep.setTextSize(11.5f);
            sep.setTextColor(getColor(R.color.hud_text2));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(0xFF22262C);
            sep.setBackground(g);
            sep.setPadding((int) dp(10), (int) dp(3), (int) dp(10), (int) dp(3));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sl.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            sl.topMargin = (int) dp(10);
            sl.bottomMargin = (int) dp(4);
            chatList.addView(sep, sl);
            lastBubbleDay = day;
            lastBubbleDev = "";
        }
        final String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.t));
        if (m.kind.equals("obj")) {
            TextView sys = new TextView(this);
            String who = m.mine ? "Вы" : (m.from.isEmpty() ? "Участник" : m.from);
            String nm = m.op.equals("del") ? m.text : "";
            if (!m.op.equals("del")) try { nm = new JSONObject(m.obj).optString("n"); } catch (Exception ignored) { }
            sys.setText(who + (m.op.equals("del") ? " убрал(а) с карты группы «" : " отметил(а) на карте группы «") + nm + "»  " + time);
            sys.setTextSize(12);
            sys.setTextColor(0xFF90CAF9);
            sys.setGravity(android.view.Gravity.CENTER);
            sys.setPadding((int) dp(16), (int) dp(6), (int) dp(16), (int) dp(6));
            if (!m.op.equals("del")) sys.setOnClickListener(v -> {
                ChatClient.GObj g = chat.object(m.oid);
                if (g != null) { chatOpen = false; sheetFooter.setVisibility(View.GONE); groupObjSheet(g); }
                else Toast.makeText(this, "Уже убрано с карты группы", Toast.LENGTH_SHORT).show();
            });
            chatList.addView(sys);
            lastBubbleDev = "";
            return;
        }
        boolean cont = m.dev.equals(lastBubbleDev) && m.t - lastBubbleT < 5 * 60_000;
        lastBubbleDev = m.dev;
        lastBubbleT = m.t;

        boolean sos = m.kind.equals("sos"), st = m.kind.equals("st");
        int bg = sos ? 0xFFC62828 : m.mine ? 0xFF5C2622 : 0xFF252A32;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity((m.mine ? android.view.Gravity.END : android.view.Gravity.START) | android.view.Gravity.BOTTOM);
        if (!m.mine) { // аватар с инициалами у первого сообщения серии
            TextView av = new TextView(this);
            av.setTextColor(Color.WHITE);
            av.setTextSize(11);
            av.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            av.setGravity(android.view.Gravity.CENTER);
            if (!cont) {
                av.setText(MembersOverlay.initials(m.from));
                GradientDrawable ag = new GradientDrawable();
                ag.setShape(GradientDrawable.OVAL);
                ag.setColor(callColor(m.from));
                av.setBackground(ag);
            }
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams((int) dp(30), (int) dp(30));
            al.setMarginEnd((int) dp(6));
            al.gravity = android.view.Gravity.TOP;
            row.addView(av, al);
        }
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding((int) dp(12), (int) dp(7), (int) dp(12), (int) dp(6));
        GradientDrawable g = new GradientDrawable();
        float r = dp(16), sm = dp(5);
        g.setCornerRadii(m.mine ? new float[]{r, r, cont ? sm : r, cont ? sm : r, sm, sm, r, r}
                : new float[]{cont ? sm : r, cont ? sm : r, r, r, r, r, sm, sm});
        g.setColor(bg);
        b.setBackground(g);
        if (!m.mine && !cont) {
            TextView who = new TextView(this);
            who.setText(m.from.isEmpty() ? "без позывного" : m.from);
            who.setTextSize(12.5f);
            who.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            who.setTextColor(sos ? Color.WHITE : callColor(m.from));
            b.addView(who);
        }
        if (sos || st) {
            TextView lab = new TextView(this);
            lab.setText(sos ? "SOS · ТРЕВОГА" : "СТАТУС");
            lab.setTextSize(10.5f);
            lab.setLetterSpacing(0.08f);
            lab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            lab.setTextColor(sos ? 0xFFFFCDD2 : 0xFF90CAF9);
            b.addView(lab);
            TextView tx = new TextView(this);
            tx.setText(m.text);
            tx.setTextSize(16.5f);
            tx.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tx.setTextColor(Color.WHITE);
            b.addView(tx);
        }
        if (m.kind.equals("voice")) {
            final TextView vb = new TextView(this);
            vb.setText("▶   " + mmss(m.ms));
            vb.setTextSize(15);
            vb.setTextColor(Color.WHITE);
            vb.setPadding(0, (int) dp(2), (int) dp(30), (int) dp(2));
            b.addView(vb);
            b.setOnClickListener(v -> {
                if (m.media.isEmpty() || !new File(m.media).exists()) {
                    Toast.makeText(this, "Запись недоступна", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (player != null) { stopPlaying(); vb.setText("▶   " + mmss(m.ms)); return; }
                vb.setText("■   " + mmss(m.ms) + "  играет");
                playVoice(new File(m.media), () -> vb.setText("▶   " + mmss(m.ms)));
            });
        }
        if (m.kind.equals("photo") && !m.media.isEmpty()) {
            ImageView photoV = new ImageView(this);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            photoV.setImageBitmap(android.graphics.BitmapFactory.decodeFile(m.media, o));
            photoV.setAdjustViewBounds(true);
            photoV.setMaxHeight((int) dp(260));
            photoV.setScaleType(ImageView.ScaleType.FIT_START);
            photoV.setClipToOutline(true);
            GradientDrawable pg = new GradientDrawable();
            pg.setCornerRadius(dp(10));
            photoV.setBackground(pg);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams((int) dp(220), LinearLayout.LayoutParams.WRAP_CONTENT);
            pl.topMargin = (int) dp(3);
            b.addView(photoV, pl);
            photoV.setOnClickListener(v -> showPhoto(m));
        }
        if (m.kind.equals("pt")) {
            LinearLayout pc = new LinearLayout(this);
            pc.setOrientation(LinearLayout.HORIZONTAL);
            pc.setGravity(android.view.Gravity.CENTER_VERTICAL);
            pc.setPadding(0, (int) dp(2), 0, 0);
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(0xFF42A5F5, getResources().getDisplayMetrics().density, ""));
            pc.addView(pin, new LinearLayout.LayoutParams((int) dp(24), (int) dp(24)));
            TextView pt = new TextView(this);
            pt.setText((m.ptName.isEmpty() ? "Точка" : m.ptName) + "\n" + Sk42.format(m.lat, m.lon));
            pt.setTextSize(14.5f);
            pt.setTextColor(Color.WHITE);
            pt.setPadding((int) dp(8), 0, 0, 0);
            pc.addView(pt);
            b.addView(pc);
        }
        final boolean bigEmoji = m.kind.equals("msg") && onlyEmoji(m.text);
        if (bigEmoji) {
            g.setColor(Color.TRANSPARENT);
            b.setPadding((int) dp(2), 0, (int) dp(2), 0);
        }
        if (!m.text.isEmpty() && !st && !sos) {
            TextView t = new TextView(this);
            t.setText(m.text);
            t.setTextSize(bigEmoji ? 44f : 15.5f);
            t.setTextColor(Color.WHITE);
            t.setLineSpacing(dp(1), 1f);
            b.addView(t);
            t.setOnLongClickListener(v -> { copy(m.text, "Текст скопирован"); return true; });
        }
        if (m.kind.equals("pt") || m.hasLoc) {
            if (!m.kind.equals("pt")) {
                TextView co = new TextView(this);
                co.setText(Sk42.format(m.lat, m.lon));
                co.setTextSize(12.5f);
                co.setTextColor(0xCCFFFFFF);
                co.setPadding(0, (int) dp(3), 0, 0);
                b.addView(co);
            }
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.setPadding(0, (int) dp(6), 0, (int) dp(2));
            final String nm = m.kind.equals("pt") ? (m.ptName.isEmpty() ? "Точка" : m.ptName) : (sos ? "SOS · " : "") + m.from;
            acts.addView(linkChip("На карте", R.drawable.ic_place, v -> {
                closeSheet();
                goTo(m.lat, m.lon, nm, "от " + (m.mine ? "вас" : m.from) + " · " + time, Math.max(15, map.getZoomLevelDouble()));
            }));
            TextView sv = linkChip("Себе", R.drawable.ic_save, v -> editPoint(null, m.lat, m.lon, nm));
            LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            svl.setMarginStart((int) dp(6));
            acts.addView(sv, svl);
            b.addView(acts);
        }
        TextView tm = new TextView(this);
        tm.setText(readText(m));
        tm.setTextSize(10.5f);
        tm.setTextColor(0x99FFFFFF);
        tm.setGravity(android.view.Gravity.END);
        if (m.mine) {
            readMarks.put(m.seq, tm);
            int[] rb = chat.readBy(m.seq);
            if (rb[1] > 0 && rb[0] == rb[1]) tm.setTextColor(0xFF90CAF9);
        }
        b.addView(tm, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setMinimumWidth((int) dp(84));
        row.addView(b, bl);
        b.post(() -> {
            int max = (int) (chatList.getWidth() * 0.8f);
            if (max > 0 && b.getWidth() > max) {
                bl.width = max;
                b.setLayoutParams(bl);
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = (int) dp(cont ? 2 : 8);
        chatList.addView(row, rl);
    }

    /** Сообщение только из 1–3 смайлов — показываем крупно, без пузыря. */
    private static boolean onlyEmoji(String t) {
        String s = t.replaceAll("\\s", "");
        if (s.isEmpty() || s.length() > 24) return false;
        int count = 0;
        boolean afterZwj = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200D) { afterZwj = true; continue; }       // склейка составного смайла
            if (cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF)) continue; // вариант, оттенок кожи
            boolean emoji = Character.getType(cp) == Character.OTHER_SYMBOL || (cp >= 0x1F000 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF) || cp == 0x203C || cp == 0x2049;
            if (!emoji) return false;
            if (!afterZwj) count++;
            afterZwj = false;
        }
        return count >= 1 && count <= 3;
    }

    /** Общая карта группы: список точек, маршрутов и зон. */
    private void showGroupObjects() {
        List<ChatClient.GObj> all = chat.objects();
        sheet("Карта группы · " + all.size());
        if (all.isEmpty()) {
            emptyState(R.drawable.ic_my_location, "Пока пусто",
                    "Откройте свою точку или маршрут и нажмите «На общую карту группы» — его увидят все участники.");
            hChipRow(hChip("Назад в чат", R.drawable.ic_back, false, v -> openChat()));
            return;
        }
        Collections.sort(all, (a, b) -> Long.compare(b.t, a.t));
        String[][] groups = {{"pt", "ТОЧКИ"}, {"route", "МАРШРУТЫ"}, {"area", "ЗОНЫ"}};
        android.location.Location me = myLoc.getLocation();
        for (String[] gr : groups) {
            boolean head = false;
            for (ChatClient.GObj g : all) {
                if (!g.type.equals(gr[0]) || g.pts.isEmpty()) continue;
                if (!head) { hSection(gr[1]); head = true; }
                View lead;
                if (g.type.equals("pt")) {
                    ImageView pin = new ImageView(this);
                    pin.setImageBitmap(UserPoints.pin(g.color == 0 ? 0xFF42A5F5 : g.color, getResources().getDisplayMetrics().density, g.icon));
                    lead = pin;
                } else lead = routeSwatch(g.color == 0 ? 0xFF42A5F5 : g.color);
                String sub = (g.authorName.isEmpty() ? chat.nameOf(g.author) : g.authorName);
                if (g.type.equals("pt") && me != null)
                    sub += " · " + Geo.dist(Geo.inverse(me.getLatitude(), me.getLongitude(), g.pts.get(0)[0], g.pts.get(0)[1])[0]) + " от вас";
                else if (g.type.equals("route")) sub += " · " + Geo.dist(Routes.length(g.pts));
                else if (g.type.equals("area")) sub += " · " + Routes.areaText(Routes.area(g.pts));
                hRow(lead, g.name.isEmpty() ? "без названия" : g.name, sub, hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18),
                        v -> groupObjSheet(g));
            }
        }
        hChipRow(hChip("Назад в чат", R.drawable.ic_back, false, v -> openChat()));
    }

    private static int callColor(String s) {
        int[] c = {0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8, 0xFF4DD0E1, 0xFFF06292, 0xFFAED581, 0xFFFFD54F};
        return c[Math.abs(s.hashCode()) % c.length];
    }

    private void chatAttach() {
        sheetFooter.setVisibility(View.GONE);
        chatOpen = false;
        sheet("Отправить в чат");
        int tx = getColor(R.color.hud_text);
        hRow(hIcon(R.drawable.ic_flag, 0xFFFF8A80, 22), "Статус или SOS", "«На месте», «Выдвигаюсь», «Нужна помощь»… и тревога", null, v -> showStatuses());
        hRow(hIcon(R.drawable.ic_add, tx, 22), "Сфотографировать", "Фото уйдёт зашифрованным, с местом съёмки", null, v -> takePhoto());
        hRow(hIcon(R.drawable.ic_layers, tx, 22), "Фото из галереи", "Координаты возьмутся из снимка, если они в нём есть", null, v -> pickPhoto());
        android.location.Location me = myLoc.getLocation();
        if (me != null) {
            hRow(hIcon(R.drawable.ic_my_location, 0xFF42A5F5, 22), "Моё местоположение", "по GPS, точность ±" + Math.round(me.getAccuracy()) + " м", null, v -> {
                sendPoint("Я здесь", me.getLatitude(), me.getLongitude());
                openChat();
            });
        } else {
            hRow(hIcon(R.drawable.ic_my_location, getColor(R.color.hud_text2), 22), "Моё местоположение", "включите GPS кнопкой ◎ на карте", null, null);
        }
        GeoPoint c = (GeoPoint) map.getMapCenter();
        hRow(hIcon(R.drawable.ic_crosshair_add, tx, 22), "Центр карты (прицел)", Sk42.format(c.getLatitude(), c.getLongitude()), null, v -> {
            sendPoint("Точка", c.getLatitude(), c.getLongitude());
            openChat();
        });
        if (!userPoints.isEmpty()) {
            hSection("МОИ ТОЧКИ");
            for (UserPoints.Point p : userPoints) {
                ImageView pin = new ImageView(this);
                pin.setImageBitmap(UserPoints.pin(p.color, getResources().getDisplayMetrics().density, p.icon));
                hRow(pin, p.name, Sk42.format(p.lat, p.lon), null, v -> {
                    sendPoint(p.name, p.lat, p.lon);
                    openChat();
                });
            }
        }
    }

    private void sendPoint(String name, double lat, double lon) {
        ChatClient.Msg m = new ChatClient.Msg();
        m.kind = "pt";
        m.ptName = name;
        m.lat = lat;
        m.lon = lon;
        sendChat(m);
    }

    private void askCallsign(Runnable then) {
        chatOpen = false;
        sheet("Ваш позывной");
        final EditText e = hInput("Позывной (видят участники)", chat.callsign());
        e.setSingleLine(true);
        sheetContent.addView(e);
        footer("Отмена", "Готово", v -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) {
                Toast.makeText(this, "Введите позывной", Toast.LENGTH_SHORT).show();
                return;
            }
            chat.setCallsign(s);
            hideKeyboard();
            then.run();
        });
    }

    /** Создать группу или вступить. */
    private void chatSetup() {
        chatOpen = false;
        sheet("Чат группы");
        emptyState(R.drawable.ic_chat, "Вы пока не в группе",
                "Создайте группу на своём сервере и пригласите остальных QR-кодом, либо вступите по приглашению.");
        hRow(hIcon(R.drawable.ic_qr, getColor(R.color.hud_accent), 22), "Вступить по приглашению", "Сканировать QR-код у администратора", null,
                v -> { closeSheet(); scanQr(); });
        hRow(hIcon(R.drawable.ic_import, getColor(R.color.hud_text), 22), "Вставить приглашение текстом", "Если прислали ссылку rubezh://…", null,
                v -> pasteInvite());
        hRow(hIcon(R.drawable.ic_add, getColor(R.color.hud_text), 22), "Создать группу", "Нужен адрес своего сервера", null,
                v -> createGroup());
    }

    private void pasteInvite() {
        sheet("Приглашение");
        final EditText e = hInput("rubezh://join?…", "");
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            if (cs != null && ChatClient.isInvite(cs.toString())) e.setText(cs);
        }
        sheetContent.addView(e);
        footer("Отмена", "Вступить", v -> joinInvite(e.getText().toString()));
    }

    private void joinInvite(String inv) {
        if (chat.callsign().isEmpty()) {
            askCallsign(() -> joinInvite(inv));
            return;
        }
        Toast.makeText(this, "Отправляю заявку…", Toast.LENGTH_SHORT).show();
        chat.join(inv, () -> {
            Toast.makeText(this, "Заявка отправлена — ждите, пока администратор примет", Toast.LENGTH_LONG).show();
            openChat();
        }, err -> Toast.makeText(this, "Не удалось вступить: " + err, Toast.LENGTH_LONG).show());
    }

    private void createGroup() {
        sheet("Новая группа");
        final EditText srv = hInput("Адрес сервера, например http://203.0.113.5:8080", prefs.getString("chatLastServer", ""));
        srv.setSingleLine(true);
        final EditText name = hInput("Название группы", "Группа");
        name.setSingleLine(true);
        sheetContent.addView(srv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(8);
        sheetContent.addView(name, lp);
        TextView t = new TextView(this);
        t.setText("Адрес выдаёт команда установки сервера. Вы станете администратором: будете принимать и удалять участников. "
                + "Ключ шифрования создаётся на этом телефоне, сервер его не знает.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12);
        t.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(t);
        footer("Отмена", "Проверить и создать", v -> {
            String s = srv.getText().toString().trim();
            if (s.isEmpty()) {
                Toast.makeText(this, "Укажите адрес сервера", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Проверяю сервер…", Toast.LENGTH_SHORT).show();
            ChatClient.ping(s, err -> {
                if (err != null) {
                    Toast.makeText(this, "Сервер не отвечает: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putString("chatLastServer", s).apply();
                hideKeyboard();
                final String gname = name.getText().toString();
                Runnable go = () -> chat.create(s, gname, () -> {
                    Toast.makeText(this, "Группа создана — вы её администратор", Toast.LENGTH_LONG).show();
                    chat.start();
                    showInvite();
                }, e2 -> Toast.makeText(this, "Не удалось создать: " + e2, Toast.LENGTH_LONG).show());
                if (chat.callsign().isEmpty()) askCallsign(go); else go.run();
            });
        });
    }

    /** Приглашение: срок и число людей → QR. */
    private void showInvite() {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        if (!chat.isAdmin()) {
            Toast.makeText(this, "Приглашать могут только администраторы", Toast.LENGTH_SHORT).show();
            return;
        }
        sheet("Пригласить в «" + chat.groupName() + "»");
        TextView t = new TextView(this);
        t.setText("По QR человек отправит заявку, а вы примете или отклоните её в «Участниках». Без вашего согласия он ничего не увидит.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12.5f);
        t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(4));
        sheetContent.addView(t);
        final double[] hours = {24}, uses = {1};
        heightChipsUnits("Сколько действует", new double[]{1, 24, 168}, hours, "", v -> { });
        LinearLayout r1 = (LinearLayout) sheetContent.getChildAt(sheetContent.getChildCount() - 1);
        String[] l1 = {"1 час", "24 часа", "7 дней"};
        for (int i = 0; i < r1.getChildCount(); i++) ((TextView) r1.getChildAt(i)).setText(l1[i]);
        heightChipsUnits("Сколько человек по нему вступит", new double[]{1, 5, 0}, uses, "", v -> { });
        LinearLayout r2 = (LinearLayout) sheetContent.getChildAt(sheetContent.getChildCount() - 1);
        String[] l2 = {"Один", "До пяти", "Без ограничения"};
        for (int i = 0; i < r2.getChildCount(); i++) ((TextView) r2.getChildAt(i)).setText(l2[i]);
        hSection("ДЕЙСТВУЮЩИЕ ПРИГЛАШЕНИЯ");
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sheetContent.addView(list);
        loadInvites(list, null);
        footer("Назад", "Создать QR", v -> chat.makeInvite((int) hours[0], (int) uses[0], inv -> {
            showQr(inv, "приглашение · " + (int) hours[0] + " ч");
            TextView n = new TextView(this);
            n.setText("Участник: «Чат» → «Вступить по приглашению» → навести камеру. После этого примите его в «Участниках».");
            n.setTextColor(getColor(R.color.hud_text2));
            n.setTextSize(12.5f);
            n.setPadding((int) dp(4), (int) dp(6), (int) dp(4), 0);
            sheetContent.addView(n);
            hChipRow(hChip("Скопировать текстом", R.drawable.ic_share, false, x -> copy(inv, "Приглашение скопировано")),
                    hChip("Участники", R.drawable.ic_my_location, true, x -> showPeople()));
        }, err -> Toast.makeText(this, "Не удалось: " + err, Toast.LENGTH_LONG).show()));
    }

    private void loadInvites(LinearLayout list, String revoke) {
        chat.invites(revoke, invs -> {
            list.removeAllViews();
            if (invs.isEmpty()) {
                TextView t = new TextView(this);
                t.setText("Нет действующих приглашений");
                t.setTextColor(getColor(R.color.hud_text2));
                t.setTextSize(12.5f);
                t.setPadding((int) dp(4), (int) dp(4), 0, (int) dp(4));
                list.addView(t);
                return;
            }
            SimpleDateFormat f = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
            for (ChatClient.Invite v : invs) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding((int) dp(8), (int) dp(6), 0, (int) dp(6));
                TextView t = new TextView(this);
                t.setText("до " + f.format(new Date(v.expires)) + " · " + (v.uses == 0 ? "без ограничения" : v.used + " из " + v.uses)
                        + (v.by.isEmpty() ? "" : " · " + chat.nameOf(v.by)));
                t.setTextColor(getColor(R.color.hud_text));
                t.setTextSize(13);
                row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                TextView x = hChip("Отозвать", 0, false, z -> loadInvites(list, v.code));
                row.addView(x, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(36)));
                list.addView(row);
            }
        }, err -> { });
    }

    /** Участники группы и управление ими. */
    private void showPeople() {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        List<ChatClient.Person> ps = chat.people();
        int act = 0;
        for (ChatClient.Person p : ps) if (p.active()) act++;
        sheet("Участники · " + act);
        boolean admin = chat.isAdmin();
        if (admin) actionRow(hChip("Пригласить", R.drawable.ic_qr, true, v -> showInvite()));
        boolean pendHead = false, restHead = false, goneHead = false;
        for (ChatClient.Person p : ps) {
            if (p.role.equals("pending")) {
                if (!admin) continue;
                if (!pendHead) { hSection("ЖДУТ ПОДТВЕРЖДЕНИЯ"); pendHead = true; }
                hRow(personDot(p), p.callsign.isEmpty() ? "без позывного" : p.callsign,
                        "заявка " + MembersOverlay.age(p.joined) + " назад", null, null);
                hChipRow(hChip("Принять", R.drawable.ic_check, true, v -> chat.manage("approve", p, () -> {
                            Toast.makeText(this, p.callsign + " принят(а) в группу", Toast.LENGTH_SHORT).show();
                            showPeople();
                        }, err -> Toast.makeText(this, "Не удалось: " + err, Toast.LENGTH_LONG).show())),
                        hChip("Отклонить", R.drawable.ic_close, false, v -> chat.manage("reject", p, this::showPeople,
                                err -> Toast.makeText(this, "Не удалось: " + err, Toast.LENGTH_LONG).show())));
            } else if (p.active()) {
                if (!restHead) { hSection("В ГРУППЕ"); restHead = true; }
                String sub = p.roleName() + " · " + (p.id.equals(chat.deviceId()) ? "это вы" : "был(а) " + MembersOverlay.age(p.seen)
                        + (MembersOverlay.age(p.seen).equals("сейчас") ? "" : " назад"));
                hRow(personDot(p), p.callsign.isEmpty() ? "без позывного" : p.callsign, sub,
                        admin && !p.id.equals(chat.deviceId()) && !p.role.equals("owner") ? hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18) : null,
                        admin && !p.id.equals(chat.deviceId()) && !p.role.equals("owner") ? v -> personActions(p) : null);
            } else if (admin) {
                if (!goneHead) { hSection("БЫВШИЕ"); goneHead = true; }
                hRow(personDot(p), p.callsign.isEmpty() ? "без позывного" : p.callsign, p.roleName(), null, null);
            }
        }
        TextView n = new TextView(this);
        n.setText(admin ? "Удалённый участник сразу теряет доступ, а группа переходит на новый ключ шифрования — "
                + "он не сможет прочитать ничего нового. Остальным ничего делать не нужно."
                : "Принимать и удалять участников могут администраторы.");
        n.setTextColor(getColor(R.color.hud_text2));
        n.setTextSize(12);
        n.setPadding((int) dp(4), (int) dp(12), (int) dp(4), 0);
        sheetContent.addView(n);
    }

    private View personDot(ChatClient.Person p) {
        TextView d = new TextView(this);
        d.setText(MembersOverlay.initials(p.callsign));
        d.setTextColor(Color.WHITE);
        d.setTextSize(11);
        d.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        d.setGravity(android.view.Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(p.active() || p.role.equals("pending") ? MembersOverlay.color(p.callsign) : 0xFF555B66);
        d.setBackground(g);
        d.setLayoutParams(new LinearLayout.LayoutParams((int) dp(32), (int) dp(32)));
        return d;
    }

    private void personActions(ChatClient.Person p) {
        sheet(p.callsign.isEmpty() ? "Участник" : p.callsign);
        LinearLayout card = hCard();
        addKV(card, "Роль", p.roleName());
        addKV(card, "В группе с", new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(new Date(p.joined)));
        addKV(card, "Последний раз на связи", MembersOverlay.age(p.seen) + (MembersOverlay.age(p.seen).equals("сейчас") ? "" : " назад"));
        sheetContent.addView(card);
        int tx = getColor(R.color.hud_text);
        hSection("ДЕЙСТВИЯ");
        if (p.role.equals("admin"))
            hRow(hIcon(R.drawable.ic_lock, tx, 22), "Снять права администратора", null, null,
                    v -> chat.manage("member", p, this::showPeople, err -> Toast.makeText(this, err, Toast.LENGTH_LONG).show()));
        else
            hRow(hIcon(R.drawable.ic_lock, tx, 22), "Сделать администратором", "Сможет принимать и удалять участников", null,
                    v -> chat.manage("admin", p, this::showPeople, err -> Toast.makeText(this, err, Toast.LENGTH_LONG).show()));
        hRow(hIcon(R.drawable.ic_delete, 0xFFFF6B60, 22), "Удалить из группы", "Сразу потеряет доступ, ключ группы сменится", null,
                v -> new AlertDialog.Builder(this)
                        .setTitle("Удалить «" + p.callsign + "»?")
                        .setMessage("Он сразу потеряет доступ к чату и меткам. Группа перейдёт на новый ключ.")
                        .setPositiveButton("Удалить", (d, w) -> chat.manage("remove", p, () -> {
                            Toast.makeText(this, p.callsign + " удалён(а), ключ группы сменён", Toast.LENGTH_LONG).show();
                            showPeople();
                        }, err -> Toast.makeText(this, "Не удалось: " + err, Toast.LENGTH_LONG).show()))
                        .setNegativeButton("Отмена", null)
                        .show());
        hChipRow(hChip("Назад к участникам", R.drawable.ic_back, false, v -> showPeople()));
    }

    private void chatSettings() {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        sheet(chat.groupName());
        int pend = 0, act = 0;
        for (ChatClient.Person p : chat.people()) { if (p.role.equals("pending")) pend++; if (p.active()) act++; }
        String roleName = chat.role().equals("owner") ? "создатель" : chat.role().equals("admin") ? "администратор" : "участник";

        LinearLayout g1 = layerBox();
        layerLink(g1, R.drawable.ic_my_location, "Участники",
                act + (chat.isAdmin() && pend > 0 ? " · ждут " + pend : ""), v -> showPeople());
        if (chat.isAdmin()) {
            layerDivider(g1);
            layerLink(g1, R.drawable.ic_qr, "Пригласить", "QR", v -> showInvite());
        }
        layerDivider(g1);
        layerLink(g1, R.drawable.ic_chat, "Позывной", chat.callsign(), v -> askCallsign(this::openChat));
        setBlock("ГРУППА · вы " + roleName, g1);

        LinearLayout g2 = layerBox();
        setSwitch(g2, "Показывать меня на карте", prefs.getBoolean("shareLoc", false), on -> { setSharing(on); chatSettings(); });
        if (prefs.getBoolean("shareLoc", false)) {
            setSeg(g2, "Отправлять раз в", new String[]{"30 с", "1 мин", "5 мин", "15 мин"}, new int[]{30, 60, 300, 900},
                    prefs.getInt("shareEvery", 60), v -> prefs.edit().putInt("shareEvery", v).apply());
            layerDivider(g2);
            setSwitch(g2, "След за последний час", prefs.getBoolean("shareTrail", false), on -> prefs.edit().putBoolean("shareTrail", on).apply());
        }
        layerDivider(g2);
        layerLink(g2, R.drawable.ic_place, "Кто на карте", Integer.toString(chat.members().size()), v -> showMembers());
        setBlock("МЕСТОПОЛОЖЕНИЕ", g2);

        LinearLayout g3 = layerBox();
        setSwitch(g3, "Работать в фоне", prefs.getBoolean("groupBg", true), on -> {
            prefs.edit().putBoolean("groupBg", on).apply();
            if (on) askNotifPermission();
            restartGroupService();
        });
        setSeg(g3, "Исчезающие сообщения", new String[]{"Выкл", "1 ч", "24 ч", "7 дн"}, new int[]{0, 3600, 86400, 604800},
                chat.ttl(), chat::setTtl);
        setBlock("СООБЩЕНИЯ", g3);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        foot.setPadding((int) dp(6), (int) dp(14), (int) dp(6), 0);
        TextView srv = new TextView(this);
        srv.setText((chat.lastOk() ? "● Сервер в работе" : "○ Нет связи — переподключаюсь")
                + (chat.role().equals("owner") ? "\n" + chat.server().replace("http://", "") : ""));
        srv.setTextSize(12);
        srv.setTextColor(chat.lastOk() ? 0xFF66BB6A : 0xFFFFB74D);
        foot.addView(srv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView leave = new TextView(this);
        leave.setText("Выйти из группы");
        leave.setTextSize(13);
        leave.setTextColor(0xFFFF6B60);
        leave.setPadding((int) dp(10), (int) dp(8), (int) dp(4), (int) dp(8));
        leave.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Выйти из группы?")
                .setMessage(chat.role().equals("owner")
                        ? "Вы создатель. Группа продолжит работать, но управлять ею смогут только назначенные вами администраторы. Вернуться можно по приглашению."
                        : "Переписка на этом телефоне удалится. Чтобы вернуться, понадобится новое приглашение.")
                .setPositiveButton("Выйти", (d, w) -> {
                    if (prefs.getBoolean("shareLoc", false)) chat.sendLoc(0, 0, 0, Float.NaN, true);
                    prefs.edit().putBoolean("shareLoc", false).apply();
                    chat.leave(null);
                    membersOverlay.setMembers(new ArrayList<>());
                    closeSheet();
                    Toast.makeText(this, "Вы вышли из группы", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show());
        foot.addView(leave);
        sheetContent.addView(foot);
        hChipRow(hChip("Назад в чат", R.drawable.ic_back, false, v -> openChat()));
    }

    private void setBlock(String title, LinearLayout box) {
        TextView h = hSection(title);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sheetContent.addView(box, bl);
    }

    /** Строка с переключателем внутри блока. */
    private void setSwitch(LinearLayout box, String title, boolean value, Toggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(14), (int) dp(6), (int) dp(10), (int) dp(6));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(getColor(R.color.hud_text));
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, on) -> cb.on(on));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        box.addView(row);
    }

    /** Подпись и сегменты выбора внутри блока. */
    private void setSeg(LinearLayout box, String title, String[] labels, int[] values, int cur, java.util.function.IntConsumer cb) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.hud_text2));
        t.setPadding((int) dp(14), (int) dp(4), (int) dp(14), (int) dp(6));
        box.addView(t);
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(3));
        GradientDrawable sg = new GradientDrawable();
        sg.setCornerRadius(dp(10));
        sg.setColor(0xFF15181D);
        seg.setBackground(sg);
        final List<TextView> bs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int v = values[i];
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(0, (int) dp(7), 0, (int) dp(7));
            b.setTag(v);
            b.setOnClickListener(x -> {
                cb.accept(v);
                for (TextView o : bs) styleSeg(o, (int) o.getTag() == v);
            });
            bs.add(b);
            seg.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            styleSeg(b, v == cur);
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.setMargins((int) dp(10), 0, (int) dp(10), (int) dp(10));
        box.addView(seg, sl);
    }


    // ================= Общая карта группы =================

    private PointsOverlay groupPtsOverlay;
    private final List<Routes.Route> groupRoutes = new ArrayList<>();

    private List<Routes.Route> allRoutes() {
        if (groupRoutes.isEmpty()) return routes;
        List<Routes.Route> a = new ArrayList<>(routes);
        a.addAll(groupRoutes);
        return a;
    }

    private void refreshGroupMap() {
        if (chat == null || groupPtsOverlay == null) return;
        boolean show = prefs.getBoolean("showGroupMap", true) && chat.active();
        List<UserPoints.Point> pts = new ArrayList<>();
        groupRoutes.clear();
        if (show) {
            for (ChatClient.GObj g : chat.objects()) {
                if (g.pts.isEmpty()) continue;
                if (g.type.equals("pt")) {
                    UserPoints.Point p = new UserPoints.Point();
                    p.id = g.id;
                    p.name = g.name;
                    p.note = g.note;
                    p.color = g.color == 0 ? 0xFF42A5F5 : g.color;
                    p.icon = g.icon;
                    p.lat = g.pts.get(0)[0];
                    p.lon = g.pts.get(0)[1];
                    pts.add(p);
                } else {
                    Routes.Route r = new Routes.Route();
                    r.id = "g:" + g.id;
                    r.name = g.name;
                    r.color = g.color == 0 ? 0xFF42A5F5 : g.color;
                    r.area = g.type.equals("area");
                    r.pts.addAll(g.pts);
                    groupRoutes.add(r);
                }
            }
        }
        groupPtsOverlay.setPoints(pts);
        pathOverlay.setSaved(allRoutes());
        map.invalidate();
    }

    private ChatClient.GObj objFromPoint(UserPoints.Point p) {
        ChatClient.GObj g = new ChatClient.GObj();
        g.type = "pt";
        g.name = p.name;
        g.note = p.note == null ? "" : p.note;
        g.color = p.color;
        g.icon = p.icon == null ? "" : p.icon;
        g.pts.add(new double[]{p.lat, p.lon});
        return g;
    }

    private ChatClient.GObj objFromRoute(Routes.Route r) {
        ChatClient.GObj g = new ChatClient.GObj();
        g.type = r.area ? "area" : "route";
        g.name = r.name;
        g.color = r.color;
        g.pts.addAll(r.pts);
        return g;
    }

    private void shareToGroupMap(ChatClient.GObj g) {
        if (chat == null || !chat.active()) {
            Toast.makeText(this, "Сначала вступите в группу (кнопка чата)", Toast.LENGTH_LONG).show();
            return;
        }
        chat.putObj(g, () -> Toast.makeText(this, "«" + g.name + "» добавлен(а) на общую карту группы", Toast.LENGTH_SHORT).show(),
                err -> Toast.makeText(this, "Не отправлено: " + err, Toast.LENGTH_LONG).show());
    }

    private void groupObjSheet(ChatClient.GObj g) {
        sheet(g.name.isEmpty() ? "Объект группы" : g.name);
        LinearLayout card = hCard();
        addKV(card, "На общей карте", g.type.equals("pt") ? "точка" : g.type.equals("area") ? "зона" : "маршрут");
        addKV(card, "Добавил(а)", g.authorName.isEmpty() ? chat.nameOf(g.author) : g.authorName);
        addKV(card, "Обновлено", new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(g.t)));
        if (g.type.equals("area")) addKV(card, "Площадь", Routes.areaText(Routes.area(g.pts)));
        else if (g.type.equals("route")) addKV(card, "Длина", Geo.dist(Routes.length(g.pts)));
        if (!g.note.isEmpty()) addKV(card, "Заметка", g.note);
        sheetContent.addView(card);
        final double[] p0 = g.pts.get(0);
        if (g.type.equals("pt")) coordsCard(p0[0], p0[1]);
        Routes.Route asRoute = new Routes.Route();
        asRoute.name = g.name;
        asRoute.color = g.color;
        asRoute.area = g.type.equals("area");
        asRoute.pts.addAll(g.pts);
        actionRow(
                vAction(R.drawable.ic_nav, "Вести", true, v -> {
                    closeSheet();
                    if (g.type.equals("pt")) startNav(Collections.singletonList(p0), 0, g.name);
                    else guideOptions(asRoute, false);
                }),
                vAction(R.drawable.ic_place, "На карте", false, v -> {
                    closeSheet();
                    if (g.type.equals("pt")) goTo(p0[0], p0[1], g.name, "карта группы", Math.max(15, map.getZoomLevelDouble()));
                    else { if (follow) { follow = false; styleMyLoc(); } fitRoute(asRoute); }
                }),
                vAction(R.drawable.ic_flag, "Навести", false, v -> { closeSheet(); setTarget(p0[0], p0[1], g.name); }),
                vAction(R.drawable.ic_save, "Себе", false, v -> {
                    if (g.type.equals("pt")) editPoint(null, p0[0], p0[1], g.name);
                    else {
                        Routes.Route r = new Routes.Route();
                        r.id = Long.toString(System.currentTimeMillis(), 36);
                        r.created = System.currentTimeMillis();
                        r.name = g.name;
                        r.color = g.color;
                        r.area = asRoute.area;
                        r.pts.addAll(g.pts);
                        routes.add(r);
                        saveRoutes();
                        closeSheet();
                        Toast.makeText(this, "Сохранено в ваши маршруты", Toast.LENGTH_SHORT).show();
                    }
                }));
        if (chat.canEdit(g, chat.deviceId()))
            hChipRow(hChip("Убрать с общей карты", R.drawable.ic_delete, false, v -> new AlertDialog.Builder(this)
                    .setTitle("Убрать «" + g.name + "» с общей карты?")
                    .setMessage("Объект исчезнет у всех участников.")
                    .setPositiveButton("Убрать", (d, w) -> chat.delObj(g, () -> { closeSheet(); Toast.makeText(this, "Убрано", Toast.LENGTH_SHORT).show(); },
                            err -> Toast.makeText(this, "Не удалось: " + err, Toast.LENGTH_LONG).show()))
                    .setNegativeButton("Отмена", null)
                    .show()));
    }

    // ================= Статусы и SOS =================

    private static final String[] STATUSES = {"На месте", "Выдвигаюсь", "Всё в норме", "Задерживаюсь", "Нужна помощь", "Связь пропадает"};

    private void showStatuses() {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        sheet("Быстрый статус");
        android.location.Location me = myLoc.getLocation();
        TextView t = new TextView(this);
        t.setText(me != null ? "К статусу добавятся ваши координаты (GPS ±" + Math.round(me.getAccuracy()) + " м)."
                : "GPS выключен — статус уйдёт без координат. Включите ◎ на карте, чтобы приложить место.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12.5f);
        t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(t);
        for (int i = 0; i < STATUSES.length; i += 2) {
            final String a = STATUSES[i], b = STATUSES[i + 1];
            hChipRow(hChip(a, R.drawable.ic_flag, false, v -> sendStatus("st", a)), hChip(b, R.drawable.ic_flag, false, v -> sendStatus("st", b)));
        }
        hSection("ТРЕВОГА");
        TextView sos = new TextView(this);
        sos.setText("SOS — удерживайте 2 секунды");
        sos.setTextColor(Color.WHITE);
        sos.setTextSize(16);
        sos.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sos.setGravity(android.view.Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0xFFD32F2F);
        sos.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(64));
        sheetContent.addView(sos, lp);
        final Runnable fire = () -> {
            vibrate(500);
            sendStatus("sos", "Нужна помощь!");
        };
        sos.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.6f);
                vibrate(40);
                v.postDelayed(fire, 2000);
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                v.removeCallbacks(fire);
            }
            return true;
        });
        TextView n = new TextView(this);
        n.setText("SOS громко оповестит всех участников (даже на беззвучном) и покажет вас на карте.");
        n.setTextColor(getColor(R.color.hud_text2));
        n.setTextSize(12);
        n.setPadding((int) dp(4), (int) dp(8), (int) dp(4), 0);
        sheetContent.addView(n);
        hChipRow(hChip("Назад в чат", R.drawable.ic_back, false, v -> openChat()));
    }

    private void sendStatus(String kind, String text) {
        ChatClient.Msg m = new ChatClient.Msg();
        m.kind = kind;
        m.text = text;
        android.location.Location me = myLoc.getLocation();
        if (me != null) {
            m.hasLoc = true;
            m.lat = me.getLatitude();
            m.lon = me.getLongitude();
        }
        chat.send(m, () -> {
            Toast.makeText(this, kind.equals("sos") ? "SOS отправлен всем" : "Статус отправлен", Toast.LENGTH_SHORT).show();
            openChat();
        }, err -> Toast.makeText(this, "Не отправлено: " + err, Toast.LENGTH_LONG).show());
    }

    private void showSos(ChatClient.Msg m) {
        vibrate(800);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("SOS · " + (m.from.isEmpty() ? "участник" : m.from))
                .setMessage((m.text.isEmpty() ? "Нужна помощь" : m.text) + "\n" + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.t))
                        + (m.hasLoc ? "\n" + Sk42.format(m.lat, m.lon) : "\nбез координат"))
                .setNegativeButton("Закрыть", null);
        if (m.hasLoc) b.setPositiveButton("Показать на карте", (d, w) -> {
            closeSheet();
            goTo(m.lat, m.lon, "SOS · " + m.from, new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.t)), Math.max(15, map.getZoomLevelDouble()));
        });
        b.show();
    }

    // ================= Голос и фото в чате =================

    private android.media.MediaRecorder chatRec;
    private File chatRecFile;
    private long chatRecStart;
    private static final int REQ_CAM = 35, REQ_GAL = 36, REQ_CAMPERM = 24;
    private Uri camUri;

    private void chatVoiceStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        stopPlaying();
        try {
            chatRecFile = new File(getCacheDir(), "chatrec.m4a");
            chatRec = Build.VERSION.SDK_INT >= 31 ? new android.media.MediaRecorder(this) : new android.media.MediaRecorder();
            chatRec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            chatRec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            chatRec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            chatRec.setAudioEncodingBitRate(24000);
            chatRec.setAudioSamplingRate(16000);
            chatRec.setAudioChannels(1);
            chatRec.setMaxDuration(60_000);
            chatRec.setOutputFile(chatRecFile.getAbsolutePath());
            chatRec.setOnInfoListener((mr, what, extra) -> {
                if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) chatVoiceStop(true);
            });
            chatRec.prepare();
            chatRec.start();
            chatRecStart = System.currentTimeMillis();
            vibrate(50);
        } catch (Exception e) {
            if (chatRec != null) chatRec.release();
            chatRec = null;
            Toast.makeText(this, "Микрофон недоступен: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        // строка записи вместо поля ввода
        sheetFooter.removeAllViews();
        TextView cancel = hChip("Отмена", R.drawable.ic_close, false, v -> chatVoiceStop(false));
        final TextView rec = new TextView(this);
        rec.setTextColor(0xFFFF5252);
        rec.setTextSize(15);
        rec.setGravity(android.view.Gravity.CENTER);
        TextView send = hChip("Отправить", R.drawable.ic_send, true, v -> chatVoiceStop(true));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, (int) dp(44), 1);
        sheetFooter.addView(cancel, l1);
        sheetFooter.addView(rec, new LinearLayout.LayoutParams(0, (int) dp(44), 1));
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, (int) dp(44), 1);
        sheetFooter.addView(send, l3);
        recHandler.post(new Runnable() {
            @Override
            public void run() {
                if (chatRec == null) return;
                rec.setText("● " + mmss(System.currentTimeMillis() - chatRecStart) + " / 1:00");
                recHandler.postDelayed(this, 300);
            }
        });
    }

    private void chatVoiceStop(boolean sendIt) {
        if (chatRec == null) return;
        long dur = System.currentTimeMillis() - chatRecStart;
        try {
            chatRec.stop();
        } catch (Exception e) {
            sendIt = false;
        }
        chatRec.release();
        chatRec = null;
        if (sendIt && dur > 700) {
            try {
                ChatClient.Msg m = new ChatClient.Msg();
                m.kind = "voice";
                m.ms = dur;
                m.data = android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(chatRecFile.toPath()),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                Toast.makeText(this, "Отправляю голосовое…", Toast.LENGTH_SHORT).show();
                sendChat(m);
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось прочитать запись", Toast.LENGTH_LONG).show();
            }
        }
        chatRecFile.delete();
        openChat();
    }

    private void takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMPERM);
            return;
        }
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "rubezh_" + System.currentTimeMillis() + ".jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rubezh");
            camUri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, camUri);
            startActivityForResult(i, REQ_CAM);
        } catch (Exception e) {
            Toast.makeText(this, "Камера недоступна: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Фото для чата"), REQ_GAL);
        } catch (Exception e) {
            Toast.makeText(this, "Нет галереи", Toast.LENGTH_LONG).show();
        }
    }

    /** Сжать фото (до 1280 px, JPEG), взять координаты из снимка или GPS и отправить. */
    private void sendPhoto(Uri uri, boolean fromCamera) {
        Toast.makeText(this, "Готовлю фото…", Toast.LENGTH_SHORT).show();
        final android.location.Location me = myLoc.getLocation();
        new Thread(() -> {
            try {
                byte[] raw = readUri(uri);
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = s;
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                double[] ll = null;
                int rot = 0;
                try {
                    android.media.ExifInterface ex = new android.media.ExifInterface(new java.io.ByteArrayInputStream(raw));
                    float[] f = new float[2];
                    if (ex.getLatLong(f)) ll = new double[]{f[0], f[1]};
                    int or = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                    rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
                } catch (Exception ignored) {
                }
                float sc = 1280f / Math.max(b.getWidth(), b.getHeight());
                android.graphics.Matrix mx = new android.graphics.Matrix();
                if (sc < 1) mx.postScale(sc, sc);
                if (rot != 0) mx.postRotate(rot);
                android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int q = 75;
                do {
                    bo.reset();
                    out.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bo);
                    q -= 10;
                } while (bo.size() > 700_000 && q > 30);
                ChatClient.Msg m = new ChatClient.Msg();
                m.kind = "photo";
                m.data = android.util.Base64.encodeToString(bo.toByteArray(),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                if (ll == null && fromCamera && me != null) ll = new double[]{me.getLatitude(), me.getLongitude()};
                if (ll != null) {
                    m.hasLoc = true;
                    m.lat = ll[0];
                    m.lon = ll[1];
                    m.ptName = "Место снимка";
                }
                if (fromCamera) getContentResolver().delete(uri, null, null); // не оставлять снимок в галерее
                runOnUiThread(() -> sendChat(m));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Фото не отправлено: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showPhoto(ChatClient.Msg m) {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        sheet("Фото · " + (m.from.isEmpty() ? "участник" : m.from));
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(m.media));
        sheetContent.addView(iv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (m.hasLoc) {
            coordsCard(m.lat, m.lon);
            hChipRow(hChip("Показать на карте", R.drawable.ic_place, true, v -> {
                closeSheet();
                goTo(m.lat, m.lon, "Фото · " + m.from, new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(m.t)), Math.max(15, map.getZoomLevelDouble()));
            }), hChip("Назад в чат", R.drawable.ic_back, false, v -> openChat()));
        } else hChipRow(hChip("Назад в чат", R.drawable.ic_back, false, v -> openChat()));
    }

    // ================= Прочитано =================

    private final Map<Long, TextView> readMarks = new HashMap<>();

    private String readText(ChatClient.Msg m) {
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.t));
        String ttl = m.exp > 0 ? "⏱ " : "";
        if (!m.mine) return ttl + time;
        int[] r = chat.readBy(m.seq);
        if (r[1] == 0 || r[0] == 0) return ttl + time + "  ✓";
        return ttl + time + "  ✓✓" + (r[0] < r[1] ? " " + r[0] + "/" + r[1] : "");
    }

    private void refreshReadMarks() {
        if (!chatOpen) return;
        for (ChatClient.Msg m : chat.history()) {
            TextView t = readMarks.get(m.seq);
            if (t != null && m.mine) {
                t.setText(readText(m));
                int[] r = chat.readBy(m.seq);
                t.setTextColor(r[1] > 0 && r[0] == r[1] ? 0xFF64B5F6 : getColor(R.color.hud_text2));
            }
        }
    }

    // ================= Экстренное удаление =================

    /** Стереть всё личное: точки, маршруты, треки, переписку, группу, сохранённые карты и дороги. */
    private void wipeAll() {
        try {
            if (guideOn()) stopGuide(false);
            if (navOn()) stopNav();
            if (track != null) track = null;
            if (locOn) stopLocation();
            if (chat != null) {
                if (chat.configured()) chat.leave(null);
                chat.dropLegacy();
            }
            GroupService.stop(this);
            userPoints.clear();
            routes.clear();
            savePoints();
            saveRoutes();
            for (String d : new String[]{"voice", "chatmedia", "roads"}) delTree(new File(getFilesDir(), d));
            File[] fs = getFilesDir().listFiles();
            if (fs != null) for (File f : fs) if (f.getName().startsWith("chat") || f.getName().startsWith("restore")) f.delete();
            delTree(getCacheDir());
            try {
                new org.osmdroid.tileprovider.modules.SqlTileWriter().purgeCache();
            } catch (Exception ignored) {
            }
            String[] keep = {"mapStyle", "labelsRu", "lbsDate"};
            java.util.Map<String, ?> all = prefs.getAll();
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : all.keySet()) {
                boolean kp = false;
                for (String x : keep) if (x.equals(k)) kp = true;
                if (!kp) ed.remove(k);
            }
            ed.commit();
        } catch (Exception ignored) {
        }
        recreate();
    }

    private static void delTree(File f) {
        if (f == null || !f.exists()) return;
        File[] c = f.listFiles();
        if (c != null) for (File x : c) delTree(x);
        f.delete();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("Стереть всё?")
                .setMessage("Удалятся точки, маршруты, треки, голосовые заметки, переписка и группа, сохранённые карты, ПИН-код. "
                        + "Отменить нельзя. Сделайте резервную копию, если она нужна.")
                .setPositiveButton("Стереть", (d, w) -> wipeAll())
                .setNegativeButton("Отмена", null)
                .show();
    }


    // ================= Смайлы =================

    private static final String[][] EMOJI = {
            {"Лица", "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😉 😊 😇 😍 😘 😋 😜 🤪 😎 🤓 🧐 🤔 🤨 😐 😑 😶 🙄 😏 😬 😌 😔 😪 😴 😷 🤒 🤕 🤢 🥵 🥶 😵 🤯 🤠 🥳 😕 😟 🙁 😮 😲 😳 🥺 😦 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 😤 😡 😠 🤬 💀 ☠️ 🤡 👻 👽 🤖 💩"},
            {"Жесты", "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐 🖖 👋 🤝 🙏 💪 👊 ✊ 🤛 🤜 👏 🙌 👐 🤲 ✍️ 👀 👂 🫡"},
            {"Знаки", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❗ ❓ ‼️ ⁉️ ✅ ❌ ⭕ 🛑 ⛔ 🚫 ⚠️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ ⬆️ ⬇️ ⬅️ ➡️ ↗️ ↘️ ↙️ ↖️ 🔄 🆗 🆘 💯 🔥 ⚡ 💥 ✨ 🎯 📍 📌 🚩 🏁"},
            {"Разное", "☀️ 🌙 ⭐ ☁️ 🌧 ⛈ ❄️ 🌫 💨 💧 🌲 🌳 🌾 🏠 🏚 🏭 ⛺ 🌉 🛣 🚗 🚙 🚚 🛻 🚜 🏍 🚲 🚁 ✈️ 🛰 📡 📻 📱 🔋 🔦 🧭 🗺 ⏱ ⏰ 🍞 🍖 🍲 ☕ 🍺 🚬 💊 🩹 🔧 🔨 🪓 📦 🎒 💰 🔑 🔒 🐕 🐈 🐦"}};

    private View emojiPanel;

    private void hideEmoji() {
        if (emojiPanel != null && emojiPanel.getParent() != null) ((ViewGroup) emojiPanel.getParent()).removeView(emojiPanel);
        emojiPanel = null;
    }

    private void toggleEmoji(EditText input) {
        if (emojiPanel != null) {
            hideEmoji();
            input.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(input, 0);
            return;
        }
        hideKeyboard();
        input.clearFocus();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // вкладки
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        ScrollView sc = new ScrollView(this);
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        sc.addView(grid);
        final List<TextView> tabViews = new ArrayList<>();
        String recent = prefs.getString("emojiRecent", "");
        final List<String[]> cats = new ArrayList<>();
        if (!recent.isEmpty()) cats.add(new String[]{"Недавние", recent});
        cats.addAll(Arrays.asList(EMOJI));
        for (int i = 0; i < cats.size(); i++) {
            final int ci = i;
            TextView t = new TextView(this);
            t.setText(cats.get(i)[0]);
            t.setTextSize(12.5f);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(0, (int) dp(6), 0, (int) dp(6));
            t.setOnClickListener(v -> {
                fillEmojiGrid(grid, cats.get(ci)[1], input);
                sc.scrollTo(0, 0);
                for (int k = 0; k < tabViews.size(); k++) styleSeg(tabViews.get(k), k == ci);
            });
            tabViews.add(t);
            tabs.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            styleSeg(t, i == 0);
        }
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(10));
        tg.setColor(0xFF15181D);
        tabs.setBackground(tg);
        tabs.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(3));
        panel.addView(tabs);
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(210));
        scl.topMargin = (int) dp(6);
        panel.addView(sc, scl);
        fillEmojiGrid(grid, cats.get(0)[1], input);
        LinearLayout gp = findViewById(R.id.genPanel);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = (int) dp(8);
        gp.addView(panel, gp.indexOfChild(sheetFooter) + 1, pl);
        emojiPanel = panel;
        scrollChatDown();
    }

    private void fillEmojiGrid(LinearLayout grid, String list, EditText input) {
        grid.removeAllViews();
        String[] em = list.trim().split("\\s+");
        int cols = 8;
        LinearLayout row = null;
        for (int i = 0; i < em.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            final String e = em[i];
            TextView t = new TextView(this);
            t.setText(e);
            t.setTextSize(25);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(0, (int) dp(5), 0, (int) dp(5));
            t.setBackgroundResource(R.drawable.bg_hud_btn);
            t.setOnClickListener(v -> {
                int st = Math.max(0, input.getSelectionStart());
                input.getText().insert(st, e);
                rememberEmoji(e);
            });
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        if (row != null) for (int k = row.getChildCount(); k < cols; k++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void rememberEmoji(String e) {
        List<String> r = new ArrayList<>();
        r.add(e);
        for (String x : prefs.getString("emojiRecent", "").trim().split("\\s+"))
            if (!x.isEmpty() && !x.equals(e) && r.size() < 24) r.add(x);
        prefs.edit().putString("emojiRecent", String.join(" ", r)).apply();
    }

    @SuppressWarnings("unused")
    private static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
