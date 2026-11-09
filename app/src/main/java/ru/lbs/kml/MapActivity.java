package ru.lbs.kml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
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
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
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
            "Вся ЛБС", "Херсонская", "Запорожская", "Днепропетровская",
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
    private final FolderOverlay cmpFolder = new FolderOverlay();
    private GridOverlay gridOverlay;
    private ReticleOverlay reticleOverlay;
    private PlacesOverlay placesOverlay;
    private PointsOverlay pointsOverlay;
    private PathOverlay pathOverlay;
    private GeoOverlay geoOverlay;
    private LayerOverlay layerOverlay;
    private final RasterLayerOverlay rasterOverlay = new RasterLayerOverlay();
    private final Map<String, PgdFile> pgdOpen = new HashMap<>();
    private List<UserLayers.Layer> userLayers = new ArrayList<>();
    private SketchOverlay sketch;
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
        map.getOverlays().add(rasterOverlay);
        map.getOverlays().add(lbsFolder);
        map.getOverlays().add(cmpFolder);
        gridOverlay = new GridOverlay(d);
        map.getOverlays().add(gridOverlay);
        View.OnLayoutChangeListener insetL = (v, l, t, r, b, ol, ot, or, ob) ->
                gridOverlay.setInsets(findViewById(R.id.topBar).getBottom() + dp(4), findViewById(R.id.bottomBar).getTop());
        findViewById(R.id.topBar).addOnLayoutChangeListener(insetL);
        findViewById(R.id.bottomBar).addOnLayoutChangeListener(insetL);
        placesOverlay = new PlacesOverlay(d);
        placesOverlay.setObstacles((mv, out) -> {
            // кнопки и карточки поверх карты
            int[] mapAt = new int[2], at = new int[2];
            mv.getLocationInWindow(mapAt);
            for (int id : new int[]{R.id.rightCol, R.id.coordCard, R.id.btnSearch, R.id.searchPill, R.id.dock, R.id.targetCard, R.id.navPanel, R.id.offlineChip, R.id.vsChip, R.id.cmpChip}) {
                View v = findViewById(id);
                if (v == null || !v.isShown() || v.getWidth() == 0) continue;
                v.getLocationInWindow(at);
                float l = at[0] - mapAt[0], t = at[1] - mapAt[1];
                out.add(new android.graphics.RectF(l - dp(4), t - dp(4), l + v.getWidth() + dp(4), t + v.getHeight() + dp(4)));
            }
            // ваши точки, точки группы и участники — их подписи главнее
            pointsOverlay.boxes(mv, out);
            groupPtsOverlay.boxes(mv, out);
            membersOverlay.boxes(mv, out);
        });
        map.getOverlays().add(placesOverlay);
        layerOverlay = new LayerOverlay(d);
        layerOverlay.onTap = this::layerFeatureInfo;
        map.getOverlays().add(layerOverlay);
        loadUserLayers();
        pathOverlay = new PathOverlay(d);
        map.getOverlays().add(pathOverlay);
        geoOverlay = new GeoOverlay(d);
        map.getOverlays().add(geoOverlay);
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
        sketch = new SketchOverlay(d);
        sketch.load(this);
        map.getOverlays().add(sketch);
        map.getOverlays().add(new CompassSync());

        userPoints = UserPoints.load(this);
        pointsOverlay.setPoints(userPoints);
        refreshGeoMine();
        refreshFireLines();
        pointsOverlay.setListener((hits, e, mv) -> tapPoints(hits, groupPtsOverlay.hits(e, mv)));
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
        groupPtsOverlay.setListener((hits, e, mv) -> tapPoints(new ArrayList<>(), hits));
        if (prefs.getBoolean("locOn", false) && hasLocPerm()) startLocation(false);
        maybeShowWhatsNew();
        if (prefs.getInt("lbsAlgo", 1) < LbsCore.ALGO && !offline() && LbsCore.mapFile(this).exists())
            map.postDelayed(this::startLbsUpdate, 4000); // линия считается по-новому — пересчитать сразу
        map.postDelayed(() -> checkUpdatesOnStart(), 2500);
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
        findViewById(R.id.btnNight).setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("night", false);
            setNight(on);
            if (on) Toast.makeText(this, "Ночной режим · удерживайте луну — яркость", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnNight).setOnLongClickListener(v -> {
            int lvl = (prefs.getInt("nightLvl", 1) + 1) % 3;
            prefs.edit().putInt("nightLvl", lvl).putBoolean("night", true).apply();
            applyNight();
            vibrate(20);
            Toast.makeText(this, "Ночью: " + NIGHT_LVL[lvl].toLowerCase(new Locale("ru")), Toast.LENGTH_SHORT).show();
            return true;
        });
        findViewById(R.id.btnTrack).setOnClickListener(v -> {
            if (track == null) setTracking(true);
            else new AlertDialog.Builder(this)
                    .setTitle("Остановить запись трека?")
                    .setMessage("Записано " + Geo.dist(Routes.length(track.pts)) + ". Трек сохранится в «Маршрутах».")
                    .setPositiveButton("Остановить", (d, w) -> setTracking(false))
                    .setNegativeButton("Продолжить", null)
                    .show();
        });
        findViewById(R.id.btnTrack).setOnLongClickListener(v -> {
            Toast.makeText(this, track == null ? "Запись трека по GPS: нажмите, чтобы начать" : "Идёт запись: " + Geo.dist(Routes.length(track.pts)), Toast.LENGTH_SHORT).show();
            return true;
        });
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
        findViewById(R.id.targetCard).setOnClickListener(v -> {
            // нажатие на цель — выбрать, что с ней сделать
            try {
                double tl = Double.parseDouble(prefs.getString("targetLat", "0")), tn = Double.parseDouble(prefs.getString("targetLon", "0"));
                flyTo(tl, tn, Math.max(14, map.getZoomLevelDouble()));
                placeActions(tl, tn);
            } catch (Exception ignored) {
            }
        });
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
                // серая зона: тёмная вуаль со штриховкой и пунктирной кромкой
                Polygon pg = new Polygon();
                pg.setPoints(pts(g));
                pg.getFillPaint().setColor(Color.WHITE);
                pg.getFillPaint().setShader(grayHatch());
                pg.getOutlinePaint().setColor(Color.argb(220, 200, 200, 200));
                pg.getOutlinePaint().setStrokeWidth(dp(1.2f));
                pg.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(4)}, 0));
                lbsFolder.add(pg);
            }
        }
        boolean showDiff = diff && prefs.getBoolean("showDiff", true);
        // прежнее положение — серый пунктир
        if (showDiff) for (double[][] l : data.removed) {
            Polyline pl = line(l, Color.argb(230, 190, 190, 190), 2);
            pl.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(7), dp(5)}, 0));
            lbsFolder.add(pl);
        }
        // ЛБС: мягкое свечение, тёмная обводка и яркая красная линия
        if (prefs.getBoolean("showLbs", true)) {
            for (double[][] l : data.lines) lbsFolder.add(line(l, Color.argb(60, 255, 40, 40), 11));
            for (double[][] l : data.lines) lbsFolder.add(line(l, Color.argb(200, 25, 0, 0), 5.6f));
            for (double[][] l : data.lines) lbsFolder.add(line(l, Color.rgb(255, 45, 45), 3f));
        }
        // изменения — янтарная линия с обводкой
        if (showDiff) {
            for (double[][] l : data.added) lbsFolder.add(line(l, Color.argb(200, 40, 25, 0), 6.5f));
            for (double[][] l : data.added) lbsFolder.add(line(l, Color.rgb(255, 196, 0), 4f));
        }
        map.invalidate();
    }

    private android.graphics.Shader hatch;

    /** Диагональная штриховка серой зоны (полупрозрачная, видна и на спутнике, и на схеме). */
    private android.graphics.Shader grayHatch() {
        if (hatch != null) return hatch;
        int sz = (int) dp(10);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        c.drawColor(Color.argb(95, 40, 40, 44));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(150, 210, 210, 215));
        p.setStrokeWidth(dp(1.3f));
        c.drawLine(-sz, sz * 2, sz * 2, -sz, p);
        c.drawLine(0, sz, sz, 0, p);
        c.drawLine(-sz / 2f, sz / 2f, sz / 2f, -sz / 2f, p);
        c.drawLine(sz / 2f, sz * 1.5f, sz * 1.5f, sz / 2f, p);
        hatch = new android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        return hatch;
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

    private static final int STYLE_SCHEME = 0, STYLE_SAT = 1, STYLE_TOPO = 2, STYLE_RELIEF = 3, STYLE_GOOGLE = 4;
    private static final String[] STYLE_NAMES = {"Схема", "Спутник (Esri)", "Топокарта (Esri)", "Рельеф (OpenTopoMap)", "Спутник (Google)"};
    private static final String[] STYLE_SHORT = {"Схема", "Esri Спутник", "Esri Топо", "Рельеф", "Google"};

    /** Google Спутник: самые подробные и часто обновляемые снимки. */
    private static final OnlineTileSourceBase GOOGLE = new OnlineTileSourceBase("GoogleSat", 0, 20, 256, ".jpg",
            new String[]{"https://mt0.google.com/", "https://mt1.google.com/", "https://mt2.google.com/", "https://mt3.google.com/"}) {
        @Override
        public String getTileURLString(long idx) {
            return getBaseUrl() + "vt/lyrs=s&x=" + MapTileIndex.getX(idx) + "&y=" + MapTileIndex.getY(idx) + "&z=" + MapTileIndex.getZoom(idx);
        }
    };

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
        if (style < 0 || style > 4) style = STYLE_SAT; // 5 — бывший «Свежий S2», убран
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
        boolean satLike = style == STYLE_SAT || style == STYLE_GOOGLE;
        map.setTilesScaledToDpi(!satLike);
        map.setMaxZoomLevel(style == STYLE_GOOGLE ? 20.5 : 19.5);
        if (style == STYLE_SCHEME) {
            map.setTileSource(TileSourceFactory.MAPNIK); // CARTO теперь требует ключ
            attr.setText("© OpenStreetMap");
        } else if (style == STYLE_TOPO) {
            map.setTileSource(TOPO);
            attr.setText("Esri, HERE, Garmin, USGS, © OpenStreetMap");
        } else if (style == STYLE_RELIEF) {
            map.setTileSource(RELIEF);
            attr.setText("© OpenTopoMap (CC-BY-SA), © OpenStreetMap, SRTM");
        } else if (style == STYLE_GOOGLE) {
            map.setTileSource(GOOGLE);
            if (!ru) {
                MapTileProviderBasic prov = new MapTileProviderBasic(getApplicationContext(), LABELS);
                esriLabels = new TilesOverlay(prov, getApplicationContext());
                esriLabels.setLoadingBackgroundColor(Color.TRANSPARENT);
                esriLabels.setLoadingLineColor(Color.TRANSPARENT);
                map.getOverlays().add(2, esriLabels);
            }
            attr.setText("Снимки © Google");
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
        sl.weight = 1; // и в обычном окне: длинное содержимое сжимается, кнопки внизу не обрезаются
        sc.setLayoutParams(sl);
    }

    private View chatHeader;

    private boolean sheetReplaced;

    private void sheet(String title) {
        runSheetHook();
        sheetReplaced = true;
        setSheetFull(false);
        if (chatHeader != null) {
            ((ViewGroup) chatHeader.getParent()).removeView(chatHeader);
            chatHeader = null;
        }
        removeChatBar();
        stopResCompass();
        hideEmoji();
        View host = findViewById(R.id.genSheet), panel = findViewById(R.id.genPanel), scrim = findViewById(R.id.genScrim);
        ((TextView) findViewById(R.id.genTitle)).setText(title);
        sheetContent = findViewById(R.id.genContent);
        sheetFooter = findViewById(R.id.genFooter);
        sheetContent.removeAllViews();
        sheetFooter.removeAllViews();
        sheetFooter.setVisibility(View.GONE);
        findViewById(R.id.genScroll).scrollTo(0, 0);
        // если окно как раз закрывается (closeSheet → sheet), отменяем закрытие, иначе новое окно исчезнет
        panel.animate().cancel();
        scrim.animate().cancel();
        if (host.getVisibility() == View.VISIBLE && (panel.getTranslationY() != 0 || scrim.getAlpha() < 1f)) {
            panel.animate().translationY(0).setDuration(160).start();
            scrim.animate().alpha(1f).setDuration(160).start();
        }
        if (host.getVisibility() != View.VISIBLE) {
            host.setVisibility(View.VISIBLE);
            panel.setTranslationY(dp(600));
            scrim.setAlpha(0f);
            panel.animate().translationY(0).setDuration(220).start();
            scrim.animate().alpha(1f).setDuration(220).start();
        }
    }

    private Runnable sheetCloseHook;

    /** Что сделать, когда текущая шторка закроется или сменится другой. */
    private void sheetOnClose(Runnable r) {
        sheetCloseHook = r;
    }

    private void runSheetHook() {
        Runnable r = sheetCloseHook;
        sheetCloseHook = null;
        if (r != null) r.run();
    }

    private void closeSheet() {
        View host = findViewById(R.id.genSheet), panel = findViewById(R.id.genPanel), scrim = findViewById(R.id.genScrim);
        runSheetHook();
        if (host.getVisibility() != View.VISIBLE) return;
        hideKeyboard();
        if (qrShown) {
            qrShown = false;
            applyNight();
        }
        stopRecording();
        stopPlaying();
        stopResCompass();
        chatOpen = false;
        chatList = null;
        chatStatus = null;
        chatPeer = "";
        chatReply = chatEdit = null;
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
        // ---------- подложка: крупные карточки с видом текущего места ----------
        hSection("ПОДЛОЖКА");
        final String[] names = {"Esri", "Google", "Рельеф", "Топо", "Схема"};
        final String[] subs = {"спутник", "спутник", "горизонтали", "топокарта", "улицы"};
        final int[] idx = {STYLE_SAT, STYLE_GOOGLE, STYLE_RELIEF, STYLE_TOPO, STYLE_SCHEME};
        final int[] icons = {R.drawable.ic_layers, R.drawable.ic_layers, R.drawable.ic_mountain, R.drawable.ic_lbs, R.drawable.ic_grid};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int selPos = 0;
        for (int i = 0; i < names.length; i++) {
            final int st = idx[i];
            boolean on = st == currentStyle;
            if (on) selPos = i;
            android.widget.FrameLayout card = new android.widget.FrameLayout(this);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageResource(icons[i]);
            img.setImageTintList(ColorStateList.valueOf(getColor(R.color.hud_text2)));
            int ip = (int) dp(26);
            img.setPadding(ip, ip, ip, ip);
            card.addView(img, new android.widget.FrameLayout.LayoutParams(-1, -1));
            // затемнение снизу и подпись поверх картинки
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC0E1014}));
            card.addView(shade, new android.widget.FrameLayout.LayoutParams(-1, (int) dp(46), android.view.Gravity.BOTTOM));
            LinearLayout cap = new LinearLayout(this);
            cap.setOrientation(LinearLayout.VERTICAL);
            cap.setPadding((int) dp(9), 0, (int) dp(6), (int) dp(7));
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(13.5f);
            t.setTextColor(Color.WHITE);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setShadowLayer(dp(2), 0, 0, Color.BLACK);
            cap.addView(t);
            TextView sb = new TextView(this);
            sb.setText(subs[i]);
            sb.setTextSize(10.5f);
            sb.setTextColor(0xD9FFFFFF);
            sb.setShadowLayer(dp(2), 0, 0, Color.BLACK);
            cap.addView(sb);
            card.addView(cap, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
            if (on) {
                ImageView ok = new ImageView(this);
                ok.setImageResource(R.drawable.ic_check);
                ok.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                ok.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(3));
                GradientDrawable og = new GradientDrawable();
                og.setShape(GradientDrawable.OVAL);
                og.setColor(getColor(R.color.hud_accent));
                ok.setBackground(og);
                android.widget.FrameLayout.LayoutParams ol = new android.widget.FrameLayout.LayoutParams((int) dp(20), (int) dp(20), android.view.Gravity.TOP | android.view.Gravity.END);
                ol.setMargins(0, (int) dp(6), (int) dp(6), 0);
                card.addView(ok, ol);
            }
            GradientDrawable cg = new GradientDrawable();
            cg.setCornerRadius(dp(14));
            cg.setColor(0xFF22262C);
            cg.setStroke((int) dp(on ? 2.5f : 1), on ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
            card.setBackground(cg);
            card.setClipToOutline(true);
            card.setPadding((int) dp(on ? 2.5f : 1), (int) dp(on ? 2.5f : 1), (int) dp(on ? 2.5f : 1), (int) dp(on ? 2.5f : 1));
            card.setContentDescription(names[i] + ", " + subs[i]);
            card.setOnClickListener(v -> {
                if (st == currentStyle) return;
                applyStyle(st);
                showLayers();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) dp(104), (int) dp(96));
            if (i < names.length - 1) lp.setMarginEnd((int) dp(8));
            strip.addView(card, lp);
            loadThumb(st, img);
        }
        hs.addView(strip);
        sheetContent.addView(hs);
        final int sp = selPos;
        hs.post(() -> hs.smoothScrollTo((int) Math.max(0, sp * dp(112) - dp(60)), 0));

        if (currentStyle == STYLE_SAT) {
            LinearLayout chips = new LinearLayout(this);
            chips.setPadding(0, (int) dp(10), 0, 0);
            TextView a = hChip(prefs.getInt("wbNum", 0) > 0 ? "Снимок: " + prefs.getString("wbLabel", "") : "Снимок: самый свежий",
                    R.drawable.ic_refresh, false, v -> showWayback());
            TextView b = hChip(compareOn() ? "Сравнение: выкл." : "Сравнить снимки", R.drawable.ic_compare, compareOn(),
                    v -> { if (compareOn()) { stopCompare(); closeSheet(); } else showWayback(true); });
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, (int) dp(40), 1);
            l1.setMarginEnd((int) dp(8));
            chips.addView(a, l1);
            chips.addView(b, new LinearLayout.LayoutParams(0, (int) dp(40), 1));
            sheetContent.addView(chips);
        }

        // ---------- ЛБС ----------
        LinearLayout lbs = layerBox();
        switchRow(lbs, R.drawable.ic_lbs, 0xFFFF2D2D, "ЛБС", "линия боевого соприкосновения", "showLbs", true, this::forceReloadLbs);
        layerDivider(lbs);
        switchRow(lbs, R.drawable.ic_refresh, 0xFFFFC400, "Изменения", "куда сдвинулась линия", "showDiff", true, this::forceReloadLbs);
        layerDivider(lbs);
        switchRow(lbs, R.drawable.ic_area, 0xFFBDBDBD, "Серая зона", "статус неизвестен", "showGray", true, this::forceReloadLbs);
        setBlock("ЛБС" + (lbsDate.isEmpty() ? "" : " · " + lbsDate), lbs);

        // ---------- поверх карты ----------
        int tx = getColor(R.color.hud_text);
        LinearLayout ov = layerBox();
        switchRow(ov, R.drawable.ic_grid, tx, "Сетка СК-42", "километровая сетка и подписи", "gridOn", false, this::applyGrid);
        layerDivider(ov);
        switchRow(ov, R.drawable.ic_label, tx, "Названия сёл", "русские подписи", "labelsRu", true, () -> applyStyle(currentStyle));
        layerDivider(ov);
        switchRow(ov, R.drawable.ic_my_location, 0xFF42A5F5, "Карта группы", "общие точки и маршруты", "showGroupMap", true, this::refreshGroupMap);
        setBlock("НА КАРТЕ", ov);

        roadsCard();
        nightCard();
        userLayersCard();

        LinearLayout more = layerBox();
        switchRow(more, R.drawable.ic_rail, tx, "Железные дороги", null, OV_KEYS[1], false, this::applyOverlays);
        layerDivider(more);
        switchRow(more, R.drawable.ic_mountain, tx, "Отмывка рельефа", "тени склонов", "hillshade", false, () -> { applyHillshade(); map.invalidate(); });
        layerDivider(more);
        switchRow(more, R.drawable.ic_border, tx, "Границы", "латиницей", OV_KEYS[3], false, this::applyOverlays);
        layerDivider(more);
        switchRow(more, R.drawable.ic_layers, tx, "Схема поверх снимка", "полупрозрачно", OV_KEYS[2], false, this::applyOverlays);
        layerDivider(more);
        switchRow(more, R.drawable.ic_nav, tx, "Кнопки областей", "быстрый переход по фронту", "showChips", false, () ->
                findViewById(R.id.chipsScroll).setVisibility(prefs.getBoolean("showChips", false) ? View.VISIBLE : View.GONE));
        setBlock("ЕЩЁ", more);
    }

    /** Строка-переключатель: значок, название, пояснение и выключатель справа. */
    private void switchRow(LinearLayout box, int icon, int tint, String title, String sub, String key, boolean def, Runnable apply) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(14), (int) dp(9), (int) dp(8), (int) dp(9));
        ImageView iv = hIcon(icon, tint, 20);
        row.addView(iv, new LinearLayout.LayoutParams((int) dp(22), (int) dp(22)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding((int) dp(14), 0, (int) dp(8), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(getColor(R.color.hud_text));
        tx.addView(t);
        if (sub != null) {
            TextView st = new TextView(this);
            st.setText(sub);
            st.setTextSize(11.5f);
            st.setTextColor(getColor(R.color.hud_text2));
            tx.addView(st);
        }
        row.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Color.WHITE, 0xFFB0B6BE}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{getColor(R.color.hud_accent), 0xFF3A404A}));
        sw.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            iv.setAlpha(on ? 1f : 0.5f);
            apply.run();
        });
        iv.setAlpha(sw.isChecked() ? 1f : 0.5f);
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        box.addView(row);
    }

    /** Карточка «Дороги»: переключатель и, если включено, насыщенность линий — всё в одном месте. */
    private void roadsCard() {
        final boolean on = prefs.getBoolean(OV_KEYS[0], false);
        LinearLayout box = layerBox();
        switchRow(box, R.drawable.ic_road, getColor(R.color.hud_text), "Дороги", "свои векторные, приглушённые", OV_KEYS[0], false, () -> {
            applyOverlays();
            final ScrollView sc = findViewById(R.id.genScroll);
            final int y = sc.getScrollY();
            showLayers();
            sc.post(() -> sc.scrollTo(0, y));
        });
        if (on) {
            LinearLayout seg = new LinearLayout(this);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(3));
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
            sl.setMargins((int) dp(50), 0, (int) dp(12), (int) dp(12));
            box.addView(seg, sl);
        }
        setBlock("ДОРОГИ", box);
    }

    /** Карточка «Ночь»: выключатель и яркость. */
    private void nightCard() {
        LinearLayout box = layerBox();
        switchRow(box, R.drawable.ic_moon, 0xFFFF5252, "Ночной режим", "весь экран красный и тёмный", "night", false, () -> {
            applyNight();
            final ScrollView sc = findViewById(R.id.genScroll);
            final int y = sc.getScrollY();
            showLayers();
            sc.post(() -> sc.scrollTo(0, y));
        });
        if (prefs.getBoolean("night", false)) {
            LinearLayout seg = new LinearLayout(this);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(3));
            GradientDrawable sg = new GradientDrawable();
            sg.setCornerRadius(dp(10));
            sg.setColor(0xFF15181D);
            seg.setBackground(sg);
            final List<TextView> bs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final int lv = i;
                TextView b = new TextView(this);
                b.setText(NIGHT_LVL[i]);
                b.setTextSize(13);
                b.setGravity(android.view.Gravity.CENTER);
                b.setPadding(0, (int) dp(7), 0, (int) dp(7));
                b.setOnClickListener(v -> {
                    prefs.edit().putInt("nightLvl", lv).apply();
                    applyNight();
                    for (int k = 0; k < bs.size(); k++) styleSeg(bs.get(k), k == lv);
                });
                bs.add(b);
                seg.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                styleSeg(b, i == prefs.getInt("nightLvl", 1));
            }
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sl.setMargins((int) dp(50), 0, (int) dp(12), (int) dp(12));
            box.addView(seg, sl);
        }
        setBlock("НОЧЬ", box);
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
                : style == STYLE_GOOGLE ? GOOGLE : TileSourceFactory.MAPNIK;
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
        // шапка: иконка, название, версия и состояние обновлений
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        hero.setPadding((int) dp(16), (int) dp(18), (int) dp(16), (int) dp(16));
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF262C35, 0xFF1A1E24});
        hg.setCornerRadius(dp(20));
        hg.setStroke((int) dp(1), getColor(R.color.hud_line));
        hero.setBackground(hg);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        hero.addView(logo, new LinearLayout.LayoutParams((int) dp(76), (int) dp(76)));
        TextView n = new TextView(this);
        n.setText("Рубеж");
        n.setTextSize(26);
        n.setTextColor(getColor(R.color.hud_text));
        n.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        n.setPadding(0, (int) dp(8), 0, 0);
        hero.addView(n);
        TextView tag = new TextView(this);
        tag.setText("Карта, СК-42, ЛБС и закрытый чат группы");
        tag.setTextSize(13);
        tag.setTextColor(getColor(R.color.hud_text2));
        hero.addView(tag);
        TextView pill = new TextView(this);
        String av = prefs.getString("updVer", "");
        boolean upd = updateAvailable();
        pill.setText(upd ? "v" + ver + "  →  доступна v" + av : "v" + ver + (av.isEmpty() ? "" : " · последняя"));
        pill.setTextSize(12.5f);
        pill.setTextColor(upd ? Color.WHITE : getColor(R.color.hud_text));
        GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(12));
        pg.setColor(upd ? getColor(R.color.hud_accent) : 0xFF2E343D);
        pill.setBackground(pg);
        pill.setPadding((int) dp(12), (int) dp(5), (int) dp(12), (int) dp(5));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = (int) dp(12);
        hero.addView(pill, pl);
        if (upd) pill.setOnClickListener(x -> downloadUpdate());
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hl.bottomMargin = (int) dp(10);
        sheetContent.addView(hero, hl);

        int tx = getColor(R.color.hud_text);
        toolTiles(
                toolTile(R.drawable.ic_refresh, upd ? "Обновить" : "Проверить", tx, () -> { if (updateAvailable()) downloadUpdate(); else checkUpdatesNow(); }),
                toolTile(R.drawable.ic_table, "История версий", tx, this::showVersions),
                toolTile(R.drawable.ic_send, "Канал Telegram", tx, this::openTgChannel));

        LinearLayout b1 = layerBox();
        layerLink(b1, R.drawable.ic_lock, "Шифрование чата", "на телефонах", x -> Toast.makeText(this,
                "Сообщения шифруются на телефонах участников (AES-256). Сервер хранит только зашифрованное.", Toast.LENGTH_LONG).show());
        layerDivider(b1);
        layerLink(b1, R.drawable.ic_download, "Работа без сети", "офлайн-карты", x -> { closeSheet(); chooseDownload(); });
        setBlock("О ПРОГРАММЕ", b1);

        hSection("ИСТОЧНИКИ ДАННЫХ");
        TextView src = new TextView(this);
        src.setText("Спутник: Esri World Imagery и архив Wayback.\nТопокарта: OpenTopoMap. Схема и дороги: OpenStreetMap, OpenFreeMap.\n"
                + "ЛБС: DeepStateMap. Населённые пункты: OpenStreetMap.\nВысоты: Terrain Tiles (SRTM), точность ±5–10 м.");
        src.setTextColor(getColor(R.color.hud_text2));
        src.setTextSize(12.5f);
        src.setLineSpacing(dp(2), 1f);
        src.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
        sheetContent.addView(src);
    }

    // ================= Информация о карте =================

    private void showInfo() {
        sheet("О карте");
        LinearLayout c1 = hCard();
        addKV(c1, "ЛБС", lbsDate.isEmpty() ? "не загружена" : "снимок " + lbsDate);
        addKV(c1, "Подложка", STYLE_NAMES[currentStyle]);
        final TextView sat = addKV(c1, "Спутник в центре", currentStyle == STYLE_SAT ? "загружаю…" : "включите подложку «Спутник»");
        GeoPoint c = (GeoPoint) map.getMapCenter();
        final TextView el = addKV(c1, "Высота центра", "…");
        sheetContent.addView(c1);
        Elevation.get(this, c.getLatitude(), c.getLongitude(), h -> el.setText(Elevation.fmt(h)));
        if (currentStyle == STYLE_SAT) queryImageryDate(t -> sat.setText(t.replace("Спутник (центр карты): ", "").replace("Спутник: ", "")));

        hSection("ЛЕГЕНДА");
        legendRow(0xFFFF2D2D, false, "ЛБС", "линия боевого соприкосновения");
        legendRow(0xFFFFC400, false, "Изменения", "новое положение линии");
        legendRow(0xFFBEBEBE, false, "Прежнее положение", "пунктир — до последнего снимка");
        legendRow(0x8C464646, true, "Серая зона", "штриховка — статус неизвестен");
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
                CoordParse.Result cr = q.isEmpty() ? null : CoordParse.parse(q);
                List<Integer> hit = places != null ? places.search(q, 1) : new ArrayList<>();
                if (cr != null) searchGo("Координаты", "СК-42: " + Sk42.format(cr.lat, cr.lon), cr.lat, cr.lon, 17.0);
                else if (!hit.isEmpty()) showPlace(hit.get(0));
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

    private static boolean inside(View v, MotionEvent e) {
        if (v == null || !v.isShown()) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        float x = e.getRawX(), y = e.getRawY();
        return x >= at[0] && x <= at[0] + v.getWidth() && y >= at[1] && y <= at[1] + v.getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // поиск открыт, а нажали на карту — закрыть поиск (карту при этом не двигаем)
            View pill = findViewById(R.id.searchPill);
            if (pill != null && pill.isShown() && !inside(pill, e) && !inside(resultsScroll, e)) {
                openSearch(false);
                return true;
            }
            // чат: нажатие мимо поля ввода закрывает клавиатуру, как в Telegram
            if (chatOpen) {
                View f = getCurrentFocus();
                if (f instanceof EditText && !inside(f, e) && !inside(findViewById(R.id.genFooter), e)) {
                    InputMethodManager imm = getSystemService(InputMethodManager.class);
                    if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
                    f.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(e);
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
            addRecentSearches();
            addHeader("НАПРАВЛЕНИЯ");
            for (int i = 0; i < NAMES.length; i++) {
                final int idx = i;
                addResultRow(NAMES[i], i == 0 ? "линия боевого соприкосновения целиком" : "область", v -> {
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
                    "СК-42: " + Sk42.format(clat, clon), v -> searchGo("Координаты",
                            "СК-42: " + Sk42.format(clat, clon), clat, clon, 17.0));
        }
        addMyPointResults(q);
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

    /** Поиск по вашим точкам и точкам группы (название и заметка). */
    private void addMyPointResults(String q) {
        String ql = q.toLowerCase(Locale.ROOT);
        boolean head = false;
        int n = 0;
        for (UserPoints.Point p : new ArrayList<>(userPoints)) {
            String hay = ((p.name == null ? "" : p.name) + " " + (p.note == null ? "" : p.note)).toLowerCase(Locale.ROOT);
            if (!hay.contains(ql) || n >= 15) continue;
            if (!head) { addHeader("МОИ ТОЧКИ"); head = true; }
            n++;
            addResultRow("📍  " + (p.name.isEmpty() ? "Точка" : p.name), "СК-42: " + Sk42.format(p.lat, p.lon), v -> {
                openSearch(false);
                flyTo(p.lat, p.lon, Math.max(15, map.getZoomLevelDouble()));
                showPointInfo(p);
            });
        }
        if (chat != null && chat.configured()) {
            head = false;
            for (ChatClient.GObj g : chat.objects()) {
                if (g.pts.isEmpty() || !g.name.toLowerCase(Locale.ROOT).contains(ql) || n >= 25) continue;
                if (!head) { addHeader("КАРТА ГРУППЫ"); head = true; }
                n++;
                final double la = g.pts.get(0)[0], lo = g.pts.get(0)[1];
                addResultRow((g.type.equals("pt") ? "📍  " : "〰  ") + g.name, "СК-42: " + Sk42.format(la, lo), v -> {
                    openSearch(false);
                    flyTo(la, lo, Math.max(15, map.getZoomLevelDouble()));
                    groupObjSheet(g);
                });
            }
        }
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
        searchGo(places.name[i], places.subtitle(i), places.lat[i], places.lon[i], places.rank[i] <= 1 ? 14.0 : 16.0);
    }

    /** Переход из поиска: запомнить в «Недавних». */
    private void searchGo(String name, String sub, double lat, double lon, double zoom) {
        try {
            JSONArray old = new JSONArray(prefs.getString("recentSearch", "[]")), out = new JSONArray();
            out.put(new JSONObject().put("n", name).put("s", sub == null ? "" : sub).put("la", lat).put("lo", lon).put("z", zoom));
            for (int k = 0; k < old.length() && out.length() < 8; k++) {
                JSONObject o = old.getJSONObject(k);
                if (o.optString("n").equals(name) && Math.abs(o.optDouble("la") - lat) < 1e-5) continue;
                out.put(o);
            }
            prefs.edit().putString("recentSearch", out.toString()).apply();
        } catch (Exception ignored) {
        }
        goTo(lat, lon, name, sub, zoom);
    }

    private void addRecentSearches() {
        JSONArray a;
        try {
            a = new JSONArray(prefs.getString("recentSearch", "[]"));
        } catch (Exception e) {
            return;
        }
        if (a.length() == 0) return;
        addHeader("НЕДАВНИЕ");
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            final double la = o.optDouble("la"), lo = o.optDouble("lo"), z = o.optDouble("z", 16);
            final String n = o.optString("n"), sub = n.equals("Координаты") ? "СК-42: " + Sk42.format(la, lo) : o.optString("s");
            addResultRow("🕓  " + n, sub, v -> searchGo(n, sub, la, lo, z));
        }
        addResultRow("Очистить историю поиска", "", v -> {
            prefs.edit().remove("recentSearch").apply();
            runLocalSearch();
        });
    }

    /** Перелететь к месту без лишней метки. */
    private void flyTo(double lat, double lon, double zoom) {
        if (follow) { follow = false; styleMyLoc(); }
        map.getController().animateTo(new GeoPoint(lat, lon), zoom, 700L);
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
                            searchGo(r[0], r[1], Double.parseDouble(r[2]), Double.parseDouble(r[3]), 16.0);
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
            ((TextView) findViewById(R.id.coordSk)).setText("X " + Sk42.group(Math.round(r[0])) + "   Y " + Sk42.group(Math.round(r[1])));
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
        placeActions(c.getLatitude(), c.getLongitude());
    }

    private boolean onMapLongPress(GeoPoint p) {
        if (tool != TOOL_NONE) {
            toolAdd(p.getLatitude(), p.getLongitude());
            return true;
        }
        placeActions(p.getLatitude(), p.getLongitude());
        return true;
    }

    /** Самые нужные значки — для быстрой метки одним нажатием. */
    private static final String[] QUICK_ICONS = {"eye", "pos", "relay", "ew", "drone", "mine", "target", "shelter", "store", "block", "!", "✚"};

    /** Нажатие на прицел или долгое нажатие на карту: что сделать с этим местом. */
    private void placeActions(double lat, double lon) {
        sheet("Это место");
        coordsCard(lat, lon);
        // быстрая метка: значок → точка сохраняется сразу, с названием по значку
        hSection("БЫСТРАЯ МЕТКА");
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, 0, 0, (int) dp(4));
        float den = getResources().getDisplayMetrics().density;
        int col = prefs.getInt("lastColor", UserPoints.COLORS[0]);
        for (String ic : QUICK_ICONS) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding((int) dp(4), (int) dp(6), (int) dp(4), (int) dp(6));
            cell.setBackgroundResource(R.drawable.bg_sheet_chip);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(UserPoints.pin(col, den * 1.25f, ic));
            cell.addView(iv, new LinearLayout.LayoutParams((int) dp(36), (int) dp(36)));
            TextView t = new TextView(this);
            String nm = iconName(ic);
            t.setText(nm.replace(" (НП)", ""));
            t.setTextSize(11);
            t.setMaxLines(1);
            t.setTextColor(getColor(R.color.hud_text));
            t.setPadding(0, (int) dp(3), 0, 0);
            cell.addView(t);
            cell.setOnClickListener(v -> {
                UserPoints.Point p = new UserPoints.Point();
                p.id = Long.toString(System.currentTimeMillis(), 36);
                p.created = System.currentTimeMillis();
                p.name = nm;
                p.note = "";
                p.color = col;
                p.icon = ic;
                p.lat = lat;
                p.lon = lon;
                userPoints.add(p);
                savePoints();
                vibrate(30);
                closeSheet();
                Toast.makeText(this, "Метка «" + nm + "» сохранена · нажмите на неё, чтобы изменить", Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) dp(78), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) dp(6));
            row.addView(cell, lp);
        }
        hs.addView(row);
        sheetContent.addView(hs);
        hSection("ДЕЙСТВИЯ");
        int tc = getColor(R.color.hud_text);
        toolTiles(
                toolTile(R.drawable.ic_add, "Точка…", getColor(R.color.hud_accent), () -> editPoint(null, lat, lon, null)),
                toolTile(R.drawable.ic_route, "Маршрут", tc, () -> { closeSheet(); startTool(TOOL_ROUTE, null); toolAdd(lat, lon); }),
                toolTile(R.drawable.ic_ruler, "Линейка", tc, () -> { closeSheet(); startTool(TOOL_RULER, null); toolAdd(lat, lon); }));
        toolTiles(
                toolTile(R.drawable.ic_flag, "Навести", tc, () -> { closeSheet(); setTarget(lat, lon, "Цель"); }),
                toolTile(R.drawable.ic_nav, "Вести сюда", tc, () -> { closeSheet(); startNav(Collections.singletonList(new double[]{lat, lon}), 0, "Цель"); }),
                toolTile(R.drawable.ic_area, "Зона", tc, () -> { closeSheet(); startTool(TOOL_AREA, null); toolAdd(lat, lon); }));
        toolTiles(
                toolTile(R.drawable.ic_eye, "Зона видимости", tc, () -> showViewshedSetup(lat, lon, "Это место")),
                toolTile(R.drawable.ic_flag, "Цель и поправки", tc, () -> { setFireSlot(2, lat, lon, ""); showFire(); }),
                toolTile(R.drawable.ic_rings, "Круги", tc, () -> editGeo(quickPoint(lat, lon, "Центр", "target"), 1)),
                toolTile(R.drawable.ic_sector, "Сектор", tc, () -> editGeo(quickPoint(lat, lon, "НП", "eye"), 0)));
        boolean grp = chat != null && chat.active();
        toolTiles(
                toolTile(R.drawable.ic_compass, "Ориентир", tc, () -> { if (resMarks.size() >= 6) resMarks.clear(); addResMark("Ориентир " + (resMarks.size() + 1), lat, lon); }),
                toolTile(R.drawable.ic_share, "Текстом", tc, () -> sharePointText("Точка", lat, lon)),
                grp ? toolTile(R.drawable.ic_chat, "В чат", tc, () -> { sendPoint("Точка", lat, lon); openChat(); })
                        : toolTile(R.drawable.ic_qr, "QR-код", tc, () -> {
                            UserPoints.Point q = new UserPoints.Point();
                            q.name = "Точка";
                            q.note = "";
                            q.lat = lat;
                            q.lon = lon;
                            showQr(QrCodec.encodePoint(q), "Точка");
                        }));
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
        refreshGeoMine();
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
        showPointInfo(p);
    }

    /** Нажатие на метки. Если под пальцем несколько (лежат друг на друге) — спросить, какую. */
    private void tapPoints(List<UserPoints.Point> mine, List<UserPoints.Point> grp) {
        if (tool != TOOL_NONE) {
            UserPoints.Point p = !mine.isEmpty() ? mine.get(0) : grp.get(0);
            toolAdd(p.lat, p.lon);
            return;
        }
        if (mine.size() + grp.size() == 1) {
            if (!mine.isEmpty()) onPointTap(mine.get(0));
            else openGroupPoint(grp.get(0));
            return;
        }
        sheet("Здесь несколько точек");
        float den = getResources().getDisplayMetrics().density;
        for (UserPoints.Point p : mine) {
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(p.color, den, p.icon));
            boolean extra = p == pointsOverlay.getExtra();
            hRow(pin, p.name.isEmpty() ? "Точка" : p.name, extra ? "найдено поиском" : "моя точка" + (p.note == null || p.note.isEmpty() ? "" : " · " + p.note),
                    hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18), v -> onPointTap(p));
        }
        for (UserPoints.Point p : grp) {
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(p.color, den, p.icon));
            hRow(pin, p.name.isEmpty() ? "Точка" : p.name, "карта группы",
                    hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18), v -> openGroupPoint(p));
        }
    }

    private void openGroupPoint(UserPoints.Point p) {
        ChatClient.GObj g = chat.object(p.id);
        if (g != null) groupObjSheet(g);
    }

    /** Карточка сохранённой точки: сведения и действия. Изменить — отдельной кнопкой. */
    private void showPointInfo(UserPoints.Point p) {
        String nm = p.name == null || p.name.isEmpty() ? "Точка" : p.name;
        sheet(nm);
        float den = getResources().getDisplayMetrics().density;
        // шапка: значок, заметка, расстояние и азимут от вас
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.setPadding((int) dp(14), (int) dp(12), (int) dp(14), (int) dp(12));
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(16));
        tg.setColor(0xFF1F232A);
        tg.setStroke((int) dp(1), getColor(R.color.hud_line));
        top.setBackground(tg);
        ImageView pin = new ImageView(this);
        pin.setImageBitmap(UserPoints.pin(p.color, den * 1.5f, p.icon));
        top.addView(pin, new LinearLayout.LayoutParams((int) dp(46), (int) dp(46)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding((int) dp(14), 0, 0, 0);
        android.location.Location me = myLoc == null ? null : myLoc.getLocation();
        TextView big = new TextView(this), small = new TextView(this);
        if (me != null) {
            double[] mi = Geo.inverse(me.getLatitude(), me.getLongitude(), p.lat, p.lon);
            big.setText(Geo.dist(mi[0]) + "  ·  " + String.format(Locale.US, "%.0f°", mi[1]));
            small.setText("от вас · азимут " + Geo.mils(Geo.gridBearing(me.getLatitude(), me.getLongitude(), p.lat, p.lon)) + " (дир. угол)");
        } else {
            big.setText(iconName(p.icon));
            small.setText("включите «Где я», чтобы видеть расстояние");
        }
        big.setTextSize(17);
        big.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        big.setTextColor(getColor(R.color.hud_text));
        small.setTextSize(12.5f);
        small.setTextColor(getColor(R.color.hud_text2));
        tx.addView(big);
        tx.addView(small);
        if (p.note != null && !p.note.isEmpty()) {
            TextView n = new TextView(this);
            n.setText(p.note);
            n.setTextSize(13.5f);
            n.setTextColor(getColor(R.color.hud_text));
            n.setPadding(0, (int) dp(6), 0, 0);
            tx.addView(n);
        }
        top.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.bottomMargin = (int) dp(8);
        sheetContent.addView(top, tl);
        coordsCard(p.lat, p.lon);
        if (voiceFile(p.id).exists())
            hChipRow(hChip("▶  Голосовая заметка", R.drawable.ic_mic, false, v -> playVoice(voiceFile(p.id), null)));
        // главное — двумя крупными кнопками
        actionRow(
                vAction(R.drawable.ic_nav, "Вести", true, v -> { closeSheet(); startNav(Collections.singletonList(new double[]{p.lat, p.lon}), 0, nm); }),
                vAction(R.drawable.ic_flag, "Навести", false, v -> { closeSheet(); setTarget(p.lat, p.lon, nm); }));
        int tc = getColor(R.color.hud_text);
        toolTiles(
                toolTile(R.drawable.ic_label, "Изменить", tc, () -> editPoint(p, p.lat, p.lon, null)),
                toolTile(R.drawable.ic_ruler, "Линейка", tc, () -> { closeSheet(); startTool(TOOL_RULER, null); toolAdd(p.lat, p.lon); }),
                toolTile(R.drawable.ic_share, "Текстом", tc, () -> sharePointText(nm, p.lat, p.lon)));
        boolean grp = chat != null && chat.active();
        toolTiles(
                toolTile(R.drawable.ic_qr, "QR-код", tc, () -> showQr(QrCodec.encodePoint(p), nm)),
                grp ? toolTile(R.drawable.ic_chat, "В чат", tc, () -> { sendPoint(nm, p.lat, p.lon); openChat(); })
                        : toolTile(R.drawable.ic_download, "Файл KML", tc, () -> exportOnePoint(p)),
                toolTile(R.drawable.ic_delete, "Удалить", 0xFFFF6B60, () -> new AlertDialog.Builder(this)
                        .setTitle("Удалить «" + nm + "»?")
                        .setPositiveButton("Удалить", (d2, w2) -> {
                            userPoints.remove(p);
                            voiceFile(p.id).delete();
                            savePoints();
                            closeSheet();
                        })
                        .setNegativeButton("Отмена", null)
                        .show()));
        hSection("ОБСТАНОВКА");
        boolean hasGeo = p.geo != null && !p.geo.isEmpty();
        toolTiles(
                toolTile(R.drawable.ic_sector, hasGeo ? "Сектор ✓" : "Сектор", tc, () -> editGeo(p, 0)),
                toolTile(R.drawable.ic_rings, "Круги", tc, () -> editGeo(p, 1)),
                toolTile(R.drawable.ic_eye, "Зона видимости", tc, () -> showViewshedSetup(p.lat, p.lon, nm)));
        if (grp) hChipRow(hChip("На общую карту группы", R.drawable.ic_my_location, false, v -> shareToGroupMap(objFromPoint(p))),
                hChip("Файл KML", R.drawable.ic_download, false, v -> exportOnePoint(p)));
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
        sk.setText("X " + Sk42.group(Math.round(r[0])) + "   Y " + Sk42.group(Math.round(r[1])));
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
        // сортировка
        final int mode = prefs.getInt("ptSort", 0);
        String[] names = {"Ближе к центру", "Ближе ко мне", "Новые", "По имени", "По значку", "По цвету"};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, (int) dp(6), 0, (int) dp(6));
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView ch = hChip(names[i], 0, i == mode, v -> { prefs.edit().putInt("ptSort", k).apply(); showPointsList(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(34));
            lp.setMarginEnd((int) dp(6));
            chips.addView(ch, lp);
        }
        hs.addView(chips);
        sheetContent.addView(hs);
        GeoPoint c = (GeoPoint) map.getMapCenter();
        android.location.Location me = myLoc == null ? null : myLoc.getLocation();
        final double cLat = mode == 1 && me != null ? me.getLatitude() : c.getLatitude(),
                cLon = mode == 1 && me != null ? me.getLongitude() : c.getLongitude();
        if (mode == 1 && me == null) Toast.makeText(this, "GPS ещё не определил место — считаю от центра карты", Toast.LENGTH_SHORT).show();
        final List<UserPoints.Point> sorted = new ArrayList<>(userPoints);
        final java.text.Collator coll = java.text.Collator.getInstance(new Locale("ru"));
        switch (mode) {
            case 2: Collections.sort(sorted, (a, b) -> Long.compare(b.created, a.created)); break;
            case 3: Collections.sort(sorted, (a, b) -> coll.compare(a.name == null ? "" : a.name, b.name == null ? "" : b.name)); break;
            case 4: Collections.sort(sorted, (a, b) -> {
                int x = coll.compare(iconName(a.icon), iconName(b.icon));
                return x != 0 ? x : coll.compare(a.name == null ? "" : a.name, b.name == null ? "" : b.name);
            }); break;
            case 5: Collections.sort(sorted, (a, b) -> {
                int ia = 0, ib = 0;
                for (int i = 0; i < UserPoints.COLORS.length; i++) { if (UserPoints.COLORS[i] == a.color) ia = i; if (UserPoints.COLORS[i] == b.color) ib = i; }
                return ia != ib ? Integer.compare(ia, ib) : coll.compare(a.name == null ? "" : a.name, b.name == null ? "" : b.name);
            }); break;
            default: Collections.sort(sorted, (a, b) -> Double.compare(
                    Geo.inverse(cLat, cLon, a.lat, a.lon)[0], Geo.inverse(cLat, cLon, b.lat, b.lon)[0]));
        }
        String lastGroup = null;
        float den = getResources().getDisplayMetrics().density;
        for (UserPoints.Point p : sorted) {
            if (mode == 4) {
                String gname = iconName(p.icon).toUpperCase(new Locale("ru"));
                if (!gname.equals(lastGroup)) { hSection(gname); lastGroup = gname; }
            }
            double[] inv = Geo.inverse(cLat, cLon, p.lat, p.lon);
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(p.color, den, p.icon));
            pin.setLayoutParams(new LinearLayout.LayoutParams((int) dp(26), (int) dp(35)));
            ImageView share = hIconButton(R.drawable.ic_share, getColor(R.color.hud_text2), v -> exportOnePoint(p));
            share.setContentDescription("Выгрузить эту точку в KML");
            String when = p.created > 0 ? new SimpleDateFormat("dd.MM", Locale.US).format(new Date(p.created)) + " · " : "";
            hRow(pin, p.name == null || p.name.isEmpty() ? "Точка" : p.name,
                    (mode == 2 ? when : "") + Geo.dist(inv[0]) + " · " + String.format(Locale.US, "%.0f°", inv[1]) + " · " + Sk42.format(p.lat, p.lon),
                    share, v -> {
                        map.getController().animateTo(new GeoPoint(p.lat, p.lon), Math.max(14.0, map.getZoomLevelDouble()), 700L);
                        showPointInfo(p);
                    });
        }
    }

    /** Выборочный экспорт: отметить точки (или маршруты) и выгрузить одним KML. */
    private void pickExport(boolean pts) {
        sheet(pts ? "Выбрать точки" : "Выбрать маршруты");
        final java.util.Set<Object> sel = new java.util.HashSet<>();
        final TextView[] cnt = new TextView[1];
        float den = getResources().getDisplayMetrics().density;
        List<?> items = pts ? new ArrayList<>(userPoints) : new ArrayList<>(routes);
        if (items.isEmpty()) { emptyState(pts ? R.drawable.ic_place : R.drawable.ic_route, pts ? "Точек нет" : "Маршрутов нет", ""); return; }
        for (Object o : items) {
            CheckBox cb = new CheckBox(this);
            cb.setButtonTintList(ColorStateList.valueOf(getColor(R.color.hud_accent)));
            cb.setClickable(false);
            View icon;
            String title, sub;
            if (pts) {
                UserPoints.Point p = (UserPoints.Point) o;
                ImageView pin = new ImageView(this);
                pin.setImageBitmap(UserPoints.pin(p.color, den, p.icon));
                pin.setLayoutParams(new LinearLayout.LayoutParams((int) dp(26), (int) dp(35)));
                icon = pin;
                title = p.name == null || p.name.isEmpty() ? "Точка" : p.name;
                sub = Sk42.format(p.lat, p.lon);
            } else {
                Routes.Route r = (Routes.Route) o;
                icon = routeSwatch(r.color);
                title = r.name;
                sub = r.area ? "Зона · " + Routes.areaText(Routes.area(r.pts)) : Geo.dist(Routes.length(r.pts));
            }
            hRow(icon, title, sub, cb, v -> {
                if (sel.contains(o)) sel.remove(o); else sel.add(o);
                cb.setChecked(sel.contains(o));
                cnt[0].setText("Выгрузить · " + sel.size());
            });
        }
        footer("Отмена", "Выгрузить", v -> {
            if (sel.isEmpty()) { Toast.makeText(this, "Ничего не отмечено", Toast.LENGTH_SHORT).show(); return; }
            String day = new SimpleDateFormat("dd_MM_yyyy", Locale.US).format(new Date());
            if (pts) {
                List<UserPoints.Point> l = new ArrayList<>();
                for (UserPoints.Point p : userPoints) if (sel.contains(p)) l.add(p);
                saveAndShare("Tochki_" + l.size() + "_" + day + ".kml", UserPoints.toKml(l), "Отправить точки");
            } else {
                List<Routes.Route> l = new ArrayList<>();
                for (Routes.Route r : routes) if (sel.contains(r)) l.add(r);
                saveAndShare("Marshruty_" + l.size() + "_" + day + ".kml", Routes.toKmlAll(l), "Отправить маршруты");
            }
            closeSheet();
        });
        cnt[0] = (TextView) sheetFooter.getChildAt(1);
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
        hRow(hIcon(R.drawable.ic_check, tx, 22), "Выбрать точки…", "Отметить нужные и выгрузить одним файлом", null, v -> pickExport(true));
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
        hRow(hIcon(R.drawable.ic_check, tx, 22), "Выбрать маршруты…", "Отметить нужные и выгрузить одним файлом", null, v -> pickExport(false));
        hSection("ЛБС");
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

    /** Одна точка — отдельным файлом KML. */
    private void exportOnePoint(UserPoints.Point p) {
        String nm = p.name == null || p.name.isEmpty() ? "Tochka" : p.name;
        saveAndShare(nm.replaceAll("[^\\p{L}\\p{N}]+", "_") + ".kml", UserPoints.toKml(Collections.singletonList(p)), "Отправить точку");
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

    private String pendingSign = "";

    private void startTool(int which, Routes.Route route) {
        tool = which;
        editingRoute = route;
        pendingSign = route != null && route.sign != null ? route.sign : "";
        toolPts.clear();
        if (route != null) {
            toolPts.addAll(route.pts);
            route.visible = false; // пока редактируем — рисуем как активную линию
        }
        pathOverlay.setActive(toolPts, which == TOOL_RULER ? getColor(R.color.hud_amber)
                : route != null ? route.color : which == TOOL_AREA ? Routes.COLORS[3] : Routes.COLORS[0], which == TOOL_AREA);
        pathOverlay.setActiveSign(pendingSign);
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
        pendingSign = "";
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
        String base = !pendingSign.isEmpty() ? "ЗНАК · " + signName(pendingSign).toUpperCase(new Locale("ru"))
                : tool == TOOL_RULER ? "ЛИНЕЙКА" : tool == TOOL_AREA ? "ЗОНА" : "МАРШРУТ";
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
        final String sign = pendingSign;
        boolean isSign = !sign.isEmpty();
        sheet(isSign ? (editingRoute != null ? "Изменить знак" : "Сохранить знак")
                : isArea ? (editingRoute != null ? "Изменить зону" : "Сохранить зону")
                : editingRoute != null ? "Изменить маршрут" : "Сохранить маршрут");
        final EditText name = hInput(isSign ? "Подпись знака" : isArea ? "Название зоны" : "Название маршрута",
                editingRoute != null ? editingRoute.name : isSign ? signName(sign)
                        : isArea ? "Зона " + (zones + 1) : "Маршрут " + (routes.size() - zones + 1));
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
        final int[] chosen = {editingRoute != null ? editingRoute.color : isSign ? prefs.getInt("signColor", SIGN_OWN)
                : isArea ? Routes.COLORS[3] : Routes.COLORS[0]};
        routeColorGrid(chosen, null);

        footer("Отмена", "Сохранить", v -> {
            Routes.Route r = editingRoute;
            if (r == null) {
                r = new Routes.Route();
                r.id = Long.toString(System.currentTimeMillis(), 36);
                r.created = System.currentTimeMillis();
                routes.add(r);
            }
            String nm = name.getText().toString().trim();
            r.name = nm.isEmpty() ? (isSign ? signName(sign) : isArea ? "Зона" : "Маршрут") : nm;
            r.color = chosen[0];
            r.area = isArea;
            r.sign = sign;
            r.pts.clear();
            r.pts.addAll(toolPts);
            r.visible = true;
            saveRoutes();
            Toast.makeText(this, isSign ? "Знак «" + r.name + "» сохранён · в группу: «Маршруты» → знак → «На карту группы»"
                    : (isArea ? "Зона «" + r.name + "» сохранена" : "Маршрут «" + r.name + "» сохранён"), Toast.LENGTH_SHORT).show();
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
        // шапка: цветная полоска и главные цифры
        List<Routes.Leg> legs = Routes.legs(r.pts);
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        if (r.area) {
            statCell(stats, Routes.areaText(Routes.area(r.pts)), "площадь", r.color);
            statCell(stats, Geo.dist(Routes.perimeter(r.pts)), "периметр", 0);
            statCell(stats, Integer.toString(r.pts.size()), "углов", 0);
        } else {
            statCell(stats, Geo.dist(Routes.length(r.pts)), "длина", r.color);
            statCell(stats, Integer.toString(legs.size()), legs.size() == 1 ? "участок" : "участков", 0);
            statCell(stats, legs.isEmpty() ? "—" : Geo.mils(legs.get(0).grid), "дир. угол 1-го", 0);
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.bottomMargin = (int) dp(10);
        sheetContent.addView(stats, sl);
        if (!r.area)
            actionRow(
                    vAction(R.drawable.ic_nav, "Вести", true, v -> { closeSheet(); navRoute(r, false); }),
                    vAction(R.drawable.ic_undo, "Обратно", false, v -> { closeSheet(); navRoute(r, true); }),
                    vAction(R.drawable.ic_eye, "Показать", false, v -> { closeSheet(); fitRoute(r); }));
        else actionRow(vAction(R.drawable.ic_eye, "Показать на карте", true, v -> { closeSheet(); fitRoute(r); }));
        int tc = getColor(R.color.hud_text);
        toolTiles(
                toolTile(r.area ? R.drawable.ic_area : R.drawable.ic_route, "Изменить", tc, () -> { closeSheet(); startTool(r.area ? TOOL_AREA : TOOL_ROUTE, r); }),
                toolTile(R.drawable.ic_table, "Таблица", tc, () -> {
            closeSheet();
            List<double[]> keep = new ArrayList<>(toolPts);
            toolPts.clear();
            toolPts.addAll(r.pts);
            toolTable();
            toolPts.clear();
            toolPts.addAll(keep);
        }),
                r.area ? toolTile(R.drawable.ic_qr, "QR-код", tc, () -> showQr(QrCodec.encodeRoute(r), r.name))
                        : toolTile(R.drawable.ic_mountain, "Профиль", tc, () -> showProfile(r.pts, r.name)));
        boolean grp = chat != null && chat.active();
        toolTiles(
                r.area ? toolTile(R.drawable.ic_share, "Файл KML", tc, () -> exportOneRoute(r))
                        : toolTile(R.drawable.ic_qr, "QR-код", tc, () -> showQr(QrCodec.encodeRoute(r), r.name)),
                grp ? toolTile(R.drawable.ic_my_location, "В группу", tc, () -> { closeSheet(); shareToGroupMap(objFromRoute(r)); })
                        : toolTile(R.drawable.ic_label, "Название", tc, () -> renameRoute(r)),
                toolTile(R.drawable.ic_delete, "Удалить", 0xFFFF6B60, () -> new AlertDialog.Builder(this)
                        .setTitle("Удалить «" + r.name + "»?")
                        .setPositiveButton("Удалить", (d2, w2) -> {
                            routes.remove(r);
                            saveRoutes();
                            showRoutes();
                        })
                        .setNegativeButton("Отмена", null)
                        .show()));
        if (!r.area || grp) hChipRow(
                r.area ? hChip("Название", R.drawable.ic_label, false, v -> renameRoute(r)) : hChip("Файл KML", R.drawable.ic_share, false, v -> exportOneRoute(r)),
                grp ? hChip("Название", R.drawable.ic_label, false, v -> renameRoute(r)) : null);
        hSection("ЦВЕТ");
        final int[] chosen = {r.color};
        routeColorGrid(chosen, () -> {
            r.color = chosen[0];
            r.visible = true;
            saveRoutes();
            map.invalidate();
        });
    }

    private void statCell(LinearLayout row, String big, String small, int accent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) dp(12), (int) dp(10), (int) dp(8), (int) dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0xFF1F232A);
        g.setStroke((int) dp(accent != 0 ? 1.5f : 1), accent != 0 ? accent : getColor(R.color.hud_line));
        c.setBackground(g);
        TextView b = new TextView(this);
        b.setText(big);
        b.setTextSize(16);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(getColor(R.color.hud_text));
        b.setSingleLine(true);
        c.addView(b);
        TextView s2 = new TextView(this);
        s2.setText(small);
        s2.setTextSize(11.5f);
        s2.setTextColor(getColor(R.color.hud_text2));
        s2.setSingleLine(true);
        c.addView(s2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (row.getChildCount() > 0) lp.setMarginStart((int) dp(8));
        row.addView(c, lp);
    }

    private void exportOneRoute(Routes.Route r) {
        saveAndShare(r.name.replaceAll("[^A-Za-z0-9А-Яа-яЁё_-]", "_") + ".kml", Routes.toKml(r), "Отправить маршрут");
    }

    private void renameRoute(Routes.Route r) {
        final EditText in = new EditText(this);
        in.setText(r.name);
        in.setSingleLine(true);
        in.setSelection(in.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Название")
                .setView(in)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (!n.isEmpty()) { r.name = n; saveRoutes(); }
                    routeActions(r);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Сетка цветов линии: по 6 в ряд. onPick — после выбора (может быть null). */
    private void routeColorGrid(int[] chosen, Runnable onPick) {
        final List<View> sw = new ArrayList<>();
        LinearLayout rowV = null;
        for (int i = 0; i < Routes.COLORS.length; i++) {
            if (i % 6 == 0) {
                rowV = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = (int) dp(8);
                sheetContent.addView(rowV, rl);
            }
            final int col = Routes.COLORS[i];
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(34), 1);
            lp.setMarginEnd((int) dp(8));
            v.setContentDescription(Routes.COLOR_NAMES[i]);
            v.setOnClickListener(x -> {
                chosen[0] = col;
                for (int k = 0; k < sw.size(); k++) sw.get(k).setBackground(swatchHud(Routes.COLORS[k], Routes.COLORS[k] == col));
                if (onPick != null) onPick.run();
            });
            v.setBackground(swatchHud(col, col == chosen[0]));
            sw.add(v);
            rowV.addView(v, lp);
        }
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
        findViewById(R.id.tileUpdate).setOnClickListener(v -> { openMenu(false); showVersions(); });
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
        swNight.setOnCheckedChangeListener((b, on) -> { if (on != prefs.getBoolean("night", false)) setNight(on); });
        swTrack.setOnCheckedChangeListener((b, on) -> setTracking(on));
        findViewById(R.id.tileOffline).setOnClickListener(v -> { openMenu(false); chooseDownload(); });
        findViewById(R.id.tileInfo).setOnClickListener(v -> { openMenu(false); showGuide(-1); });
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
                else if (cmpStage == 1) lbsHandler.postDelayed(MapActivity.this::cmpListStep, 1500);
            }
        });
    }

    private void startLbsUpdate() {
        if (lbsRunning || lbsScript == null) return;
        if (cmpStage != 0) {
            Toast.makeText(this, "Идёт сравнение дат ЛБС — подождите", Toast.LENGTH_LONG).show();
            return;
        }
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
        return currentStyle == STYLE_SAT || currentStyle == STYLE_GOOGLE ? 20000 : currentStyle == STYLE_TOPO ? 16000 : 9000;
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
        actionRow(vAction(R.drawable.ic_download, "Выбрать участок рамкой", true, v -> { closeSheet(); startAreaPick(cm); }));
        TextView note = new TextView(this);
        note.setText("Поставьте рамку на нужный участок: двигайте и приближайте карту под рамкой, тяните углы. "
                + "Выберите подробность — размер посчитается сразу. Скачанное открывается мгновенно и работает без интернета.");
        note.setTextColor(getColor(R.color.hud_text2));
        note.setTextSize(12.5f);
        note.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(note);
        hChipRow(hChip("Очистить сохранённые карты", R.drawable.ic_delete, false, v -> { closeSheet(); confirmClear(); }));
    }

    // ---------- выбор участка рамкой ----------

    private AreaFrameView areaFrame;
    private View areaPanel;
    private Runnable areaTick;

    private BoundingBox frameBox() {
        Projection pj = map.getProjection();
        android.graphics.RectF f = areaFrame.frame;
        double n = -90, s2 = 90, e = -180, w = 180;
        float[][] k = {{f.left, f.top}, {f.right, f.top}, {f.right, f.bottom}, {f.left, f.bottom}};
        for (float[] c : k) {
            org.osmdroid.api.IGeoPoint g = pj.fromPixels((int) c[0], (int) c[1]);
            n = Math.max(n, g.getLatitude()); s2 = Math.min(s2, g.getLatitude());
            e = Math.max(e, g.getLongitude()); w = Math.min(w, g.getLongitude());
        }
        return new BoundingBox(n, e, s2, w);
    }

    private void startAreaPick(CacheManager cm) {
        FrameLayout root = findViewById(R.id.mapRoot);
        findViewById(R.id.dock).setVisibility(View.GONE);
        findViewById(R.id.rightCol).setVisibility(View.GONE);
        findViewById(R.id.centerMark).setVisibility(View.GONE);
        areaFrame = new AreaFrameView(this);
        root.addView(areaFrame, 1, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundResource(R.drawable.bg_sheet);
        p.setElevation(dp(12));
        p.setClickable(true);
        p.setPadding((int) dp(18), (int) dp(16), (int) dp(18), (int) dp(18) + navBarInset());
        TextView title = new TextView(this);
        title.setText("Участок для офлайна");
        title.setTextSize(18);
        title.setTextColor(getColor(R.color.hud_text));
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        p.addView(title);
        final TextView info = new TextView(this);
        info.setTextSize(13.5f);
        info.setTextColor(getColor(R.color.hud_text2));
        info.setPadding(0, (int) dp(4), 0, (int) dp(10));
        p.addView(info);
        final int maxZ = (int) map.getTileProvider().getTileSource().getMaximumZoomLevel();
        final int[] zs = {14, 15, 16, 17, 18};
        final String[] zn = {"обзор", "сёла", "посадки", "улицы", "дома"};
        final int[] zSel = {Math.min(prefs.getInt("dlZ", 16), maxZ)};
        LinearLayout chips = new LinearLayout(this);
        final List<TextView> cl = new ArrayList<>();
        final Runnable[] update = new Runnable[1];
        for (int i = 0; i < zs.length; i++) {
            final int z = Math.min(zs[i], maxZ);
            if (i > 0 && z == Math.min(zs[i - 1], maxZ)) continue;
            TextView c = hChip(zn[i], 0, z == zSel[0], null);
            c.setTag(z);
            c.setOnClickListener(v -> {
                zSel[0] = z;
                prefs.edit().putInt("dlZ", z).apply();
                for (TextView t : cl) {
                    boolean on = (int) t.getTag() == z;
                    t.setBackgroundResource(on ? R.drawable.bg_accent_btn : R.drawable.bg_sheet_chip);
                    t.setTextColor(on ? Color.WHITE : getColor(R.color.hud_text));
                }
                update[0].run();
            });
            cl.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(38), 1);
            lp.setMarginEnd((int) dp(6));
            chips.addView(c, lp);
        }
        p.addView(chips);
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, (int) dp(12), 0, 0);
        TextView cancel = hChip("Отмена", 0, false, v -> stopAreaPick());
        final TextView go = hChip("Скачать", R.drawable.ic_download, true, null);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, (int) dp(48), 1);
        lc.setMarginEnd((int) dp(10));
        btns.addView(cancel, lc);
        btns.addView(go, new LinearLayout.LayoutParams(0, (int) dp(48), 1.4f));
        p.addView(btns);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
        root.addView(p, pl);
        areaPanel = p;

        final int[] cnt = {0};
        final String[] lastKey = {""};
        update[0] = () -> {
            if (areaFrame == null) return;
            BoundingBox bb = frameBox();
            String key = bb.toString() + zSel[0];
            if (key.equals(lastKey[0])) return;
            lastKey[0] = key;
            int n = cm.possibleTilesInArea(bb, 8, zSel[0]);
            cnt[0] = n;
            double w = Geo.inverse(bb.getCenterLatitude(), bb.getLonWest(), bb.getCenterLatitude(), bb.getLonEast())[0];
            double h = Geo.inverse(bb.getLatSouth(), bb.getCenterLongitude(), bb.getLatNorth(), bb.getCenterLongitude())[0];
            boolean big = n > MAX_TILES;
            info.setText(String.format(Locale.US, "%s × %s · %s · ", Geo.dist(w), Geo.dist(h), STYLE_NAMES[currentStyle])
                    + (big ? "слишком много — уменьшите рамку или подробность" : "≈ " + mb((double) n * tileBytes())));
            info.setTextColor(big ? 0xFFFF8A80 : getColor(R.color.hud_text2));
            go.setAlpha(big ? 0.45f : 1f);
        };
        go.setOnClickListener(v -> {
            if (cnt[0] > MAX_TILES) {
                Toast.makeText(this, "Слишком большой участок — уменьшите рамку или выберите подробность ниже", Toast.LENGTH_LONG).show();
                return;
            }
            BoundingBox bb = frameBox();
            int z = zSel[0];
            stopAreaPick();
            startDownload(cm, bb, 8, z, cnt[0]);
        });
        areaFrame.onChange = () -> update[0].run();
        p.post(() -> {
            int[] at = new int[2];
            findViewById(R.id.topBar).getLocationInWindow(at);
            int[] me = new int[2];
            areaFrame.getLocationInWindow(me);
            areaFrame.setLimits(at[1] - me[1] + findViewById(R.id.topBar).getHeight(), p.getTop());
            areaFrame.requestLayout();
            areaFrame.invalidate();
            update[0].run();
        });
        areaTick = new Runnable() {
            @Override
            public void run() {
                if (areaFrame == null) return;
                update[0].run();
                map.postDelayed(this, 400);
            }
        };
        map.postDelayed(areaTick, 400);
    }

    private int navBarInset() {
        android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
        return wi == null ? 0 : wi.getSystemWindowInsetBottom();
    }

    private void stopAreaPick() {
        FrameLayout root = findViewById(R.id.mapRoot);
        if (areaFrame != null) root.removeView(areaFrame);
        if (areaPanel != null) root.removeView(areaPanel);
        areaFrame = null;
        areaPanel = null;
        if (areaTick != null) map.removeCallbacks(areaTick);
        findViewById(R.id.dock).setVisibility(View.VISIBLE);
        findViewById(R.id.rightCol).setVisibility(View.VISIBLE);
        findViewById(R.id.centerMark).setVisibility(View.VISIBLE);
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
                        + "). ЛБС, точки и маршруты останутся.")
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
        if (sketchPanel != null) {
            stopSketch();
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

    private static final int REQ_LOC = 21, REQ_IMPORT = 31, REQ_RESTORE = 32, REQ_LAYER = 38;
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

    private void styleTrackBtn(boolean on) {
        ImageView b = findViewById(R.id.btnTrack);
        b.setImageTintList(ColorStateList.valueOf(on ? Color.WHITE : getColor(R.color.hud_text)));
        b.setBackgroundTintList(on ? ColorStateList.valueOf(0xFFE53935) : null);
        b.setContentDescription(on ? "Остановить запись трека" : "Начать запись трека");
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
            styleTrackBtn(true);
            Toast.makeText(this, "Запись трека началась. Остановить — та же красная кнопка", Toast.LENGTH_LONG).show();
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
            styleTrackBtn(false);
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
                    + "СК-42: X " + Sk42.group(Math.round(r[0])) + "   Y " + Sk42.group(Math.round(r[1])) + " (зона " + (int) r[2] + ")\n"
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

    private static final float[] NIGHT_BRIGHT = {0.02f, 0.08f, 0.22f};
    private static final String[] NIGHT_LVL = {"Темно", "Средне", "Ярче"};

    /**
     * Ночной режим: весь экран (карта, кнопки, панели) — в красных тонах, яркость снижена.
     * Красный свет меньше всего сбивает ночное зрение и хуже виден со стороны.
     */
    private void applyNight() {
        boolean on = prefs.getBoolean("night", false);
        boolean apply = on && !qrShown; // QR-код показываем как есть, иначе не считается
        findViewById(R.id.nightFilter).setVisibility(View.GONE);
        map.getOverlayManager().getTilesOverlay().setColorFilter(null);
        View root = getWindow().getDecorView();
        if (apply) {
            android.graphics.Paint lp = new android.graphics.Paint();
            lp.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                    0.36f, 0.62f, 0.14f, 0, 0,
                    0.02f, 0.04f, 0.01f, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 1, 0}));
            root.setLayerType(View.LAYER_TYPE_HARDWARE, lp);
        } else {
            root.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        android.view.WindowManager.LayoutParams wl = getWindow().getAttributes();
        int lvl = Math.max(0, Math.min(2, prefs.getInt("nightLvl", 1)));
        wl.screenBrightness = qrShown ? 1f : on ? NIGHT_BRIGHT[lvl] : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(wl);
        ImageView b = findViewById(R.id.btnNight);
        if (b != null) {
            b.setImageTintList(ColorStateList.valueOf(on ? Color.WHITE : getColor(R.color.hud_text)));
            b.setBackgroundTintList(on ? ColorStateList.valueOf(0xFFB71C1C) : null);
            b.setContentDescription(on ? "Выключить ночной режим" : "Ночной режим");
        }
        Switch sw = findViewById(R.id.swNight);
        if (sw != null && sw.isChecked() != on) sw.setChecked(on);
        map.invalidate();
    }

    private void setNight(boolean on) {
        prefs.edit().putBoolean("night", on).apply();
        applyNight();
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
                prefs.edit().putLong("updCheckedAt", System.currentTimeMillis()).putString("updVer", ver)
                        .putString("updNotes", notesOf(o.optString("body", ""))).apply();
                runOnUiThread(() -> {
                    showUpdateRow();
                    if (updateAvailable() && !ver.equals(prefs.getString("updShownVer", ""))) {
                        prefs.edit().putString("updShownVer", ver).apply();
                        if (!sheetOpen() && !menuOpen()) downloadUpdate();
                    }
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** При каждом запуске: через пару секунд проверить и, если есть новая версия, показать окно. */
    private void checkUpdatesOnStart() {
        if (offline()) return;
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(httpGet("https://api.github.com/repos/TRO9II/lbs-kml/releases/tags/latest"));
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("v(\\d+(?:\\.\\d+)+)")
                        .matcher(o.optString("name", "") + " " + o.optString("body", ""));
                String ver = m.find() ? m.group(1) : "";
                prefs.edit().putLong("updCheckedAt", System.currentTimeMillis()).putString("updVer", ver)
                        .putString("updNotes", notesOf(o.optString("body", ""))).apply();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    showUpdateRow();
                    if (!updateAvailable()) return;
                    boolean busy = sheetOpen() || menuOpen() || findViewById(R.id.lockView).getVisibility() == View.VISIBLE;
                    if (!busy) downloadUpdate();
                    else Toast.makeText(this, "Вышла новая версия v" + ver + " — меню → «Обновления»", Toast.LENGTH_LONG).show();
                });
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
                prefs.edit().putLong("updCheckedAt", System.currentTimeMillis()).putString("updVer", ver)
                        .putString("updNotes", notesOf(o.optString("body", ""))).apply();
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
        sheet(updateAvailable() ? "Вышла версия v" + prefs.getString("updVer", "") : "Обновление");
        String mine = "";
        try {
            mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        LinearLayout card = hCard();
        addKV(card, "Установлена", "v" + mine);
        addKV(card, "Доступна", prefs.getString("updVer", "").isEmpty() ? "проверяю…" : "v" + prefs.getString("updVer", ""));
        sheetContent.addView(card);
        String notes = prefs.getString("updNotes", "");
        if (!notes.isEmpty() && updateAvailable()) {
            hSection("ЧТО НОВОГО");
            changeList(notes);
        }
        final TextView st = new TextView(this);
        st.setTextColor(getColor(R.color.hud_text2));
        st.setTextSize(13);
        st.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        String err = prefs.getString("updError", "");
        st.setText(err.isEmpty() ? "Новая версия скачается и встанет поверх — точки, маршруты и группа сохранятся." : err);
        sheetContent.addView(st);
        footer("Позже", "Обновить", v -> {
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
        if (req == REQ_GAVATAR) {
            if (res == RESULT_OK && data != null && data.getData() != null) onGroupAvatarPicked(data.getData());
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
        if (req == REQ_LAYER) importLayer(uri);
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
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        if ("rubezh2".equals(i.getData().getScheme())) {
            final String inv = i.getData().toString();
            map.postDelayed(() -> joinInvite(inv), 400); // ссылка-приглашение из мессенджера
            return;
        }
        importFrom(i.getData());
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
        qrShown = true;
        applyNight();
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
                toolTile(R.drawable.ic_eye_los, "Видно ли", tx, () -> {
                    startTool(TOOL_RULER, null);
                    Toast.makeText(this, "Поставьте 2 точки (откуда и куда смотреть), затем нажмите ⛰ «Профиль»", Toast.LENGTH_LONG).show();
                }));
        toolTiles(
                toolTile(R.drawable.ic_eye, "Зона видимости", tx, () -> {
                    GeoPoint c = (GeoPoint) map.getMapCenter();
                    showViewshedSetup(c.getLatitude(), c.getLongitude(), "Центр экрана");
                }),
                toolTile(R.drawable.ic_rings, "Круги дальности", tx, () -> {
                    GeoPoint c = (GeoPoint) map.getMapCenter();
                    editGeo(quickPoint(c.getLatitude(), c.getLongitude(), "Центр", "target"), 1);
                }),
                toolTile(R.drawable.ic_sector, "Сектор", tx, () -> {
                    GeoPoint c = (GeoPoint) map.getMapCenter();
                    editGeo(quickPoint(c.getLatitude(), c.getLongitude(), "НП", "eye"), 0);
                }));
        hSection("ОБСТАНОВКА");
        toolTiles(
                toolTile(R.drawable.ic_sign_attack, "Тактические знаки", tx, this::showSigns),
                toolTile(R.drawable.ic_sketch, "Кроки", tx, this::startSketch),
                toolTile(R.drawable.ic_lbs, "ЛБС: сравнить даты", tx, this::showLbsCompare));
        toolTiles(
                toolTile(R.drawable.ic_flag, "Цель и поправки", tx, this::showFire),
                toolTile(R.drawable.ic_compass, "Где я (засечка)", tx, this::showResection),
                toolTile(R.drawable.ic_download, "Печать района", tx, this::showPrint));
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
                toolTile(R.drawable.ic_info, "Инструкция", tx, () -> showGuide(-1)),
                toolTile(R.drawable.ic_qr, "Принять по QR", tx, this::scanQr));
        toolTiles(
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
            sheetReplaced = false;
            action.run();
            if (!sheetReplaced) closeSheet(); // инструмент на карте — убрать окно; новое окно — оставить
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
        findViewById(R.id.btnChat).setOnClickListener(v -> { chatPeer = ""; openChat(); });
        chat.setListener(new ChatClient.Listener() {
            @Override
            public void onMessages(List<ChatClient.Msg> added) {
                int fromOthers = 0;
                ChatClient.Msg other = null;
                for (ChatClient.Msg m : added) {
                    if (!m.mine) fromOthers++;
                    if (!m.mine && m.kind.equals("sos")) showSos(m);
                    boolean here = chatOpen && chatList != null && inConv(m);
                    if (!m.mine && m.dm() && !here) dmUnread(m.peer(chat.deviceId()), 1);
                    if (!m.mine && chatOpen && !here) other = m;
                }
                if (chatOpen) chat.markRead();
                if (chatOpen && chatList != null) {
                    for (ChatClient.Msg m : added) if (inConv(m)) addBubble(m);
                    renderQueue();
                    scrollChatDown();
                    if (other != null) Toast.makeText(MapActivity.this, (other.from.isEmpty() ? "Сообщение" : other.from)
                            + (other.dm() ? " (лично): " : " (в группе): ") + snippet(other), Toast.LENGTH_SHORT).show();
                    if (chatDmIcon != null) updateDmIcon();
                } else if (fromOthers > 0) {
                    chatUnread += fromOthers;
                    prefs.edit().putInt("chatUnread", chatUnread).apply();
                    showChatBadge();
                    vibrate(120);
                    ChatClient.Msg last = added.get(added.size() - 1);
                    Toast.makeText(MapActivity.this, (last.from.isEmpty() ? "Сообщение" : last.from) + (last.dm() ? " (лично)" : "") + ": "
                            + snippet(last), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onChanged() {
                if (chatOpen && chatList != null) rebuildChatList();
            }

            @Override
            public void onOutbox() {
                if (chatOpen && chatList != null) renderQueue();
            }

            @Override
            public void onSendFailed(String why) {
                Toast.makeText(MapActivity.this, "Сервер не принял сообщение: " + why, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onGroupSwitched() {
                membersOverlay.setMembers(chat.members());
                refreshGroupMap();
                map.invalidate();
                chatUnread = 0;
                prefs.edit().putInt("chatUnread", 0).apply();
                chatPending = 0;
                showChatBadge();
                restartGroupService();
                if (chatOpen) { chatPeer = ""; openChat(); }
            }

            @Override
            public void onMembers() {
                membersOverlay.setMembers(chat.members());
                map.invalidate();
            }

            @Override
            public void onObjects() {
                refreshGroupMap();
                // оформление или закреп могли поменяться — обновить шапку открытого чата
                if (chatOpen && chatList != null && chatHeader != null) {
                    ((ViewGroup) chatHeader.getParent()).removeView(chatHeader);
                    chatHeader = null;
                    buildChatHeader();
                    sheet_title_refresh();
                }
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
        bubbleRows.clear();
        chatReply = chatEdit = null;
        sheet(chatPeer.isEmpty() ? chat.groupName() : "Лично · " + peerName(chatPeer));
        setSheetFull(true);
        if (!chatPeer.isEmpty()) dmUnread(chatPeer, -1);
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
        List<ChatClient.Msg> hist = convHistory();
        if (hist.isEmpty() && !chatPeer.isEmpty()) {
            emptyHint(chatList, "Личная переписка с " + peerName(chatPeer),
                    "Её видите только вы двое: шифруется ключом, который есть лишь у вас, — ни сервер, ни остальные участники, ни создатель группы её не прочтут.");
        } else if (hist.isEmpty()) {
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
        if (chatJump > 0) for (int i = 0; i < hist.size(); i++) if (hist.get(i).seq == chatJump) { from = Math.min(from, Math.max(0, i - 20)); break; }
        for (int i = from; i < hist.size(); i++) addBubble(hist.get(i));
        chatQueueBox = new LinearLayout(this);
        chatQueueBox.setOrientation(LinearLayout.VERTICAL);
        sheetContent.addView(chatQueueBox);
        renderQueue();
        chatScroll = findViewById(R.id.genScroll);
        if (chatJump > 0) {
            final long j = chatJump;
            chatJump = 0;
            chatScroll.postDelayed(() -> jumpTo(j), 150);
        } else scrollChatDown();

        // строка ввода внизу
        sheetFooter.removeAllViews();
        sheetFooter.setVisibility(View.VISIBLE);
        sheetFooter.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // «+» — вложения, статусы и SOS; поле ввода на всю ширину; справа микрофон, а при наборе текста — «отправить»
        ImageView plus = hIconButton(R.drawable.ic_add, getColor(R.color.hud_text), v -> chatAttach());
        plus.setContentDescription("Вложения, статус, SOS");
        sheetFooter.addView(plus, new LinearLayout.LayoutParams((int) dp(42), (int) dp(42)));
        final EditText input = hInput(chatPeer.isEmpty() ? "Сообщение" : "Лично для " + peerName(chatPeer), "");
        chatInput = input;
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
            input.setText("");
            if (chatEdit != null) {
                ChatClient.Msg t = chatEdit;
                removeChatBar();
                if (!txt.equals(t.text)) chat.edit(t, txt, err -> Toast.makeText(this, "Не изменено: " + err, Toast.LENGTH_LONG).show());
                return;
            }
            ChatClient.Msg m = new ChatClient.Msg();
            m.text = txt;
            if (chatReply != null) {
                m.re = chatReply.seq;
                m.rq = (chatReply.mine ? chat.callsign() : chatReply.from) + "\n" + snippet(chatReply);
                removeChatBar();
            }
            sendChat(m);
        });
    }

    private void sendChat(ChatClient.Msg m) {
        if (m.to.isEmpty() && !chatPeer.isEmpty() && !m.kind.equals("sos") && !m.kind.equals("st")) m.to = chatPeer;
        chat.send(m, null, err -> Toast.makeText(this, "Не отправлено: " + err, Toast.LENGTH_LONG).show());
    }

    private void scrollChatDown() {
        if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Неподвижная строка под заголовком чата: связь и участники слева, значки справа. */
    private void sheet_title_refresh() {
        if (chatPeer.isEmpty()) ((TextView) findViewById(R.id.genTitle)).setText(chat.groupName());
    }

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
        if (!chatPeer.isEmpty()) {
            ImageView back = hIconButton(R.drawable.ic_back, getColor(R.color.hud_text), v -> { chatPeer = ""; openChat(); });
            back.setContentDescription("В общий чат группы");
            head.addView(back, new LinearLayout.LayoutParams((int) dp(36), (int) dp(40)));
        }
        if (chatPeer.isEmpty()) {
            View av = groupAvatarView(chat.groupInfo(), chat.groupName(), 30);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams((int) dp(30), (int) dp(30));
            al.setMarginEnd((int) dp(8));
            av.setOnClickListener(v -> { if (chat.isAdmin()) showGroupLook(); else showGroups(); });
            head.addView(av, al);
        }
        head.addView(chatStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (chatPeer.isEmpty()) head.addView(headIcon(R.drawable.ic_layers, 0, getColor(R.color.hud_text), "Мои группы", v -> showGroups()));
        head.addView(headIcon(R.drawable.ic_search, 0, getColor(R.color.hud_text), "Поиск по чату", v -> chatSearch("")));
        if (chatPeer.isEmpty()) {
            chatDmIcon = headIcon(R.drawable.ic_lock, dmUnreadTotal(), getColor(R.color.hud_text), "Личные сообщения", v -> showDialogs());
            head.addView(chatDmIcon);
        } else chatDmIcon = null;
        chatStatus.setOnClickListener(v -> {
            if (chat.isAdmin()) Toast.makeText(this, chat.lastStatus() + (chat.lastError().isEmpty() ? "" : "\n" + chat.lastError()), Toast.LENGTH_LONG).show();
        });
        if (chatPeer.isEmpty()) head.addView(headIcon(R.drawable.ic_my_location, chat.members().size(), sharing() ? 0xFF66BB6A : getColor(R.color.hud_text), "Люди на карте",
                v -> { chatOpen = false; sheetFooter.setVisibility(View.GONE); showMembers(); }));
        if (chatPeer.isEmpty()) head.addView(headIcon(R.drawable.ic_place, chat.objects().size(), getColor(R.color.hud_text), "Карта группы",
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
        JSONObject pin = chatPeer.isEmpty() ? chat.pinned() : null;
        if (pin != null) box.addView(pinnedBar(pin));
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
        addBubble(m, chatList);
    }

    private void addBubble(ChatClient.Msg m, LinearLayout chatList) {
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
            if (ChatClient.INFO_ID.equals(m.oid)) sys.setText(who + " обновил(а) оформление группы или закреп  " + time);
            else sys.setText(who + (m.op.equals("del") ? " убрал(а) с карты группы «" : " отметил(а) на карте группы «") + nm + "»  " + time);
            sys.setTextSize(12);
            sys.setTextColor(0xFF90CAF9);
            sys.setGravity(android.view.Gravity.CENTER);
            sys.setPadding((int) dp(16), (int) dp(6), (int) dp(16), (int) dp(6));
            if (!m.op.equals("del") && !ChatClient.INFO_ID.equals(m.oid)) sys.setOnClickListener(v -> {
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
        SwipeRow row = new SwipeRow(this);
        if (!m.queued) row.onSwipe = () -> startReply(m);
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
        if (m.re > 0 && !m.rq.isEmpty()) b.addView(quoteView(m));
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
            photoV.setOnLongClickListener(v -> { msgMenu(b, m); return true; });
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
            t.setOnLongClickListener(v -> { msgMenu(b, m); return true; });
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
        View rxRow = reactionsView(m);
        if (rxRow != null) b.addView(rxRow);
        TextView tm = new TextView(this);
        tm.setText(readText(m));
        tm.setTextSize(10.5f);
        tm.setTextColor(0x99FFFFFF);
        tm.setGravity(android.view.Gravity.END);
        if (m.mine && !m.queued) {
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
        if (m.queued) {
            b.setAlpha(0.75f);
            b.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Ждёт связи")
                    .setMessage("Сообщение уйдёт само, как только появится связь с сервером.")
                    .setPositiveButton("Отменить отправку", (d, w) -> chat.cancelQueued(m))
                    .setNegativeButton("Пусть ждёт", null)
                    .show());
        } else {
            b.setOnLongClickListener(v -> { msgMenu(b, m); return true; });
            bubbleRows.put(m.seq, row);
        }
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
                LinearLayout quick = new LinearLayout(this);
                quick.setOrientation(LinearLayout.HORIZONTAL);
                ImageView show = hIconButton(R.drawable.ic_eye, getColor(R.color.hud_text), v -> {
                    closeSheet();
                    if (g.pts.size() == 1) flyTo(g.pts.get(0)[0], g.pts.get(0)[1], Math.max(15, map.getZoomLevelDouble()));
                    else {
                        double n = -90, so = 90, e = -180, w = 180;
                        for (double[] q : g.pts) { n = Math.max(n, q[0]); so = Math.min(so, q[0]); e = Math.max(e, q[1]); w = Math.min(w, q[1]); }
                        double pa = Math.max(0.004, (n - so) * 0.15), po = Math.max(0.004, (e - w) * 0.15);
                        map.zoomToBoundingBox(new BoundingBox(n + pa, e + po, so - pa, w - po), true, (int) dp(40));
                    }
                });
                show.setContentDescription("Показать на карте");
                quick.addView(show, new LinearLayout.LayoutParams((int) dp(38), (int) dp(38)));
                if (g.type.equals("pt")) {
                    ImageView aim = hIconButton(R.drawable.ic_flag, getColor(R.color.hud_text), v -> {
                        closeSheet();
                        setTarget(g.pts.get(0)[0], g.pts.get(0)[1], g.name.isEmpty() ? "Точка группы" : g.name);
                    });
                    aim.setContentDescription("Навести");
                    quick.addView(aim, new LinearLayout.LayoutParams((int) dp(38), (int) dp(38)));
                }
                hRow(lead, g.name.isEmpty() ? "без названия" : g.name, sub, quick, v -> groupObjSheet(g));
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
        boolean more = chat.configured();
        sheet(more ? "Ещё одна группа" : "Чат группы");
        if (more) {
            TextView t = new TextView(this);
            t.setText("Текущая группа «" + chat.groupName() + "» останется на телефоне — переключаться в «Мои группы» (значок в шапке чата).");
            t.setTextColor(getColor(R.color.hud_text2));
            t.setTextSize(12.5f);
            t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(8));
            sheetContent.addView(t);
        } else emptyState(R.drawable.ic_chat, "Вы пока не в группе",
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
            final String link = ChatClient.inviteLink(inv);
            if (link != null) hChipRow(hChip("Отправить ссылку", R.drawable.ic_share, true, x -> shareInvite(link, (int) hours[0])));
            hChipRow(hChip("Скопировать текстом", R.drawable.ic_save, false, x -> copy(inv, "Приглашение скопировано")),
                    hChip("Участники", R.drawable.ic_my_location, false, x -> showPeople()));
        }, err -> Toast.makeText(this, "Не удалось: " + err, Toast.LENGTH_LONG).show()));
    }

    /** Ссылка-приглашение в мессенджер: откроет Рубеж, а если его нет — предложит скачать. */
    private void shareInvite(String link, int hours) {
        String text = "Приглашение в группу «" + chat.groupName() + "» в приложении Рубеж:\n" + link
                + "\n\nНет приложения — на странице по ссылке есть кнопка «Скачать». Ссылка действует "
                + (hours >= 168 ? "7 дней" : hours >= 24 ? "сутки" : "час") + ".";
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(i, "Отправить приглашение"));
        } catch (Exception e) {
            copy(text, "Ссылка скопирована");
        }
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
                boolean other = !p.id.equals(chat.deviceId());
                hRow(personDot(p), p.callsign.isEmpty() ? "без позывного" : p.callsign, sub,
                        other ? hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18) : null,
                        other ? v -> personActions(p) : null);
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
        hRow(hIcon(R.drawable.ic_lock, getColor(R.color.hud_accent), 22), "Написать лично", "Увидите только вы двое", null, v -> openDm(p.id));
        if (!chat.isAdmin() || p.role.equals("owner")) {
            hChipRow(hChip("Назад к участникам", R.drawable.ic_back, false, v -> showPeople()));
            return;
        }
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
        if (chat.isAdmin()) {
            layerDivider(g1);
            layerLink(g1, R.drawable.ic_smile, "Оформление", "аватар, описание, цвет", v -> showGroupLook());
        }
        layerDivider(g1);
        layerLink(g1, R.drawable.ic_chat, "Позывной", chat.callsign(), v -> askCallsign(this::openChat));
        layerDivider(g1);
        layerLink(g1, R.drawable.ic_lock, "Соединение", chat.secure() ? "HTTPS" : "обычное",
                v -> Toast.makeText(this, chat.secure()
                        ? "Канал до сервера зашифрован, сертификат сервера запомнен — подменить сервер незаметно нельзя."
                        : "Сообщения и так зашифрованы на телефоне. Когда сервер обновится (сам, в течение часа), канал до него тоже станет защищённым.", Toast.LENGTH_LONG).show());
        setBlock("ГРУППА · вы " + roleName, g1);

        LinearLayout g0 = layerBox();
        layerLink(g0, R.drawable.ic_layers, "Мои группы", Integer.toString(chat.groups().size()), v -> showGroups());
        setBlock("ГРУППЫ НА ТЕЛЕФОНЕ", g0);

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
                    String left = chat.groupName();
                    closeSheet();
                    chat.leave(null);
                    membersOverlay.setMembers(chat.members());
                    refreshGroupMap();
                    Toast.makeText(this, "Вы вышли из группы «" + left + "»" + (chat.configured() ? " · открыта «" + chat.groupName() + "»" : ""),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Отмена", null)
                .show());
        foot.addView(leave);
        sheetContent.addView(foot);
        if (chat.role().equals("owner"))
            hChipRow(hChip("Удалить группу у всех", R.drawable.ic_delete, false, v -> confirmDestroyGroup()));
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
        List<GeoOverlay.Item> geoItems = new ArrayList<>();
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
                    p.geo = g.extra;
                    pts.add(p);
                    GeoOverlay.Item gi = GeoOverlay.parse(g.extra, p.lat, p.lon, p.color);
                    if (gi != null) geoItems.add(gi);
                } else {
                    Routes.Route r = new Routes.Route();
                    r.id = "g:" + g.id;
                    r.name = g.name;
                    r.color = g.color == 0 ? 0xFF42A5F5 : g.color;
                    r.area = g.type.equals("area");
                    if (!g.extra.isEmpty()) try {
                        r.sign = new JSONObject(g.extra).optString("sign", "");
                    } catch (Exception ignored) {
                    }
                    r.pts.addAll(g.pts);
                    groupRoutes.add(r);
                }
            }
        }
        groupPtsOverlay.setPoints(pts);
        if (geoOverlay != null) geoOverlay.setGroup(geoItems);
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
        g.extra = p.geo == null ? "" : p.geo;
        g.pts.add(new double[]{p.lat, p.lon});
        return g;
    }


    // ================= Секторы, круги дальности, зона видимости =================

    private void refreshGeoMine() {
        if (geoOverlay == null) return;
        List<GeoOverlay.Item> items = new ArrayList<>();
        for (UserPoints.Point p : userPoints) {
            GeoOverlay.Item it = GeoOverlay.parse(p.geo, p.lat, p.lon, p.color);
            if (it != null) items.add(it);
        }
        geoOverlay.setMine(items);
    }

    /** Новая точка без лишних вопросов (для кругов и сектора «с места»). Сохраняется только по «Сохранить». */
    private UserPoints.Point quickPoint(double lat, double lon, String name, String icon) {
        UserPoints.Point p = new UserPoints.Point();
        p.name = name;
        p.note = "";
        p.color = prefs.getInt("lastColor", UserPoints.COLORS[0]);
        p.icon = icon;
        p.lat = lat;
        p.lon = lon;
        return p; // id пустой — признак «ещё не сохранена»
    }

    private EditText numInput(String hint, String text) {
        EditText e = hInput(hint, text);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private LinearLayout labeled(String cap, View field) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(cap);
        t.setTextSize(11.5f);
        t.setTextColor(getColor(R.color.hud_text2));
        t.setPadding((int) dp(4), 0, 0, (int) dp(3));
        col.addView(t);
        col.addView(field);
        return col;
    }

    private void row3(View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < vs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i < vs.length - 1) lp.setMarginEnd((int) dp(8));
            r.addView(vs[i], lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = (int) dp(4);
        sheetContent.addView(r, rl);
    }

    /** Сектор обзора/огня и круги дальности у точки. focus: 0 — сектор, 1 — круги. */
    private void editGeo(UserPoints.Point p, int focus) {
        final boolean fresh = p.id == null || p.id.isEmpty();
        sheet(fresh ? "Сектор и круги" : "Сектор и круги · " + p.name);
        JSONObject o;
        try {
            o = p.geo == null || p.geo.isEmpty() ? new JSONObject() : new JSONObject(p.geo);
        } catch (Exception e) {
            o = new JSONObject();
        }
        final EditText name = fresh ? hInput("Название точки", p.name) : null;
        if (name != null) {
            name.setSingleLine(true);
            sheetContent.addView(name);
        }
        // ---------- сектор ----------
        hSection("СЕКТОР ОБЗОРА / ОГНЯ");
        final EditText az = numInput("45 или 7-50", o.has("sa") ? fmtNum(o.optDouble("sa")) : "");
        final EditText w = numInput("60", o.has("sw") ? fmtNum(o.optDouble("sw")) : "60");
        final EditText r = numInput("1500", o.has("sr") ? fmtNum(o.optDouble("sr")) : "");
        row3(labeled("Азимут, ° / д.у.", az), labeled("Ширина, °", w), labeled("Дальность, м", r));
        // азимут по центру экрана: точка → центр карты
        LinearLayout quick = hChipRow(
                hChip("Азимут на центр экрана", R.drawable.ic_compass, false, v -> {
                    GeoPoint c = (GeoPoint) map.getMapCenter();
                    double[] inv = Geo.inverse(p.lat, p.lon, c.getLatitude(), c.getLongitude());
                    if (inv[0] < 5) {
                        Toast.makeText(this, "Сдвиньте карту: центр экрана — направление сектора", Toast.LENGTH_LONG).show();
                        return;
                    }
                    az.setText(fmtNum(Math.round(inv[1])));
                    if (r.getText().toString().trim().isEmpty()) r.setText(fmtNum(Math.round(inv[0] / 10) * 10));
                }));
        LinearLayout widths = new LinearLayout(this);
        for (int wv : new int[]{30, 60, 90, 120, 180, 360}) {
            TextView c = hChip(wv == 360 ? "круг" : wv + "°", 0, false, v -> w.setText(Integer.toString(wv)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(36), 1);
            lp.setMarginEnd((int) dp(5));
            widths.addView(c, lp);
        }
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wl.topMargin = (int) dp(6);
        sheetContent.addView(widths, wl);
        // ---------- круги ----------
        hSection("КРУГИ ДАЛЬНОСТИ, КМ");
        final EditText rg = numInput("1, 3, 5", o.optString("rg", focus == 1 ? "1, 3, 5" : ""));
        sheetContent.addView(rg);
        LinearLayout presets = new LinearLayout(this);
        String[][] pr = {{"0,5 · 1 · 2", "0.5, 1, 2"}, {"1 · 3 · 5", "1, 3, 5"}, {"5 · 10 · 20", "5, 10, 20"}, {"нет", ""}};
        for (String[] x : pr) {
            TextView c = hChip(x[0], 0, false, v -> rg.setText(x[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(36), 1);
            lp.setMarginEnd((int) dp(5));
            presets.addView(c, lp);
        }
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = (int) dp(6);
        sheetContent.addView(presets, pl);
        TextView hint = new TextView(this);
        hint.setText("Азимут — истинный, в градусах или делениях угломера (7-50). Пустая дальность — сектора нет. "
                + "Если точка уже на общей карте группы — отправьте её туда ещё раз, и группа увидит сектор и круги.");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.hud_text2));
        hint.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(hint);
        (focus == 1 ? rg : az).requestFocus();

        footer("Отмена", "Сохранить", v -> {
            JSONObject g = new JSONObject();
            try {
                double a = Resection.parseAz(az.getText().toString());
                double rr = parseNum(r.getText().toString());
                double ww = parseNum(w.getText().toString());
                if (!az.getText().toString().trim().isEmpty() && Double.isNaN(a)) {
                    Toast.makeText(this, "Азимут не разобрать: 0–360° или 0-00…60-00", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!Double.isNaN(a) && !Double.isNaN(rr) && rr > 0) {
                    if (rr > 100000) rr = 100000;
                    g.put("sa", Math.round(a * 10) / 10.0);
                    g.put("sw", Double.isNaN(ww) || ww <= 0 ? 60 : Math.min(360, ww));
                    g.put("sr", Math.round(rr));
                }
                double[] rings = GeoOverlay.ringsKm(rg.getText().toString());
                if (rings.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (double x : rings) sb.append(sb.length() == 0 ? "" : ", ").append(fmtNum(x / 1000));
                    g.put("rg", sb.toString());
                }
            } catch (Exception ignored) {
            }
            if (g.length() == 0 && fresh) {
                Toast.makeText(this, "Укажите сектор (азимут и дальность) или круги", Toast.LENGTH_LONG).show();
                return;
            }
            p.geo = g.length() == 0 ? "" : g.toString();
            if (fresh) {
                String nm = name.getText().toString().trim();
                p.name = nm.isEmpty() ? "Точка" : nm;
                p.id = Long.toString(System.currentTimeMillis(), 36);
                p.created = System.currentTimeMillis();
                userPoints.add(p);
            }
            savePoints();
            closeSheet();
            Toast.makeText(this, g.length() == 0 ? "Сектор и круги убраны" : "Сохранено · «" + p.name + "»", Toast.LENGTH_SHORT).show();
        });
    }

    private static String fmtNum(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "").replace('.', ',');
    }

    private static double parseNum(String s) {
        try {
            s = s.trim().replace(',', '.').replace(" ", "");
            return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private volatile int vsRun;

    /** Зона прямой видимости: откуда смотрим, на какой высоте, на какое расстояние. */
    private void showViewshedSetup(double lat, double lon, String title) {
        sheet("Зона видимости");
        TextView about = new TextView(this);
        about.setText("Подсветит, куда видно с высоты над точкой «" + title + "» — по рельефу местности. "
                + "Лес, посадки и постройки не учитываются: в реальности видно меньше.");
        about.setTextSize(13);
        about.setTextColor(getColor(R.color.hud_text2));
        sheetContent.addView(about);
        final double[] hv = {prefs.getFloat("vsH", 30)}, rv = {prefs.getFloat("vsR", 5000)}, tv = {prefs.getFloat("vsT", 2)};
        hSection("ВЫСОТА ПОДЪЁМА НАД ЗЕМЛЁЙ");
        final EditText hIn = numInput("30", fmtNum(hv[0]));
        sheetContent.addView(hIn);
        LinearLayout hp = new LinearLayout(this);
        String[][] hs = {{"2 м", "2", "глаза"}, {"10", "10", "мачта"}, {"30", "30", "ретранс."}, {"100", "100", "дрон"}, {"300", "300", "дрон"}};
        for (String[] x : hs) {
            TextView c = hChip(x[0], 0, false, v -> hIn.setText(x[1]));
            c.setContentDescription(x[0] + " " + x[2]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(36), 1);
            lp.setMarginEnd((int) dp(5));
            hp.addView(c, lp);
        }
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hl.topMargin = (int) dp(6);
        sheetContent.addView(hp, hl);

        hSection("РАДИУС");
        LinearLayout rr = new LinearLayout(this);
        final List<TextView> rbs = new ArrayList<>();
        final int[] radii = {2000, 5000, 10000, 15000};
        for (int rad : radii) {
            TextView b = new TextView(this);
            b.setText(rad / 1000 + " км");
            b.setTextSize(13.5f);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(0, (int) dp(8), 0, (int) dp(8));
            b.setOnClickListener(v -> {
                rv[0] = rad;
                for (int k = 0; k < rbs.size(); k++) styleSegHud(rbs.get(k), radii[k] == rad);
            });
            rbs.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd((int) dp(5));
            rr.addView(b, lp);
            styleSegHud(b, rad == (int) rv[0]);
        }
        sheetContent.addView(rr);

        hSection("ЧТО ИЩЕМ");
        LinearLayout tr = new LinearLayout(this);
        final List<TextView> tbs = new ArrayList<>();
        final double[] tg = {0, 2, 4};
        final String[] tl = {"Землю", "Человека", "Технику"};
        for (int i = 0; i < tg.length; i++) {
            final double tvv = tg[i];
            TextView b = new TextView(this);
            b.setText(tl[i]);
            b.setTextSize(13.5f);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(0, (int) dp(8), 0, (int) dp(8));
            b.setOnClickListener(v -> {
                tv[0] = tvv;
                for (int k = 0; k < tbs.size(); k++) styleSegHud(tbs.get(k), tg[k] == tvv);
            });
            tbs.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd((int) dp(5));
            tr.addView(b, lp);
            styleSegHud(b, tvv == tv[0]);
        }
        sheetContent.addView(tr);

        final TextView status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(getColor(R.color.hud_text));
        status.setPadding((int) dp(4), (int) dp(12), (int) dp(4), 0);
        sheetContent.addView(status);

        footer("Закрыть", "Рассчитать", v -> {
            double h = parseNum(hIn.getText().toString());
            if (Double.isNaN(h) || h < 0 || h > 3000) {
                Toast.makeText(this, "Высота — от 0 до 3000 м", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putFloat("vsH", (float) h).putFloat("vsR", (float) rv[0]).putFloat("vsT", (float) tv[0]).apply();
            v.setEnabled(false);
            final int run = ++vsRun;
            final double rad = rv[0], tgt = tv[0];
            status.setText("Загружаю рельеф…");
            new Thread(() -> {
                ViewshedCalc.prefetch(this, lat, lon, rad);
                runOnUiThread(() -> { if (run == vsRun) status.setText("Считаю… 0%"); });
                GeoOverlay.Viewshed vs = ViewshedCalc.run(this, lat, lon, h, rad, tgt, pct -> {
                    runOnUiThread(() -> { if (run == vsRun) status.setText("Считаю… " + pct + "%"); });
                    return run == vsRun;
                });
                runOnUiThread(() -> {
                    if (run != vsRun) return;
                    v.setEnabled(true);
                    if (vs == null) {
                        status.setText("Нет данных о рельефе для этого места. Нужен интернет (один раз) — потом работает без сети.");
                        return;
                    }
                    vs.title = title;
                    geoOverlay.setViewshed(vs);
                    showVsChip();
                    closeSheet();
                    zoomToBox(vs);
                    map.invalidate();
                });
            }).start();
        });
    }

    private void zoomToBox(GeoOverlay.Viewshed vs) {
        try {
            map.zoomToBoundingBox(new org.osmdroid.util.BoundingBox(vs.north, vs.east, vs.south, vs.west), true, (int) dp(40));
        } catch (Exception ignored) {
        }
    }

    private void showVsChip() {
        TextView c = findViewById(R.id.vsChip);
        GeoOverlay.Viewshed vs = geoOverlay.viewshed();
        if (vs == null) {
            c.setVisibility(View.GONE);
            return;
        }
        c.setText("👁 Видно " + vs.visiblePct + "% · " + fmtNum(vs.height) + " м · " + fmtNum(vs.radius / 1000) + " км   ✕");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0xE61F232A);
        g.setStroke((int) dp(1), 0xFF00C853);
        c.setBackground(g);
        c.setVisibility(View.VISIBLE);
        c.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Зона видимости")
                .setMessage("Зелёным — куда видно с " + fmtNum(vs.height) + " м над «" + vs.title + "», тёмным — не видно (за рельефом). "
                        + "Видно " + vs.visiblePct + "% площади в радиусе " + fmtNum(vs.radius / 1000) + " км.\n\n"
                        + "Лес и постройки не учтены. Точность рельефа ±10 м.")
                .setPositiveButton("Убрать с карты", (d2, w2) -> {
                    vsRun++;
                    geoOverlay.setViewshed(null);
                    showVsChip();
                    map.invalidate();
                })
                .setNeutralButton("Показать", (d2, w2) -> zoomToBox(vs))
                .setNegativeButton("Оставить", null)
                .show());
    }


    // ================= Цель и поправки =================
    // Чистая геометрия по карте: дальность, дирекционный угол, превышение, смещение точки
    // относительно линии наблюдения. Баллистики (прицел, заряды, таблицы) здесь нет.

    private static final String[] FIRE_KEYS = {"fireOp", "fireNp", "fireT"};
    private static final String[] FIRE_NAMES = {"Огневая позиция (ОП)", "Наблюдатель (НП)", "Цель"};

    /** {lat, lon} и название в Object[]; null — не задано. */
    private Object[] fireSlot(int i) {
        String v = prefs.getString(FIRE_KEYS[i], "");
        if (v.isEmpty()) return null;
        try {
            String[] a = v.split(";", 3);
            return new Object[]{new double[]{Double.parseDouble(a[0]), Double.parseDouble(a[1])}, a.length > 2 ? a[2] : ""};
        } catch (Exception e) {
            return null;
        }
    }

    private void setFireSlot(int i, double lat, double lon, String name) {
        prefs.edit().putString(FIRE_KEYS[i], lat + ";" + lon + ";" + name.replace(";", ",")).apply();
        refreshFireLines();
    }

    private void refreshFireLines() {
        if (geoOverlay == null) return;
        Object[] op = fireSlot(0), np = fireSlot(1), t = fireSlot(2);
        boolean show = prefs.getBoolean("fireShow", true);
        geoOverlay.setFire(show && op != null ? (double[]) op[0] : null, show && np != null ? (double[]) np[0] : null,
                show && t != null ? (double[]) t[0] : null);
        map.invalidate();
    }

    private static String milsSigned(double deg) {
        long m = Math.round(Math.abs(deg) / 0.06);
        return (deg < 0 ? "−" : "+") + String.format(Locale.US, "%d-%02d", m / 100, m % 100);
    }

    private void showFire() {
        sheet("Цель и поправки");
        for (int i = 0; i < 3; i++) {
            if (i == 1 && fireSlot(2) == null) continue; // НП нужен для поправок — когда цель уже есть
            final int slot = i;
            Object[] v = fireSlot(i);
            ImageView ic = hIcon(i == 0 ? R.drawable.ic_place : i == 1 ? R.drawable.ic_eye : R.drawable.ic_flag,
                    i == 0 ? 0xFFFF9100 : i == 1 ? 0xFF64B5F6 : 0xFFFF5252, 22);
            String sub = v == null ? (i == 1 ? "не задан — поправки от ОП" : "не задано · нажмите, чтобы выбрать")
                    : (((String) v[1]).isEmpty() ? "" : v[1] + " · ") + Sk42.format(((double[]) v[0])[0], ((double[]) v[0])[1]);
            hRow(ic, FIRE_NAMES[i], sub, null, x -> pickFireSlot(slot));
        }
        Object[] op = fireSlot(0), np = fireSlot(1), t = fireSlot(2);
        if (op != null && t != null) {
            double[] a = (double[]) op[0], b = (double[]) t[0];
            double[] inv = Geo.inverse(a[0], a[1], b[0], b[1]);
            double grid = Geo.gridBearing(a[0], a[1], b[0], b[1]);
            hSection("ОТ ОП ДО ЦЕЛИ");
            LinearLayout card = hCard();
            addKV(card, "Дальность", Geo.dist(inv[0]));
            addKV(card, "Дир. угол", Geo.mils(grid) + "  (" + String.format(Locale.US, "%.1f°", grid) + ")");
            addKV(card, "Азимут истинный", String.format(Locale.US, "%.1f°", inv[1]));
            final TextView hOp = addKV(card, "Высота ОП / цели", "…");
            final TextView dh = addKV(card, "Превышение цели", "…");
            final TextView site = addKV(card, "Угол места цели", "…");
            sheetContent.addView(card);
            final double dist = inv[0];
            new Thread(() -> {
                double h1 = Elevation.sync(this, a[0], a[1]), h2 = Elevation.sync(this, b[0], b[1]);
                runOnUiThread(() -> {
                    hOp.setText(Elevation.fmt(h1) + " / " + Elevation.fmt(h2));
                    if (Double.isNaN(h1) || Double.isNaN(h2)) {
                        dh.setText("нет данных о рельефе");
                        site.setText("—");
                        return;
                    }
                    double d = h2 - h1;
                    dh.setText((d >= 0 ? "+" : "−") + Math.round(Math.abs(d)) + " м");
                    site.setText(milsSigned(Math.toDegrees(Math.atan2(d, dist))));
                });
            }).start();
            // свой предел дальности — просто сравнение с введённым числом
            final EditText lim = numInput("Ваш предел, км (необязательно)", prefs.getString("fireLim", ""));
            final TextView limRes = new TextView(this);
            limRes.setTextSize(13);
            limRes.setPadding((int) dp(4), (int) dp(4), (int) dp(4), 0);
            Runnable upd = () -> {
                double km = parseNum(lim.getText().toString());
                prefs.edit().putString("fireLim", lim.getText().toString().trim()).apply();
                if (Double.isNaN(km) || km <= 0) { limRes.setText(""); return; }
                double over = dist - km * 1000;
                limRes.setTextColor(over <= 0 ? 0xFF69F0AE : 0xFFFF6B60);
                limRes.setText(over <= 0 ? "В пределах · запас " + Geo.dist(-over) : "Дальше предела на " + Geo.dist(over));
            };
            lim.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a1, int a2, int a3) {}
                @Override public void onTextChanged(CharSequence c, int a1, int a2, int a3) {}
                @Override public void afterTextChanged(android.text.Editable e) { upd.run(); }
            });
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ll.topMargin = (int) dp(8);
            sheetContent.addView(lim, ll);
            sheetContent.addView(limRes);
            upd.run();
        }
        if (t != null) {
            double[] tg = (double[]) t[0];
            double[] obs = np != null ? (double[]) np[0] : op != null ? (double[]) op[0] : null;
            hSection("СМЕСТИТЬ ЦЕЛЬ" + (np != null ? " · КАК ВИДИТ НП" : op != null ? " · КАК ВИДНО С ОП" : ""));
            if (obs == null) {
                TextView w = new TextView(this);
                w.setText("Задайте НП или ОП — смещение «вправо/дальше» считается от линии наблюдения.");
                w.setTextSize(12.5f);
                w.setTextColor(getColor(R.color.hud_text2));
                sheetContent.addView(w);
            } else {
                final EditText right = numInput("0", ""), far = numInput("0", "");
                row3(labeled("Вправо +, влево −, м", right), labeled("Дальше +, ближе −, м", far));
                LinearLayout q = new LinearLayout(this);
                String[][] qs = {{"←50", "r", "-50"}, {"50→", "r", "50"}, {"−100", "f", "-100"}, {"+100", "f", "100"}};
                for (String[] x : qs) {
                    TextView c = hChip(x[0], 0, false, v -> {
                        EditText e = x[1].equals("r") ? right : far;
                        double cur = parseNum(e.getText().toString());
                        e.setText(fmtNum((Double.isNaN(cur) ? 0 : cur) + Double.parseDouble(x[2])));
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(36), 1);
                    lp.setMarginEnd((int) dp(5));
                    q.addView(c, lp);
                }
                LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ql.topMargin = (int) dp(6);
                sheetContent.addView(q, ql);
                final double[] o2 = obs;
                final Object[] opF = op;
                hChipRow(hChip("Сместить цель", R.drawable.ic_check, true, v -> {
                    double r = parseNum(right.getText().toString()), f = parseNum(far.getText().toString());
                    if (Double.isNaN(r)) r = 0;
                    if (Double.isNaN(f)) f = 0;
                    if (r == 0 && f == 0) {
                        Toast.makeText(this, "Введите смещение в метрах", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double az = Geo.inverse(o2[0], o2[1], tg[0], tg[1])[1];
                    double[] p1 = GeoOverlay.dest(tg[0], tg[1], az, f);
                    double[] p2 = GeoOverlay.dest(p1[0], p1[1], az + 90, r);
                    StringBuilder msg = new StringBuilder();
                    if (opF != null) {
                        double[] a = (double[]) opF[0];
                        double d0 = Geo.inverse(a[0], a[1], tg[0], tg[1])[0], d1 = Geo.inverse(a[0], a[1], p2[0], p2[1])[0];
                        double g0 = Geo.gridBearing(a[0], a[1], tg[0], tg[1]), g1 = Geo.gridBearing(a[0], a[1], p2[0], p2[1]);
                        double dg = ((g1 - g0 + 540) % 360) - 180;
                        msg.append("От ОП: дальность ").append(Geo.dist(d1)).append(" (").append(d1 >= d0 ? "+" : "−")
                                .append(Math.round(Math.abs(d1 - d0))).append(" м), дир. угол ").append(Geo.mils(g1))
                                .append(" (").append(milsSigned(dg)).append(")");
                    }
                    String hist = prefs.getString("fireHist", "");
                    String rec = (r >= 0 ? "вправо " : "влево ") + Math.round(Math.abs(r)) + ", " + (f >= 0 ? "дальше " : "ближе ") + Math.round(Math.abs(f));
                    hist = (rec + "\n" + hist);
                    String[] hl = hist.split("\n");
                    StringBuilder hb = new StringBuilder();
                    for (int i = 0; i < Math.min(6, hl.length); i++) if (!hl[i].isEmpty()) hb.append(hl[i]).append('\n');
                    prefs.edit().putString("fireHist", hb.toString().trim())
                            .putString("firePrev", tg[0] + ";" + tg[1]).apply();
                    setFireSlot(2, p2[0], p2[1], "Цель (смещена)");
                    showFire();
                    if (msg.length() > 0) Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show();
                }));
                String hist = prefs.getString("fireHist", "");
                if (!hist.isEmpty()) {
                    TextView h = new TextView(this);
                    h.setText("Смещения: " + hist.replace("\n", "  ·  "));
                    h.setTextSize(12);
                    h.setTextColor(getColor(R.color.hud_text2));
                    h.setPadding((int) dp(4), (int) dp(8), (int) dp(4), 0);
                    sheetContent.addView(h);
                }
            }
            hSection("ЦЕЛЬ");
            actionRow(
                    vAction(R.drawable.ic_place, "На карте", true, v -> { closeSheet(); goTo(tg[0], tg[1], "Цель", Sk42.format(tg[0], tg[1]), Math.max(15, map.getZoomLevelDouble())); }),
                    vAction(R.drawable.ic_flag, "Навести", false, v -> { closeSheet(); setTarget(tg[0], tg[1], "Цель"); }),
                    vAction(R.drawable.ic_add, "В точки", false, v -> editPoint(null, tg[0], tg[1], "Цель")),
                    vAction(R.drawable.ic_share, "Текстом", false, v -> sharePointText("Цель", tg[0], tg[1])),
                    chat != null && chat.active() ? vAction(R.drawable.ic_chat, "В чат", false, v -> { sendPoint("Цель", tg[0], tg[1]); openChat(); }) : null);
            if (!prefs.getString("firePrev", "").isEmpty())
                hChipRow(hChip("Вернуть прежнюю цель", R.drawable.ic_back, false, v -> {
                    String[] a = prefs.getString("firePrev", "").split(";");
                    try {
                        setFireSlot(2, Double.parseDouble(a[0]), Double.parseDouble(a[1]), "Цель");
                    } catch (Exception ignored) {
                    }
                    String hist = prefs.getString("fireHist", "");
                    int nl = hist.indexOf('\n');
                    prefs.edit().remove("firePrev").putString("fireHist", nl < 0 ? "" : hist.substring(nl + 1)).apply();
                    showFire();
                }));
        }
        boolean show = prefs.getBoolean("fireShow", true);
        hChipRow(hChip(show ? "Скрыть линии" : "Линии на карте", R.drawable.ic_eye, false, v -> {
                    prefs.edit().putBoolean("fireShow", !show).apply();
                    refreshFireLines();
                    showFire();
                }),
                hChip("Сбросить всё", R.drawable.ic_delete, false, v -> {
                    SharedPreferences.Editor e = prefs.edit();
                    for (String k : FIRE_KEYS) e.remove(k);
                    e.remove("fireHist").remove("firePrev").apply();
                    refreshFireLines();
                    showFire();
                }));
    }

    /** Откуда взять ОП, НП или цель. */
    private void pickFireSlot(int slot) {
        sheet(FIRE_NAMES[slot]);
        android.location.Location me = myLoc == null ? null : myLoc.getLocation();
        float den = getResources().getDisplayMetrics().density;
        hRow(hIcon(R.drawable.ic_my_location, 0xFF42A5F5, 22), "Где я", me == null ? "включите «Где я» — нет GPS" : "по GPS · ±" + Math.round(me.getAccuracy()) + " м", null, v -> {
            if (me == null) {
                Toast.makeText(this, "Нет местоположения: включите «Где я»", Toast.LENGTH_LONG).show();
                return;
            }
            setFireSlot(slot, me.getLatitude(), me.getLongitude(), "Где я");
            showFire();
        });
        hRow(hIcon(R.drawable.ic_add, getColor(R.color.hud_text), 22), "Прицел карты", "крестик в центре экрана", null, v -> {
            GeoPoint c = (GeoPoint) map.getMapCenter();
            setFireSlot(slot, c.getLatitude(), c.getLongitude(), "");
            showFire();
        });
        if (hasTarget()) {
            double tl = Double.parseDouble(prefs.getString("targetLat", "0")), tn = Double.parseDouble(prefs.getString("targetLon", "0"));
            String tnm = prefs.getString("targetName", "Цель");
            hRow(hIcon(R.drawable.ic_flag, 0xFFFF5252, 22), "Наведённая цель", tnm + " · " + Sk42.format(tl, tn), null, v -> {
                setFireSlot(slot, tl, tn, tnm);
                showFire();
            });
        }
        hSection("ВВЕСТИ КООРДИНАТЫ");
        final EditText in = hInput("X Y СК-42 или 48.12, 37.45", "");
        in.setSingleLine(true);
        sheetContent.addView(in);
        hChipRow(hChip("Применить", R.drawable.ic_check, true, v -> {
            CoordParse.Result c = CoordParse.parse(in.getText().toString());
            if (c == null) {
                Toast.makeText(this, "Координаты не разобрать", Toast.LENGTH_LONG).show();
                return;
            }
            setFireSlot(slot, c.lat, c.lon, "");
            showFire();
        }));
        boolean any = false;
        for (UserPoints.Point p : userPoints) {
            if (!any) hSection("ИЗ МОИХ ТОЧЕК");
            any = true;
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(p.color, den, p.icon));
            hRow(pin, p.name, Sk42.format(p.lat, p.lon), null, v -> { setFireSlot(slot, p.lat, p.lon, p.name); showFire(); });
        }
        if (chat != null && chat.configured()) {
            boolean head = false;
            for (ChatClient.GObj g : chat.objects()) {
                if (!g.type.equals("pt") || g.pts.isEmpty()) continue;
                if (!head) hSection("С КАРТЫ ГРУППЫ");
                head = true;
                ImageView pin = new ImageView(this);
                pin.setImageBitmap(UserPoints.pin(g.color, den, g.icon));
                double la = g.pts.get(0)[0], lo = g.pts.get(0)[1];
                hRow(pin, g.name, Sk42.format(la, lo), null, v -> { setFireSlot(slot, la, lo, g.name); showFire(); });
            }
        }
        hChipRow(hChip("Назад", R.drawable.ic_back, false, v -> showFire()));
    }


    // ================= Тактические знаки =================

    static final int SIGN_OWN = 0xFF2979FF, SIGN_ENEMY = 0xFFFF3D3D;
    private static final String[] SIGNS = {"attack", "retreat", "front", "obstacle", "region"};
    private static final String[] SIGN_NAMES = {"Направление удара", "Отход / манёвр", "Рубеж, позиция", "Заграждение", "Район"};
    private static final int[] SIGN_ICONS = {R.drawable.ic_sign_attack, R.drawable.ic_sign_retreat, R.drawable.ic_sign_front,
            R.drawable.ic_sign_obstacle, R.drawable.ic_area};

    static String signName(String s) {
        for (int i = 0; i < SIGNS.length; i++) if (SIGNS[i].equals(s)) return SIGN_NAMES[i];
        return "Знак";
    }

    private void showSigns() {
        sheet("Тактические знаки");
        final int col = prefs.getInt("signColor", SIGN_OWN);
        LinearLayout seg = new LinearLayout(this);
        final List<TextView> bs = new ArrayList<>();
        final int[] cols = {SIGN_OWN, SIGN_ENEMY};
        final String[] labels = {"Свои (синий)", "Противник (красный)"};
        for (int i = 0; i < 2; i++) {
            final int cc = cols[i];
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(13.5f);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(0, (int) dp(9), 0, (int) dp(9));
            b.setOnClickListener(v -> {
                prefs.edit().putInt("signColor", cc).apply();
                showSigns();
            });
            bs.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i == 0) lp.setMarginEnd((int) dp(6));
            seg.addView(b, lp);
            styleSegHud(b, cc == col);
        }
        sheetContent.addView(seg);
        hSection("ЗНАК");
        View[] row = new View[3];
        int k = 0;
        for (int i = 0; i < SIGNS.length; i++) {
            final String sg = SIGNS[i];
            row[k++] = toolTile(SIGN_ICONS[i], SIGN_NAMES[i], col, () -> {
                closeSheet();
                startTool(sg.equals("region") ? TOOL_AREA : TOOL_ROUTE, null);
                pendingSign = sg;
                pathOverlay.setActive(toolPts, col, sg.equals("region"));
                pathOverlay.setActiveSign(sg);
                updateToolTitle();
                map.invalidate();
                Toast.makeText(this, sg.equals("attack") || sg.equals("retreat")
                        ? "Ставьте точки от начала к острию стрелки, затем «Сохранить»"
                        : sg.equals("front") ? "Ставьте точки линии; зубцы — слева по ходу (в сторону противника)"
                        : "Ставьте точки, затем «Сохранить»", Toast.LENGTH_LONG).show();
            });
            if (k == 3 || i == SIGNS.length - 1) {
                View[] out = new View[k];
                System.arraycopy(row, 0, out, 0, k);
                toolTiles(out);
                k = 0;
            }
        }
        TextView hint = new TextView(this);
        hint.setText("Знаки сохраняются вместе с маршрутами. Чтобы их увидела группа: «Маршруты» → знак → «На карту группы».");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.hud_text2));
        hint.setPadding((int) dp(4), (int) dp(4), (int) dp(4), 0);
        sheetContent.addView(hint);
        boolean any = false;
        for (Routes.Route r : routes) {
            if (r.sign == null || r.sign.isEmpty()) continue;
            if (!any) hSection("МОИ ЗНАКИ");
            any = true;
            ImageView ic = hIcon(signIcon(r.sign), r.color, 22);
            hRow(ic, r.name, signName(r.sign) + " · " + r.pts.size() + " т.", null, v -> routeActions(r));
        }
    }

    private static int signIcon(String s) {
        for (int i = 0; i < SIGNS.length; i++) if (SIGNS[i].equals(s)) return SIGN_ICONS[i];
        return R.drawable.ic_route;
    }


    // ================= Кроки =================

    private LinearLayout sketchPanel;
    private static final int[] SKETCH_COLORS = {0xFFFF3D3D, 0xFF2979FF, 0xFF111111, 0xFFFFFFFF, 0xFFFFEA00, 0xFF00C853};

    private void startSketch() {
        closeSheet();
        if (tool != TOOL_NONE) stopTool();
        sketch.drawing = true;
        sketch.textMode = false;
        sketch.color = prefs.getInt("sketchColor", SKETCH_COLORS[0]);
        sketch.width = prefs.getFloat("sketchW", 3.5f);
        sketch.onText = (la, lo) -> {
            final EditText in = hInput("Подпись", "");
            in.setSingleLine(true);
            android.widget.FrameLayout fr = new android.widget.FrameLayout(this);
            fr.setPadding((int) dp(20), (int) dp(8), (int) dp(20), 0);
            fr.addView(in);
            new AlertDialog.Builder(this)
                    .setTitle("Подпись на кроки")
                    .setView(fr)
                    .setPositiveButton("Поставить", (dd, w) -> {
                        String t = in.getText().toString().trim();
                        if (t.isEmpty()) return;
                        SketchOverlay.Stroke st = new SketchOverlay.Stroke();
                        st.color = sketch.color;
                        st.width = sketch.width;
                        st.text = t;
                        st.pts.add(new double[]{la, lo});
                        sketch.strokes.add(st);
                        sketch.save(this);
                        map.invalidate();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        };
        findViewById(R.id.dock).setVisibility(View.GONE);
        findViewById(R.id.centerMark).setVisibility(View.GONE);
        buildSketchPanel();
        Toast.makeText(this, "Рисуйте пальцем. «Двигать» — сдвинуть или приблизить карту", Toast.LENGTH_LONG).show();
    }

    private void buildSketchPanel() {
        ViewGroup root = findViewById(android.R.id.content);
        if (sketchPanel != null) root.removeView(sketchPanel);
        sketchPanel = new LinearLayout(this);
        sketchPanel.setOrientation(LinearLayout.VERTICAL);
        sketchPanel.setPadding((int) dp(10), (int) dp(10), (int) dp(10), (int) dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xF01A1D22);
        bg.setStroke((int) dp(1), getColor(R.color.hud_line));
        sketchPanel.setBackground(bg);
        sketchPanel.setElevation(dp(24));
        sketchPanel.setClickable(true);
        // цвета и толщина
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(android.view.Gravity.CENTER_VERTICAL);
        for (int col : SKETCH_COLORS) {
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(col);
            boolean on = col == sketch.color;
            g.setStroke((int) dp(on ? 3 : 1), on ? getColor(R.color.hud_accent) : 0xFF555B64);
            sw.setBackground(g);
            sw.setOnClickListener(v -> {
                sketch.color = col;
                prefs.edit().putInt("sketchColor", col).apply();
                buildSketchPanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) dp(30), (int) dp(30));
            lp.setMarginEnd((int) dp(8));
            r1.addView(sw, lp);
        }
        View sp = new View(this);
        r1.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        float[] ws = {2f, 3.5f, 6f};
        String[] wl = {"Тонко", "Средне", "Толсто"};
        int wi = sketch.width <= 2.5f ? 0 : sketch.width <= 4.5f ? 1 : 2;
        TextView wb = hChip(wl[wi], 0, false, v -> {
            sketch.width = ws[(wi + 1) % 3];
            prefs.edit().putFloat("sketchW", sketch.width).apply();
            buildSketchPanel();
        });
        r1.addView(wb, new LinearLayout.LayoutParams((int) dp(84), (int) dp(36)));
        sketchPanel.addView(r1);
        // действия
        LinearLayout r2 = new LinearLayout(this);
        r2.setPadding(0, (int) dp(8), 0, 0);
        boolean draw = sketch.drawing && !sketch.textMode;
        View[] bs = {
                hChip(draw ? "Рисую" : "Рисовать", R.drawable.ic_sketch, draw, v -> { sketch.drawing = true; sketch.textMode = false; buildSketchPanel(); }),
                hChip("Двигать", 0, !sketch.drawing, v -> { sketch.drawing = false; sketch.textMode = false; buildSketchPanel(); }),
                hChip("Текст", 0, sketch.drawing && sketch.textMode, v -> {
                    sketch.drawing = true;
                    sketch.textMode = true;
                    buildSketchPanel();
                    Toast.makeText(this, "Нажмите на карту — там будет подпись", Toast.LENGTH_SHORT).show();
                }),
                hChip("↶", 0, false, v -> {
                    if (!sketch.strokes.isEmpty()) sketch.strokes.remove(sketch.strokes.size() - 1);
                    sketch.save(this);
                    map.invalidate();
                })};
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(40), i == 3 ? 0.6f : 1);
            lp.setMarginEnd((int) dp(6));
            r2.addView(bs[i], lp);
        }
        sketchPanel.addView(r2);
        LinearLayout r3 = new LinearLayout(this);
        r3.setPadding(0, (int) dp(8), 0, 0);
        TextView clr = hChip("Стереть всё", R.drawable.ic_delete, false, v -> new AlertDialog.Builder(this)
                .setTitle("Стереть рисунок?")
                .setPositiveButton("Стереть", (dd, w) -> { sketch.strokes.clear(); sketch.save(this); map.invalidate(); })
                .setNegativeButton("Отмена", null)
                .show());
        TextView done = hChip("Готово", R.drawable.ic_check, true, v -> finishSketch());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, (int) dp(42), 1);
        l1.setMarginEnd((int) dp(8));
        r3.addView(clr, l1);
        r3.addView(done, new LinearLayout.LayoutParams(0, (int) dp(42), 1.3f));
        sketchPanel.addView(r3);
        android.widget.FrameLayout.LayoutParams fl = new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM);
        fl.setMargins((int) dp(10), 0, (int) dp(10), (int) dp(14) + navInset());
        root.addView(sketchPanel, fl);
    }

    private int navInset() {
        try {
            android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            return wi == null ? 0 : wi.getSystemWindowInsetBottom();
        } catch (Exception e) {
            return 0;
        }
    }

    private void stopSketch() {
        sketch.drawing = false;
        sketch.textMode = false;
        sketch.save(this);
        if (sketchPanel != null) {
            ((ViewGroup) findViewById(android.R.id.content)).removeView(sketchPanel);
            sketchPanel = null;
        }
        findViewById(R.id.dock).setVisibility(View.VISIBLE);
        findViewById(R.id.centerMark).setVisibility(View.VISIBLE);
        map.invalidate();
    }

    private void finishSketch() {
        sketch.save(this);
        if (sketch.strokes.isEmpty()) {
            stopSketch();
            return;
        }
        stopSketch();
        sheet("Кроки");
        TextView t = new TextView(this);
        t.setText("Картинка — то, что сейчас на экране: карта и ваш рисунок. Рисунок остаётся на карте, пока вы его не сотрёте.");
        t.setTextSize(12.5f);
        t.setTextColor(getColor(R.color.hud_text2));
        sheetContent.addView(t);
        boolean grp = chat != null && chat.active();
        actionRow(
                grp ? vAction(R.drawable.ic_chat, "В чат", true, v -> { closeSheet(); sketchImage(0); }) : null,
                vAction(R.drawable.ic_share, "Отправить", !grp, v -> { closeSheet(); sketchImage(1); }),
                vAction(R.drawable.ic_download, "В Загрузки", false, v -> { closeSheet(); sketchImage(2); }));
        hChipRow(hChip("Дорисовать", R.drawable.ic_sketch, false, v -> startSketch()),
                hChip("Убрать с карты", R.drawable.ic_delete, false, v -> {
                    sketch.strokes.clear();
                    sketch.save(this);
                    map.invalidate();
                    closeSheet();
                }));
    }

    /** 0 — в чат группы, 1 — отправить через другие приложения, 2 — сохранить в «Загрузки». */
    private void sketchImage(int where) {
        map.post(() -> {
            int w = map.getWidth(), h = map.getHeight();
            if (w == 0 || h == 0) return;
            float sc = Math.min(1f, 1600f / Math.max(w, h));
            android.graphics.Bitmap shot = android.graphics.Bitmap.createBitmap(Math.round(w * sc), Math.round(h * sc), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(shot);
            c.scale(sc, sc);
            c.drawColor(0xFFE8E4DC);
            map.draw(c);
            // подпись внизу: дата и координаты центра
            GeoPoint ctr = (GeoPoint) map.getMapCenter();
            String cap = "Кроки · " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(new Date())
                    + " · центр " + Sk42.format(ctr.getLatitude(), ctr.getLongitude());
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setTextSize(dp(12));
            tp.setColor(Color.WHITE);
            Paint bp = new Paint();
            bp.setColor(0xC0000000);
            c.drawRect(0, h - dp(24), w, h, bp);
            c.drawText(cap, dp(8), h - dp(8), tp);
            new Thread(() -> {
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    int q = 85;
                    do {
                        bo.reset();
                        shot.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bo);
                        q -= 10;
                    } while (where == 0 && bo.size() > 700_000 && q > 35);
                    byte[] jpg = bo.toByteArray();
                    if (where == 0) {
                        ChatClient.Msg m = new ChatClient.Msg();
                        m.kind = "photo";
                        m.text = "Кроки";
                        m.data = android.util.Base64.encodeToString(jpg,
                                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                        m.hasLoc = true;
                        m.lat = ctr.getLatitude();
                        m.lon = ctr.getLongitude();
                        m.ptName = "Кроки";
                        runOnUiThread(() -> { sendChat(m); openChat(); });
                        return;
                    }
                    String name = "Кроки_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date()) + ".jpg";
                    Uri uri = LbsCore.saveToDownloads(getApplicationContext(), name, jpg, "image/jpeg");
                    runOnUiThread(() -> {
                        if (where == 2) {
                            Toast.makeText(this, "Сохранено в «Загрузки»: " + name, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("image/jpeg");
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(Intent.createChooser(i, "Отправить кроки"));
                        } catch (Exception e) {
                            Toast.makeText(this, "Нет приложения для отправки", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Не получилось: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }


    // ================= ЛБС: сравнение двух дат =================

    private int cmpStage;          // 0 — ничего, 1 — грузим список снимков, 2 — считаем
    private int cmpTries;
    private long[] cmpIds, cmpTimes; // снимки DeepStateMap по возрастанию времени
    private long cmpOld, cmpNew;     // выбранные моменты времени (мс); cmpNew = 0 — последний снимок
    private TextView cmpStatus;
    private String cmpLabel = "";
    private List<double[][]> cmpParts = new ArrayList<>();
    private double[] cmpShift = new double[0];

    private void showLbsCompare() {
        if (offline()) {
            Toast.makeText(this, "Нужен интернет — выключите «Без сети»", Toast.LENGTH_LONG).show();
            return;
        }
        if (lbsRunning) {
            Toast.makeText(this, "Сейчас обновляется ЛБС — подождите минуту", Toast.LENGTH_LONG).show();
            return;
        }
        sheet("ЛБС: сравнить даты");
        if (cmpIds == null) {
            cmpStatus = new TextView(this);
            cmpStatus.setText("Загружаю список снимков DeepStateMap…");
            cmpStatus.setTextColor(getColor(R.color.hud_text));
            sheetContent.addView(cmpStatus);
            if (cmpStage != 1) {
                if (lbsWeb == null) setupLbsWeb();
                cmpStage = 1;
                cmpTries = 0;
                lbsWeb.loadUrl("https://deepstatemap.live/api/history/public");
                lbsHandler.postDelayed(() -> {
                    if (cmpStage == 1) cmpFail("сайт не ответил");
                }, 60_000);
            }
            return;
        }
        if (cmpOld == 0) cmpOld = System.currentTimeMillis() - 30L * 86_400_000L;
        hSection("БЫЛО");
        final TextView oldT = new TextView(this);
        oldT.setTextSize(15);
        oldT.setTextColor(getColor(R.color.hud_text));
        oldT.setText("снимок " + snapLabel(cmpSnap(cmpOld)));
        sheetContent.addView(oldT);
        LinearLayout q1 = new LinearLayout(this), q2 = new LinearLayout(this);
        String[] ql = {"Неделя", "2 недели", "Месяц", "3 мес.", "Полгода", "Год"};
        int[] qd = {7, 14, 30, 91, 182, 365};
        for (int i = 0; i < ql.length; i++) {
            final int days = qd[i];
            TextView c = hChip(ql[i], 0, false, v -> {
                cmpOld = System.currentTimeMillis() - days * 86_400_000L;
                showLbsCompare();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(36), 1);
            lp.setMarginEnd((int) dp(5));
            (i < 3 ? q1 : q2).addView(c, lp);
        }
        LinearLayout.LayoutParams ql1 = new LinearLayout.LayoutParams(-1, -2);
        ql1.topMargin = (int) dp(6);
        sheetContent.addView(q1, ql1);
        LinearLayout.LayoutParams ql2 = new LinearLayout.LayoutParams(-1, -2);
        ql2.topMargin = (int) dp(5);
        sheetContent.addView(q2, ql2);
        hChipRow(hChip("Выбрать дату…", R.drawable.ic_refresh, false, v -> pickCmpDate(true)));
        hSection("СТАЛО");
        TextView newT = new TextView(this);
        newT.setTextSize(15);
        newT.setTextColor(getColor(R.color.hud_text));
        newT.setText(cmpNew == 0 ? "последний снимок · " + snapLabel(cmpIds.length - 1) : "снимок " + snapLabel(cmpSnap(cmpNew)));
        sheetContent.addView(newT);
        hChipRow(hChip("Последний", 0, cmpNew == 0, v -> { cmpNew = 0; showLbsCompare(); }),
                hChip("Выбрать дату…", R.drawable.ic_refresh, false, v -> pickCmpDate(false)));
        cmpStatus = new TextView(this);
        cmpStatus.setTextSize(13);
        cmpStatus.setTextColor(getColor(R.color.hud_text2));
        cmpStatus.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        cmpStatus.setText("Красная — линия на дату «Стало». Где она сдвинулась: янтарная — новое положение, голубой пунктир — где была. "
                + "Список участков с расстоянием сдвига — на плашке сверху.");
        sheetContent.addView(cmpStatus);
        if (cmpFolder.getItems().size() > 0)
            hChipRow(hChip("Закончить сравнение", R.drawable.ic_close, false, v -> { clearCompare(); closeSheet(); }));
        footer("Закрыть", "Сравнить", v -> runCompare());
    }

    /** Индекс последнего снимка не позже момента t. */
    private int cmpSnap(long t) {
        int best = 0;
        for (int i = 0; i < cmpTimes.length; i++) if (cmpTimes[i] <= t + 86_399_000L) best = i;
        return best;
    }

    private String snapLabel(int i) {
        return new SimpleDateFormat("d MMMM yyyy, HH:mm", new Locale("ru")).format(new Date(cmpTimes[i]));
    }

    private void pickCmpDate(boolean old) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        long cur = old ? cmpOld : cmpNew == 0 ? System.currentTimeMillis() : cmpNew;
        cal.setTimeInMillis(cur);
        android.app.DatePickerDialog dp = new android.app.DatePickerDialog(this, (view, y, m, dd) -> {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(y, m, dd, 0, 0, 0);
            if (old) cmpOld = c.getTimeInMillis(); else cmpNew = c.getTimeInMillis();
            showLbsCompare();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(cmpTimes[0]);
        dp.getDatePicker().setMaxDate(System.currentTimeMillis());
        dp.show();
    }

    private void cmpListStep() {
        if (cmpStage != 1) return;
        lbsWeb.evaluateJavascript("(function(){var d;try{d=JSON.parse(document.body.innerText)}catch(e){return 'WAIT'}"
                + "if(!Array.isArray(d))return 'ERR формат';return d.filter(function(x){return x.status!==false})"
                + ".map(function(x){return x.id+','+Date.parse(x.createdAt)}).join(';')})()", raw -> {
            if (cmpStage != 1) return;
            String res = LbsCore.decode(raw);
            if (res == null || res.startsWith("WAIT")) {
                if (++cmpTries < 20) lbsHandler.postDelayed(this::cmpListStep, 2000);
                else cmpFail("сайт не отдал список");
                return;
            }
            if (res.startsWith("ERR")) {
                cmpFail(res.substring(3).trim());
                return;
            }
            List<long[]> l = new ArrayList<>();
            for (String it : res.split(";")) {
                String[] a = it.split(",");
                try {
                    l.add(new long[]{Long.parseLong(a[0]), Long.parseLong(a[1])});
                } catch (Exception ignored) {
                }
            }
            if (l.isEmpty()) {
                cmpFail("список снимков пуст");
                return;
            }
            Collections.sort(l, (x, y) -> Long.compare(x[1], y[1]));
            cmpIds = new long[l.size()];
            cmpTimes = new long[l.size()];
            for (int i = 0; i < l.size(); i++) {
                cmpIds[i] = l.get(i)[0];
                cmpTimes[i] = l.get(i)[1];
            }
            cmpStage = 0;
            lbsHandler.removeCallbacksAndMessages(null);
            if (sheetOpen()) showLbsCompare();
        });
    }

    private void cmpFail(String why) {
        cmpStage = 0;
        lbsHandler.removeCallbacksAndMessages(null);
        if (cmpStatus != null) cmpStatus.setText("Не получилось: " + why + ". Попробуйте ещё раз.");
        Toast.makeText(this, "Сравнение ЛБС: " + why, Toast.LENGTH_LONG).show();
    }

    private void runCompare() {
        if (cmpStage != 0) return;
        final int a = cmpSnap(cmpOld), b = cmpNew == 0 ? cmpIds.length - 1 : cmpSnap(cmpNew);
        if (a == b) {
            Toast.makeText(this, "Это один и тот же снимок — выберите другую дату", Toast.LENGTH_LONG).show();
            return;
        }
        final int o = Math.min(a, b), n = Math.max(a, b);
        String lib;
        try {
            lib = LbsCore.readAsset(this, "lbs_lines.js");
        } catch (Exception e) {
            return;
        }
        cmpStage = 2;
        cmpTries = 0;
        cmpLabel = new SimpleDateFormat("dd.MM.yy", Locale.US).format(new Date(cmpTimes[o])) + " → "
                + new SimpleDateFormat("dd.MM.yy", Locale.US).format(new Date(cmpTimes[n]));
        if (cmpStatus != null) cmpStatus.setText("Загружаю два снимка… (около полуминуты)");
        String start = lib + ";(function(){window.__cmp=null;var f=function(id){return fetch('/api/history/'+id+'/geojson')"
                + ".then(function(r){if(!r.ok)throw 'HTTP '+r.status;return r.json()}).then(function(d){return window.__lbsLines(d.features||[])})};"
                + "Promise.all([f(" + cmpIds[o] + "),f(" + cmpIds[n] + ")]).then(function(x){window.__cmp=x[0]+'\\n#####\\n'+x[1]})"
                + ".catch(function(e){window.__cmp='ERR '+e});return 'OK'})()";
        lbsWeb.evaluateJavascript(start, r -> lbsHandler.postDelayed(this::cmpPoll, 1500));
        lbsHandler.postDelayed(() -> {
            if (cmpStage == 2) cmpFail("слишком долго");
        }, 120_000);
    }

    private void cmpPoll() {
        if (cmpStage != 2) return;
        lbsWeb.evaluateJavascript("(function(){return window.__cmp})()", raw -> {
            if (cmpStage != 2) return;
            String res = LbsCore.decode(raw);
            if (res == null) {
                if (cmpStatus != null) cmpStatus.setText("Загружаю два снимка… " + (++cmpTries * 3 / 2) + " с");
                lbsHandler.postDelayed(this::cmpPoll, 1500);
                return;
            }
            if (res.startsWith("ERR")) {
                cmpFail(res.substring(3).trim());
                return;
            }
            if (cmpStatus != null) cmpStatus.setText("Сравниваю…");
            int sep = res.indexOf("\n#####\n");
            final String oldS = sep < 0 ? "" : res.substring(0, sep), newS = sep < 0 ? "" : res.substring(sep + 7);
            new Thread(() -> {
                KmlBuilder.Parsed po = KmlBuilder.parse(oldS), pn = KmlBuilder.parse(newS);
                List<double[][]> moved = KmlBuilder.diff(pn.lines, po.lines, KmlBuilder.DIFF_KM);
                List<double[][]> was = KmlBuilder.diff(po.lines, pn.lines, KmlBuilder.DIFF_KM);
                double[] shift = KmlBuilder.maxShiftKm(moved, po.lines);
                runOnUiThread(() -> {
                    cmpStage = 0;
                    lbsHandler.removeCallbacksAndMessages(null);
                    if (po.lines.isEmpty() || pn.lines.isEmpty()) {
                        cmpFail("в одном из снимков не нашлась линия");
                        return;
                    }
                    showCompare(was, pn.lines, moved, shift);
                });
            }).start();
        });
    }

    /** was — участки старой линии, которых больше нет; moved — участки новой линии, которых не было. */
    private void showCompare(List<double[][]> was, List<double[][]> newL, List<double[][]> moved, double[] shift) {
        cmpFolder.getItems().clear();
        for (double[][] l : newL) cmpFolder.add(line(l, Color.argb(200, 25, 0, 0), 5f));
        for (double[][] l : newL) cmpFolder.add(line(l, Color.rgb(255, 45, 45), 2.8f));
        for (double[][] l : moved) cmpFolder.add(line(l, Color.argb(200, 40, 25, 0), 7f));
        for (double[][] l : moved) cmpFolder.add(line(l, Color.rgb(255, 196, 0), 4.5f));
        for (double[][] l : was) cmpFolder.add(line(l, Color.argb(200, 0, 20, 40), 4.6f));
        for (double[][] l : was) {
            Polyline pl = line(l, Color.rgb(120, 200, 255), 2.6f);
            pl.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(7), dp(5)}, 0));
            cmpFolder.add(pl);
        }
        lbsFolder.setEnabled(false);
        cmpParts = moved;
        cmpShift = shift;
        TextView c = findViewById(R.id.cmpChip);
        double mx = 0;
        int big = 0;
        for (double v : shift) {
            mx = Math.max(mx, v);
            if (v >= 0.5) big++;
        }
        c.setText("ЛБС " + cmpLabel + " · " + (big == 0 ? "без заметных сдвигов" : big + " уч. · до " + km(mx)) + "   ✕");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0xE61F232A);
        g.setStroke((int) dp(1), 0xFFFFC400);
        c.setBackground(g);
        c.setVisibility(View.VISIBLE);
        c.setOnClickListener(v -> showCompareList());
        closeSheet();
        map.invalidate();
        Toast.makeText(this, "Красная — линия сейчас. Где сдвинулась: янтарная — новое положение, голубой пунктир — где была", Toast.LENGTH_LONG).show();
    }

    private static String km(double v) {
        return v < 1 ? Math.round(v * 1000 / 10) * 10 + " м" : String.format(Locale.US, "%.1f км", v).replace('.', ',');
    }

    private void showCompareList() {
        sheet("Сдвиги ЛБС · " + cmpLabel);
        Integer[] idx = new Integer[cmpParts.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(cmpShift[y], cmpShift[x]));
        int shown = 0;
        for (int i : idx) {
            if (cmpShift[i] < 0.5 || shown >= 40) continue;
            shown++;
            double[][] part = cmpParts.get(i);
            double[] mid = part[part.length / 2];
            String where = nearestPlace(mid[1], mid[0]);
            double len = 0;
            for (int j = 1; j < part.length; j++) len += Geo.inverse(part[j - 1][1], part[j - 1][0], part[j][1], part[j][0])[0];
            ImageView ic = hIcon(R.drawable.ic_lbs, 0xFFFFC400, 22);
            hRow(ic, (where.isEmpty() ? "Участок" : "У " + where) + " — до " + km(cmpShift[i]),
                    "сдвиг по фронту на " + Geo.dist(len), null, v -> {
                        closeSheet();
                        double n = -90, s = 90, e = -180, w = 180;
                        for (double[] p : part) {
                            n = Math.max(n, p[1]); s = Math.min(s, p[1]); e = Math.max(e, p[0]); w = Math.min(w, p[0]);
                        }
                        double pad = 0.02;
                        map.zoomToBoundingBox(new org.osmdroid.util.BoundingBox(n + pad, e + pad, s - pad, w - pad), true, (int) dp(50));
                    });
        }
        if (shown == 0) emptyState(R.drawable.ic_lbs, "Заметных сдвигов нет", "Линия сдвинулась меньше чем на 500 м по всему фронту.");
        hChipRow(hChip("Другие даты", R.drawable.ic_refresh, false, v -> showLbsCompare()),
                hChip("Закончить", R.drawable.ic_close, false, v -> { clearCompare(); closeSheet(); }));
    }

    private void clearCompare() {
        cmpFolder.getItems().clear();
        lbsFolder.setEnabled(true);
        findViewById(R.id.cmpChip).setVisibility(View.GONE);
        cmpParts = new ArrayList<>();
        map.invalidate();
    }

    /** Ближайший город/село к точке (до 15 км), пусто — если рядом ничего. */
    private String nearestPlace(double lat, double lon) {
        if (places == null) return "";
        double best = 15000;
        String out = "";
        double kx = Math.cos(Math.toRadians(lat)) * 111320;
        for (int i = 0; i < places.n; i++) {
            if (places.rank[i] > 2) continue;
            double dx = (places.lon[i] - lon) * kx, dy = (places.lat[i] - lat) * 110540;
            double d = Math.hypot(dx, dy) * (places.rank[i] <= 1 ? 0.6 : 1); // города — приоритетнее
            if (d < best) {
                best = d;
                out = places.name[i];
            }
        }
        return out;
    }


    // ================= Свои слои из файлов (KML / KMZ / GPX) =================

    private void loadUserLayers() {
        userLayers = UserLayers.list(this);
        layerOverlay.setLayers(userLayers);
        refreshRaster();
        final List<UserLayers.Layer> ls = userLayers;
        new Thread(() -> {
            for (UserLayers.Layer l : ls) {
                if (l.features != null || l.kind.equals("pgd")) continue;
                List<UserLayers.Feature> f = UserLayers.read(this, l.id);
                runOnUiThread(() -> {
                    l.features = f;
                    map.invalidate();
                });
            }
        }).start();
    }


    /** Растровые слои (.pgd): открыть файлы в фоне и отдать оверлею. */
    private void refreshRaster() {
        final List<UserLayers.Layer> ls = new ArrayList<>(userLayers);
        new Thread(() -> {
            List<RasterLayerOverlay.Item> items = new ArrayList<>();
            for (UserLayers.Layer l : ls) {
                if (!l.kind.equals("pgd")) continue;
                PgdFile f;
                synchronized (pgdOpen) {
                    f = pgdOpen.get(l.id);
                }
                if (f == null) {
                    try {
                        f = new PgdFile(UserLayers.pgdFile(this, l.id));
                        synchronized (pgdOpen) {
                            pgdOpen.put(l.id, f);
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
                items.add(RasterLayerOverlay.item(l, f));
            }
            runOnUiThread(() -> {
                rasterOverlay.set(items);
                map.invalidate();
            });
        }).start();
    }

    /** Карта AlpineQuest (.pgd) копируется в папку приложения как есть и читается прямо оттуда. */
    private void importPgd(Uri uri, String base) {
        Toast.makeText(this, "Копирую карту… (крупный файл — до минуты)", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            String id = Long.toString(System.currentTimeMillis(), 36);
            java.io.File dst = UserLayers.pgdFile(this, id);
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                    if (in == null) throw new Exception("файл не открылся");
                    byte[] buf = new byte[1 << 16];
                    int k;
                    long total = 0;
                    while ((k = in.read(buf)) > 0) {
                        out.write(buf, 0, k);
                        total += k;
                        if (total > 1500L * 1024 * 1024) throw new Exception("файл больше 1,5 ГБ");
                    }
                }
                PgdFile f = new PgdFile(dst);
                synchronized (pgdOpen) {
                    pgdOpen.put(id, f);
                }
                int tiles = 0;
                for (PgdFile.Level lv : f.levels) tiles += lv.tiles.size();
                final int tt = tiles;
                runOnUiThread(() -> showPgdImport(id, base, f, tt, dst.length()));
            } catch (Exception e) {
                dst.delete();
                runOnUiThread(() -> Toast.makeText(this, "Не удалось открыть карту PGD: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showPgdImport(String id, String base, PgdFile f, int tiles, long bytes) {
        sheet("Новый слой · карта");
        final EditText name = hInput("Название слоя", base.replace('_', ' ').trim());
        name.setSingleLine(true);
        sheetContent.addView(name);
        LinearLayout card = hCard();
        addKV(card, "Картинка", f.width + " × " + f.height + " пикс.");
        addKV(card, "Уровней / тайлов", f.levels.size() + " / " + tiles);
        double w = Geo.inverse(f.lat[0], f.lon[0], f.lat[1], f.lon[1])[0], h = Geo.inverse(f.lat[0], f.lon[0], f.lat[3], f.lon[3])[0];
        addKV(card, "Район", Geo.dist(w) + " × " + Geo.dist(h));
        addKV(card, "Размер", String.format(Locale.US, "%.0f МБ", bytes / 1048576.0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = (int) dp(10);
        sheetContent.addView(card, cl);
        TextView t = new TextView(this);
        t.setText("Карта ляжет поверх подложки с прозрачностью, как в AlpineQuest. Хранится отдельным файлом в памяти телефона — само приложение не тяжелеет.");
        t.setTextSize(12.5f);
        t.setTextColor(getColor(R.color.hud_text2));
        t.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(t);
        final boolean[] added = {false};
        footer("Отмена", "Добавить слой", v -> {
            added[0] = true;
            UserLayers.Layer l = new UserLayers.Layer();
            l.id = id;
            l.kind = "pgd";
            String nm = name.getText().toString().trim();
            l.name = nm.isEmpty() ? "Карта" : nm;
            l.count = tiles;
            for (int k = 0; k < 4; k++) {
                l.n = Math.max(l.n, f.lat[k]); l.s = Math.min(l.s, f.lat[k]); l.e = Math.max(l.e, f.lon[k]); l.w = Math.min(l.w, f.lon[k]);
            }
            userLayers.add(l);
            UserLayers.saveList(this, userLayers);
            layerOverlay.setLayers(userLayers);
            refreshRaster();
            closeSheet();
            zoomToLayer(l);
            Toast.makeText(this, "Слой «" + l.name + "» добавлен", Toast.LENGTH_SHORT).show();
        });
        sheetOnClose(() -> {
            if (added[0]) return;
            synchronized (pgdOpen) {
                PgdFile x = pgdOpen.remove(id);
                if (x != null) x.close();
            }
            UserLayers.pgdFile(this, id).delete();
        });
    }

    private boolean startsWithPgd(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            byte[] b = new byte[4];
            return in != null && in.read(b) == 4 && b[0] == 'P' && b[1] == 'G' && b[2] == 'D' && b[3] == ':';
        } catch (Exception e) {
            return false;
        }
    }

    private String uriName(Uri uri) {
        try (android.database.Cursor cur = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        String n = uri.getLastPathSegment();
        return n == null ? "Слой" : n.substring(n.lastIndexOf('/') + 1);
    }

    private void importLayer(Uri uri) {
        String file = uriName(uri);
        String base = file.replaceAll("(?i)\\.(kml|kmz|gpx|xml|pgd)$", "");
        if (file.toLowerCase(Locale.US).endsWith(".pgd") || startsWithPgd(uri)) {
            importPgd(uri, base);
            return;
        }
        Toast.makeText(this, "Читаю «" + file + "»…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<UserLayers.Feature> fs = UserLayers.parse(readUri(uri));
                runOnUiThread(() -> showLayerImport(base, fs));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось прочитать слой (нужен KML, KMZ или GPX): " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showLayerImport(String base, List<UserLayers.Feature> fs) {
        sheet("Новый слой");
        int pts = 0, lines = 0, polys = 0, named = 0;
        for (UserLayers.Feature f : fs) {
            if (f.type == UserLayers.POINT) pts++; else if (f.type == UserLayers.LINE) lines++; else polys++;
            if (!f.name.isEmpty()) named++;
        }
        if (fs.isEmpty()) {
            emptyState(R.drawable.ic_layers, "В файле ничего нет", "Не найдено ни линий, ни участков, ни точек.");
            return;
        }
        final EditText name = hInput("Название слоя", base);
        name.setSingleLine(true);
        sheetContent.addView(name);
        LinearLayout card = hCard();
        addKV(card, "Линий (лесополос)", Integer.toString(lines));
        addKV(card, "Участков, квадратов", Integer.toString(polys));
        addKV(card, "Точек", Integer.toString(pts));
        addKV(card, "С названиями", Integer.toString(named));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = (int) dp(10);
        sheetContent.addView(card, cl);
        TextView t = new TextView(this);
        t.setText("Слой ляжет поверх любой подложки отдельно от ваших точек и маршрутов. Включать, выключать и удалять — в «Слои» → «Мои слои». "
                + "Подписи видны с масштаба ~13 и крупнее.");
        t.setTextSize(12.5f);
        t.setTextColor(getColor(R.color.hud_text2));
        t.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(t);
        footer("Отмена", "Добавить слой", v -> {
            UserLayers.Layer l = new UserLayers.Layer();
            l.id = Long.toString(System.currentTimeMillis(), 36);
            String nm = name.getText().toString().trim();
            l.name = nm.isEmpty() ? "Слой" : nm;
            l.features = fs;
            l.count = fs.size();
            for (UserLayers.Feature f : fs) {
                l.n = Math.max(l.n, f.n); l.s = Math.min(l.s, f.s); l.e = Math.max(l.e, f.e); l.w = Math.min(l.w, f.w);
            }
            v.setEnabled(false);
            new Thread(() -> {
                try {
                    UserLayers.write(this, l);
                    runOnUiThread(() -> {
                        userLayers.add(l);
                        UserLayers.saveList(this, userLayers);
                        layerOverlay.setLayers(userLayers);
                        closeSheet();
                        zoomToLayer(l);
                        Toast.makeText(this, "Слой «" + l.name + "» добавлен", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        v.setEnabled(true);
                        Toast.makeText(this, "Не удалось сохранить слой: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
    }

    private void zoomToLayer(UserLayers.Layer l) {
        if (l.n < l.s) return;
        double pad = Math.max(0.002, (l.n - l.s) * 0.05);
        try {
            map.zoomToBoundingBox(new org.osmdroid.util.BoundingBox(l.n + pad, l.e + pad, l.s - pad, l.w - pad), true, (int) dp(30));
        } catch (Exception ignored) {
        }
    }

    /** Блок «Мои слои» во вкладке «Слои». */
    private void userLayersCard() {
        LinearLayout box = layerBox();
        for (UserLayers.Layer l : userLayers) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int) dp(14), (int) dp(9), (int) dp(8), (int) dp(9));
            ImageView iv = hIcon(R.drawable.ic_layers, getColor(R.color.hud_accent), 20);
            row.addView(iv, new LinearLayout.LayoutParams((int) dp(22), (int) dp(22)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding((int) dp(14), 0, (int) dp(8), 0);
            TextView t = new TextView(this);
            t.setText(l.name);
            t.setTextSize(14.5f);
            t.setTextColor(getColor(R.color.hud_text));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(t);
            TextView st = new TextView(this);
            st.setText((l.kind.equals("pgd") ? "карта AlpineQuest · " + Math.round(l.opacity * 100) + "%" : l.count + " объектов") + " · нажмите — действия");
            st.setTextSize(11.5f);
            st.setTextColor(getColor(R.color.hud_text2));
            tx.addView(st);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            Switch sw = new Switch(this);
            sw.setChecked(l.visible);
            sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Color.WHITE, 0xFFB0B6BE}));
            sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{getColor(R.color.hud_accent), 0xFF3A404A}));
            sw.setOnCheckedChangeListener((b, on) -> {
                l.visible = on;
                iv.setAlpha(on ? 1f : 0.5f);
                UserLayers.saveList(this, userLayers);
                map.invalidate();
            });
            iv.setAlpha(l.visible ? 1f : 0.5f);
            row.addView(sw);
            row.setOnClickListener(v -> layerActions(l));
            box.addView(row);
            layerDivider(box);
        }
        TextView add = new TextView(this);
        add.setText("＋  Загрузить слой — PGD, KML, KMZ, GPX");
        add.setTextSize(14);
        add.setTextColor(getColor(R.color.hud_accent));
        add.setPadding((int) dp(14), (int) dp(12), (int) dp(14), (int) dp(12));
        add.setOnClickListener(v -> pickFile(REQ_LAYER));
        box.addView(add);
        if (userLayers.isEmpty()) {
            TextView h = new TextView(this);
            h.setText("Карты AlpineQuest (.pgd) — как есть; метки и линии — KML/KMZ/GPX («Экспорт» в AlpineQuest).");
            h.setTextSize(11.5f);
            h.setTextColor(getColor(R.color.hud_text2));
            h.setPadding((int) dp(14), 0, (int) dp(14), (int) dp(12));
            box.addView(h);
        }
        setBlock("МОИ СЛОИ", box);
    }

    private void layerActions(UserLayers.Layer l) {
        sheet(l.name);
        LinearLayout card = hCard();
        addKV(card, l.kind.equals("pgd") ? "Тайлов" : "Объектов", Integer.toString(l.count));
        if (l.n >= l.s) addKV(card, "Район", Geo.dist(Geo.inverse(l.n, l.w, l.s, l.e)[0]) + " по диагонали");
        sheetContent.addView(card);
        if (!l.kind.equals("pgd")) {
            LinearLayout box = layerBox();
            setSwitch(box, "Подписи на карте", l.labels, on -> {
                l.labels = on;
                UserLayers.saveList(this, userLayers);
                map.invalidate();
            });
            TextView hint = new TextView(this);
            hint.setText("Выключите, если под этим слоем лежит исходная карта с подписями: поиск и нажатие на полосу продолжат работать.");
            hint.setTextSize(11.5f);
            hint.setTextColor(getColor(R.color.hud_text2));
            hint.setPadding((int) dp(14), 0, (int) dp(14), (int) dp(10));
            box.addView(hint);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = (int) dp(10);
            sheetContent.addView(box, bl);
        }
        hSection("ВИДИМОСТЬ СЛОЯ");
        LinearLayout op = new LinearLayout(this);
        op.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView pv = new TextView(this);
        pv.setText(Math.round(l.opacity * 100) + "%");
        pv.setTextColor(getColor(R.color.hud_text));
        pv.setTextSize(14);
        pv.setMinWidth((int) dp(48));
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(90);
        sb.setProgress(Math.round(l.opacity * 100) - 10);
        sb.setProgressTintList(ColorStateList.valueOf(getColor(R.color.hud_accent)));
        sb.setThumbTintList(ColorStateList.valueOf(getColor(R.color.hud_accent)));
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int pr, boolean user) {
                l.opacity = (pr + 10) / 100f;
                pv.setText((pr + 10) + "%");
                map.invalidate();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) { UserLayers.saveList(MapActivity.this, userLayers); }
        });
        op.addView(sb, new LinearLayout.LayoutParams(0, (int) dp(40), 1));
        op.addView(pv);
        sheetContent.addView(op);
        actionRow(
                vAction(R.drawable.ic_place, "Показать", true, v -> { closeSheet(); l.visible = true; UserLayers.saveList(this, userLayers); zoomToLayer(l); map.invalidate(); }),
                vAction(R.drawable.ic_sketch, "Название", false, v -> {
                    final EditText in = hInput("Название слоя", l.name);
                    in.setSingleLine(true);
                    android.widget.FrameLayout fr = new android.widget.FrameLayout(this);
                    fr.setPadding((int) dp(20), (int) dp(8), (int) dp(20), 0);
                    fr.addView(in);
                    new AlertDialog.Builder(this).setTitle("Название слоя").setView(fr)
                            .setPositiveButton("Сохранить", (dd, w) -> {
                                String nm = in.getText().toString().trim();
                                if (!nm.isEmpty()) l.name = nm;
                                UserLayers.saveList(this, userLayers);
                                layerActions(l);
                            })
                            .setNegativeButton("Отмена", null).show();
                }),
                l.kind.equals("pgd") ? null : vAction(R.drawable.ic_search, "Найти", false, v -> layerSearch(l)),
                vAction(R.drawable.ic_delete, "Удалить", false, v -> new AlertDialog.Builder(this)
                        .setTitle("Удалить слой «" + l.name + "»?")
                        .setMessage("Файл слоя удалится из приложения. Ваши точки и маршруты не затронуты.")
                        .setPositiveButton("Удалить", (dd, w) -> {
                            synchronized (pgdOpen) {
                                PgdFile x = pgdOpen.remove(l.id);
                                if (x != null) x.close();
                            }
                            UserLayers.delete(this, l);
                            userLayers.remove(l);
                            refreshRaster();
                            UserLayers.saveList(this, userLayers);
                            layerOverlay.setLayers(userLayers);
                            map.invalidate();
                            closeSheet();
                        })
                        .setNegativeButton("Отмена", null).show()));
    }

    /** Поиск лесополосы / квадрата по названию внутри слоя. */
    private void layerSearch(UserLayers.Layer l) {
        sheet("Найти в слое");
        final EditText q = hInput("Название, например «Лотос»", "");
        q.setSingleLine(true);
        sheetContent.addView(q);
        final LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        sheetContent.addView(res);
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d2) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d2) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                res.removeAllViews();
                String s = e.toString().trim().toLowerCase(new Locale("ru"));
                if (s.isEmpty() || l.features == null) return;
                int k = 0;
                for (UserLayers.Feature f : l.features) {
                    if (!f.name.toLowerCase(new Locale("ru")).contains(s)) continue;
                    if (k++ >= 30) break;
                    TextView t = new TextView(MapActivity.this);
                    double[] c = featureCenter(f);
                    t.setText(f.name + "   ·   " + (f.type == UserLayers.LINE ? "линия" : f.type == UserLayers.POLY ? "участок" : "точка")
                            + " · " + Sk42.format(c[0], c[1]));
                    t.setTextSize(14);
                    t.setTextColor(getColor(R.color.hud_text));
                    t.setPadding((int) dp(6), (int) dp(11), (int) dp(6), (int) dp(11));
                    t.setOnClickListener(v -> {
                        closeSheet();
                        l.visible = true;
                        map.getController().animateTo(new GeoPoint(c[0], c[1]), Math.max(15.0, map.getZoomLevelDouble()), 600L);
                    });
                    res.addView(t);
                }
                if (k == 0) {
                    TextView t = new TextView(MapActivity.this);
                    t.setText("Не найдено");
                    t.setTextColor(getColor(R.color.hud_text2));
                    t.setPadding((int) dp(6), (int) dp(11), 0, 0);
                    res.addView(t);
                }
            }
        });
        q.requestFocus();
    }

    /** Середина объекта: для линии — точка посередине её длины. */
    private static double[] featureCenter(UserLayers.Feature f) {
        int k = f.lat.length;
        if (f.type == UserLayers.LINE && k > 1) {
            double total = 0;
            for (int i = 1; i < k; i++) total += Math.hypot(f.lat[i] - f.lat[i - 1], f.lon[i] - f.lon[i - 1]);
            double acc = 0;
            for (int i = 1; i < k; i++) {
                double seg = Math.hypot(f.lat[i] - f.lat[i - 1], f.lon[i] - f.lon[i - 1]);
                if (acc + seg >= total / 2 && seg > 0) {
                    double t = (total / 2 - acc) / seg;
                    return new double[]{f.lat[i - 1] + (f.lat[i] - f.lat[i - 1]) * t, f.lon[i - 1] + (f.lon[i] - f.lon[i - 1]) * t};
                }
                acc += seg;
            }
        }
        if (f.type == UserLayers.POLY) return new double[]{(f.n + f.s) / 2, (f.e + f.w) / 2};
        return new double[]{f.lat[0], f.lon[0]};
    }

    /** Нажали на лесополосу, квадрат или точку слоя. */
    private void layerFeatureInfo(UserLayers.Layer l, UserLayers.Feature f) {
        double[] c = featureCenter(f);
        String nm = f.name.isEmpty() ? (f.type == UserLayers.LINE ? "Линия" : f.type == UserLayers.POLY ? "Участок" : "Точка") : f.name;
        sheet(nm);
        LinearLayout card = hCard();
        addKV(card, "Слой", l.name);
        if (f.type == UserLayers.LINE) {
            double len = 0;
            for (int i = 1; i < f.lat.length; i++) len += Geo.inverse(f.lat[i - 1], f.lon[i - 1], f.lat[i], f.lon[i])[0];
            addKV(card, "Длина", Geo.dist(len));
        }
        addKV(card, f.type == UserLayers.POINT ? "СК-42" : "Середина, СК-42", Sk42.format(c[0], c[1]));
        addKV(card, "Квадрат", GridOverlay.square(c[0], c[1]));
        addKV(card, "WGS-84", String.format(Locale.US, "%.5f, %.5f", c[0], c[1]));
        android.location.Location me = myLoc == null ? null : myLoc.getLocation();
        if (me != null) {
            double[] inv = Geo.inverse(me.getLatitude(), me.getLongitude(), c[0], c[1]);
            addKV(card, "От меня", Geo.dist(inv[0]) + " · " + Math.round(inv[1]) + "°");
        }
        if (!f.note.isEmpty()) addKV(card, "Описание", f.note.length() > 200 ? f.note.substring(0, 200) + "…" : f.note);
        sheetContent.addView(card);
        actionRow(
                vAction(R.drawable.ic_flag, "Навести", true, v -> { closeSheet(); setTarget(c[0], c[1], nm); }),
                vAction(R.drawable.ic_add, "В точки", false, v -> editPoint(null, c[0], c[1], nm)),
                vAction(R.drawable.ic_share, "Текстом", false, v -> sharePointText(nm, c[0], c[1])),
                chat != null && chat.active() ? vAction(R.drawable.ic_chat, "В чат", false, v -> { sendPoint(nm, c[0], c[1]); openChat(); }) : null);
        hChipRow(hChip("Исправить название", R.drawable.ic_sketch, false, v -> {
                    final EditText in = hInput("Название", f.name);
                    in.setSingleLine(true);
                    android.widget.FrameLayout fr = new android.widget.FrameLayout(this);
                    fr.setPadding((int) dp(20), (int) dp(8), (int) dp(20), 0);
                    fr.addView(in);
                    new AlertDialog.Builder(this).setTitle("Название").setView(fr)
                            .setPositiveButton("Сохранить", (dd, w) -> {
                                f.name = in.getText().toString().trim();
                                f.note = "";
                                saveLayerAsync(l);
                                map.invalidate();
                                layerFeatureInfo(l, f);
                            })
                            .setNegativeButton("Отмена", null).show();
                }),
                hChip("Убрать из слоя", R.drawable.ic_delete, false, v -> {
                    if (l.features != null) l.features.remove(f);
                    l.count = l.features == null ? 0 : l.features.size();
                    saveLayerAsync(l);
                    UserLayers.saveList(this, userLayers);
                    map.invalidate();
                    closeSheet();
                }));
    }

    private void saveLayerAsync(UserLayers.Layer l) {
        if (l.features == null) return;
        new Thread(() -> {
            try {
                UserLayers.write(this, l);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private ChatClient.GObj objFromRoute(Routes.Route r) {
        ChatClient.GObj g = new ChatClient.GObj();
        g.type = r.area ? "area" : "route";
        g.name = r.name;
        g.color = r.color;
        if (r.sign != null && !r.sign.isEmpty()) g.extra = "{\"sign\":\"" + r.sign + "\"}";
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

    private static final String[] STATUSES = {"На месте", "Выдвигаюсь", "Всё в норме", "Задерживаюсь", "На позиции", "Возвращаюсь",
            "Наблюдаю", "Тихо", "Нужна помощь", "Связь пропадает"};

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

    /** Быстрые статусы в панели смайлов: касание — отправлено (с координатами, если есть GPS). */
    private void fillStatusGrid(LinearLayout grid) {
        LinearLayout row = null;
        for (int i = 0; i < STATUSES.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = (int) dp(6);
                grid.addView(row, rl);
            }
            final String st = STATUSES[i];
            boolean warn = st.equals("Нужна помощь") || st.equals("Связь пропадает");
            TextView c = hChip(st, R.drawable.ic_flag, false, v -> {
                vibrate(25);
                sendStatus("st", st);
                hideEmoji();
            });
            if (warn) c.setTextColor(0xFFFFB74D);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(40), 1);
            if (i % 2 == 0) lp.setMarginEnd((int) dp(6));
            row.addView(c, lp);
        }
        TextView sos = new TextView(this);
        sos.setText("SOS — удерживайте 2 секунды");
        sos.setTextColor(Color.WHITE);
        sos.setTextSize(14);
        sos.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sos.setGravity(android.view.Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(0xFFD32F2F);
        sos.setBackground(g);
        final Runnable fire = () -> { vibrate(500); sendStatus("sos", "Нужна помощь!"); hideEmoji(); };
        sos.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { v.setAlpha(0.6f); vibrate(40); v.postDelayed(fire, 2000); }
            else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) { v.setAlpha(1f); v.removeCallbacks(fire); }
            return true;
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(46));
        sl.topMargin = (int) dp(4);
        grid.addView(sos, sl);
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


    // ================= Ответы, реакции, правка, личные, поиск, группы =================

    private String chatPeer = "";
    private ChatClient.Msg chatReply, chatEdit;
    private View chatBar, chatDmIcon;
    private LinearLayout chatQueueBox;
    private EditText chatInput;
    private long chatJump;
    private final Map<Long, View> bubbleRows = new HashMap<>();

    /** Сообщение относится к открытой переписке (общей или личной). */
    private boolean inConv(ChatClient.Msg m) {
        if (chatPeer.isEmpty()) return !m.dm();
        return m.dm() && m.peer(chat.deviceId()).equals(chatPeer);
    }

    private List<ChatClient.Msg> convHistory() {
        List<ChatClient.Msg> out = new ArrayList<>();
        for (ChatClient.Msg m : chat.history()) if (inConv(m)) out.add(m);
        return out;
    }

    private String peerName(String id) {
        String n = chat.nameOf(id);
        return n.isEmpty() ? "участник" : n;
    }

    private static String snippet(ChatClient.Msg m) {
        String t;
        switch (m.kind) {
            case "pt": t = "📍 " + (m.ptName.isEmpty() ? "Точка" : m.ptName); break;
            case "photo": t = "📷 Фото" + (m.text.isEmpty() ? "" : ": " + m.text); break;
            case "voice": t = "🎤 Голосовое"; break;
            case "st": t = "⚑ " + m.text; break;
            case "sos": t = "SOS " + m.text; break;
            default: t = m.text;
        }
        t = t.replace('\n', ' ');
        return t.length() > 90 ? t.substring(0, 88) + "…" : t;
    }

    private void emptyHint(LinearLayout list, String title, String text) {
        LinearLayout em = new LinearLayout(this);
        em.setOrientation(LinearLayout.VERTICAL);
        em.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        em.setPadding((int) dp(20), (int) dp(48), (int) dp(20), 0);
        em.setTag("empty");
        ImageView ic = hIcon(R.drawable.ic_lock, getColor(R.color.hud_text2), 28);
        em.addView(ic, new LinearLayout.LayoutParams((int) dp(40), (int) dp(40)));
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(15.5f);
        t1.setTextColor(getColor(R.color.hud_text));
        t1.setGravity(android.view.Gravity.CENTER);
        t1.setPadding(0, (int) dp(12), 0, (int) dp(4));
        em.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(text);
        t2.setTextSize(12.5f);
        t2.setGravity(android.view.Gravity.CENTER);
        t2.setTextColor(getColor(R.color.hud_text2));
        em.addView(t2);
        list.addView(em);
    }

    /** Перерисовать ленту, сохранив положение (после правки / удаления / реакции). */
    private void rebuildChatList() {
        if (chatList == null) return;
        int y = chatScroll == null ? 0 : chatScroll.getScrollY();
        boolean bottom = chatScroll == null || chatScroll.getChildAt(0) == null
                || y + chatScroll.getHeight() >= chatScroll.getChildAt(0).getHeight() - dp(80);
        chatList.removeAllViews();
        readMarks.clear();
        bubbleRows.clear();
        lastBubbleDay = "";
        lastBubbleDev = "";
        lastBubbleT = 0;
        List<ChatClient.Msg> hist = convHistory();
        for (int i = Math.max(0, hist.size() - 200); i < hist.size(); i++) addBubble(hist.get(i));
        renderQueue();
        if (chatScroll != null) {
            if (bottom) scrollChatDown();
            else chatScroll.post(() -> chatScroll.scrollTo(0, y));
        }
    }

    /** Неотправленное (нет связи) — внизу ленты, полупрозрачным. */
    private void renderQueue() {
        if (chatQueueBox == null || chatList == null) return;
        chatQueueBox.removeAllViews();
        String d = lastBubbleDay, dev = lastBubbleDev;
        long t = lastBubbleT;
        for (ChatClient.Msg q : chat.outbox()) if (!q.meta() && !q.kind.equals("obj") && inConv(q)) addBubble(q, chatQueueBox);
        lastBubbleDay = d;
        lastBubbleDev = dev;
        lastBubbleT = t;
    }

    /** Прокрутить к сообщению и коротко подсветить его. */
    private void jumpTo(long seq) {
        View r = bubbleRows.get(seq);
        if (r == null || chatScroll == null) {
            Toast.makeText(this, "Сообщение уже недоступно", Toast.LENGTH_SHORT).show();
            return;
        }
        int top = r.getTop() + ((View) r.getParent()).getTop();
        chatScroll.smoothScrollTo(0, Math.max(0, top - (int) dp(80)));
        r.setBackgroundColor(0x3342A5F5);
        r.postDelayed(() -> r.setBackgroundColor(Color.TRANSPARENT), 1200);
    }

    private View quoteView(ChatClient.Msg m) {
        int nl = m.rq.indexOf('\n');
        String who = nl < 0 ? "" : m.rq.substring(0, nl), what = nl < 0 ? m.rq : m.rq.substring(nl + 1);
        LinearLayout q = new LinearLayout(this);
        q.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(0x26FFFFFF);
        q.setBackground(g);
        q.setPadding((int) dp(8), (int) dp(4), (int) dp(8), (int) dp(5));
        TextView a = new TextView(this);
        a.setText(who.isEmpty() ? "Ответ" : who);
        a.setTextSize(12);
        a.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        a.setTextColor(who.isEmpty() ? 0xFF90CAF9 : callColor(who));
        q.addView(a);
        TextView b = new TextView(this);
        b.setText(what);
        b.setTextSize(12.5f);
        b.setMaxLines(2);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setTextColor(0xCCFFFFFF);
        q.addView(b);
        q.setOnClickListener(v -> jumpTo(m.re));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(3);
        lp.bottomMargin = (int) dp(3);
        q.setLayoutParams(lp);
        return q;
    }

    /** Реакции под текстом: «👍 2». Своя подсвечена; нажатие — поставить / снять. */
    private View reactionsView(ChatClient.Msg m) {
        Map<String, List<String>> rx = ChatClient.reactions(m);
        if (rx.isEmpty() || m.queued) return null;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, (int) dp(5), 0, 0);
        for (Map.Entry<String, List<String>> e : rx.entrySet()) {
            boolean mine = e.getValue().contains(chat.deviceId());
            TextView c = new TextView(this);
            c.setText(e.getKey() + (e.getValue().size() > 1 ? " " + e.getValue().size() : ""));
            c.setTextSize(13);
            c.setTextColor(Color.WHITE);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(11));
            g.setColor(mine ? 0x6642A5F5 : 0x26FFFFFF);
            if (mine) g.setStroke((int) dp(1), 0xFF64B5F6);
            c.setBackground(g);
            c.setPadding((int) dp(7), (int) dp(2), (int) dp(7), (int) dp(2));
            c.setOnClickListener(v -> chat.react(m, e.getKey()));
            c.setOnLongClickListener(v -> {
                StringBuilder who = new StringBuilder();
                for (String id : e.getValue()) who.append(who.length() > 0 ? ", " : "").append(id.equals(chat.deviceId()) ? "вы" : peerName(id));
                Toast.makeText(this, e.getKey() + " " + who, Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) dp(4));
            r.addView(c, lp);
        }
        return r;
    }

    private static final String[] QUICK_RX = {"👍", "❤️", "😂", "😮", "🔥", "✅", "❌"};

    /** Долгое нажатие на сообщение: реакции и действия. */
    private void msgMenu(View anchor, ChatClient.Msg m) {
        if (m.queued) return;
        vibrate(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xFF2A2F37);
        bg.setStroke((int) dp(1), getColor(R.color.hud_line));
        box.setBackground(bg);
        box.setPadding((int) dp(6), (int) dp(6), (int) dp(6), (int) dp(6));
        final android.widget.PopupWindow pw = new android.widget.PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        LinearLayout rx = new LinearLayout(this);
        rx.setOrientation(LinearLayout.HORIZONTAL);
        for (String e : QUICK_RX) {
            TextView t = new TextView(this);
            t.setText(e);
            t.setTextSize(22);
            t.setPadding((int) dp(6), (int) dp(4), (int) dp(6), (int) dp(4));
            t.setOnClickListener(v -> { pw.dismiss(); chat.react(m, e); });
            rx.addView(t);
        }
        box.addView(rx);
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.hud_line));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        ll.topMargin = ll.bottomMargin = (int) dp(4);
        box.addView(line, ll);
        menuItem(box, pw, R.drawable.ic_undo, "Ответить", getColor(R.color.hud_text), () -> startReply(m));
        if (!m.text.isEmpty()) menuItem(box, pw, R.drawable.ic_save, "Копировать текст", getColor(R.color.hud_text), () -> copy(m.text, "Текст скопирован"));
        if (m.mine && (m.kind.equals("msg") || m.kind.equals("photo")) && !m.text.isEmpty())
            menuItem(box, pw, R.drawable.ic_label, "Изменить", getColor(R.color.hud_text), () -> startEdit(m));
        if (chat.isAdmin() && !m.dm())
            menuItem(box, pw, R.drawable.ic_flag, "Закрепить", getColor(R.color.hud_text), () -> {
                try {
                    JSONObject pin = new JSONObject().put("pin", snippet(m)).put("seq", m.seq).put("by", m.mine ? chat.callsign() : m.from);
                    chat.setGroupInfo(null, null, null, null, pin, false, () -> Toast.makeText(this, "Закреплено", Toast.LENGTH_SHORT).show(),
                            err -> Toast.makeText(this, "Не закреплено: " + err, Toast.LENGTH_LONG).show());
                } catch (Exception ignored) {
                }
            });
        if (!m.mine && !m.dm() && !m.dev.isEmpty())
            menuItem(box, pw, R.drawable.ic_lock, "Ответить лично", getColor(R.color.hud_text), () -> openDm(m.dev));
        if (chat.canDelete(m))
            menuItem(box, pw, R.drawable.ic_delete, m.mine ? "Удалить у всех" : "Удалить (администратор)", 0xFFFF6B60, () -> new AlertDialog.Builder(this)
                    .setTitle("Удалить сообщение у всех?")
                    .setMessage("Оно пропадёт у всех участников и сотрётся с сервера.")
                    .setPositiveButton("Удалить", (d, w) -> chat.delete(m, err -> Toast.makeText(this, "Не удалено: " + err, Toast.LENGTH_LONG).show()))
                    .setNegativeButton("Отмена", null)
                    .show());
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pw.setElevation(dp(10));
        pw.setOutsideTouchable(true);
        pw.showAsDropDown(anchor, 0, (int) dp(4));
    }

    private void menuItem(LinearLayout box, android.widget.PopupWindow pw, int icon, String text, int color, Runnable r) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14.5f);
        t.setTextColor(color);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
        d.setTint(color);
        int sz = (int) dp(18);
        d.setBounds(0, 0, sz, sz);
        t.setCompoundDrawables(d, null, null, null);
        t.setCompoundDrawablePadding((int) dp(12));
        t.setPadding((int) dp(10), (int) dp(9), (int) dp(18), (int) dp(9));
        t.setOnClickListener(v -> { pw.dismiss(); r.run(); });
        box.addView(t);
    }

    private void startReply(ChatClient.Msg m) {
        if (chatInput == null || !chatOpen) return;
        chatEdit = null;
        chatReply = m;
        showChatBar(R.drawable.ic_undo, "Ответ " + (m.mine ? "себе" : m.from), snippet(m));
        chatInput.requestFocus();
        showKeyboard(chatInput);
    }

    private void startEdit(ChatClient.Msg m) {
        if (chatInput == null) return;
        chatReply = null;
        chatEdit = m;
        showChatBar(R.drawable.ic_label, "Изменение", snippet(m));
        chatInput.setText(m.text);
        chatInput.setSelection(chatInput.getText().length());
        chatInput.requestFocus();
        showKeyboard(chatInput);
    }

    private void showKeyboard(View v) {
        v.post(() -> {
            android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.showSoftInput(v, 0);
        });
    }

    /** Полоска над строкой ввода: «Ответ …» / «Изменение» с крестиком. */
    private void showChatBar(int icon, String title, String text) {
        removeChatBar0();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding((int) dp(12), (int) dp(6), (int) dp(4), (int) dp(2));
        ImageView ic = hIcon(icon, getColor(R.color.hud_accent), 18);
        bar.addView(ic, new LinearLayout.LayoutParams((int) dp(22), (int) dp(22)));
        View acc = new View(this);
        acc.setBackgroundColor(getColor(R.color.hud_accent));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams((int) dp(2), (int) dp(32));
        al.setMarginStart((int) dp(10));
        al.setMarginEnd((int) dp(8));
        bar.addView(acc, al);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView a = new TextView(this);
        a.setText(title);
        a.setTextSize(12.5f);
        a.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        a.setTextColor(getColor(R.color.hud_accent));
        tx.addView(a);
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setTextColor(getColor(R.color.hud_text2));
        tx.addView(b);
        bar.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageView x = hIconButton(R.drawable.ic_close, getColor(R.color.hud_text2), v -> {
            if (chatEdit != null && chatInput != null) chatInput.setText("");
            removeChatBar();
        });
        bar.addView(x, new LinearLayout.LayoutParams((int) dp(40), (int) dp(40)));
        LinearLayout panel = findViewById(R.id.genPanel);
        panel.addView(bar, panel.indexOfChild(findViewById(R.id.genFooter)));
        chatBar = bar;
    }

    private void removeChatBar0() {
        if (chatBar != null && chatBar.getParent() != null) ((ViewGroup) chatBar.getParent()).removeView(chatBar);
        chatBar = null;
    }

    private void removeChatBar() {
        removeChatBar0();
        chatReply = chatEdit = null;
    }

    /** Строка ленты, которую можно смахнуть влево, чтобы ответить. */
    private final class SwipeRow extends LinearLayout {
        Runnable onSwipe;
        private float x0, y0;
        private boolean drag;
        private final int slop = android.view.ViewConfiguration.get(MapActivity.this).getScaledTouchSlop();

        SwipeRow(android.content.Context c) {
            super(c);
            setOrientation(HORIZONTAL);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            if (onSwipe == null) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    drag = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (!drag && dx < -slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                        drag = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (onSwipe == null) return false;
            if (!drag) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    return true;
                }
                if (e.getActionMasked() != android.view.MotionEvent.ACTION_MOVE) return false;
                float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                if (dx < -slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    drag = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                } else return true;
            }
            float dx = Math.min(0, e.getRawX() - x0);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_MOVE:
                    setTranslationX(Math.max(-dp(90), dx));
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    boolean fire = dx < -dp(60) && e.getActionMasked() == android.view.MotionEvent.ACTION_UP;
                    animate().translationX(0).setDuration(150).start();
                    drag = false;
                    if (fire) {
                        vibrate(15);
                        onSwipe.run();
                    }
                    return true;
            }
            return true;
        }
    }

    // ---------- личные сообщения ----------

    private void openDm(String peer) {
        if (peer.equals(chat.deviceId())) return;
        chatPeer = peer;
        openChat();
    }

    private JSONObject dmUnreadMap() {
        try {
            return new JSONObject(prefs.getString("c2DmUnread", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** delta > 0 — прибавить, < 0 — обнулить. */
    private void dmUnread(String peer, int delta) {
        try {
            JSONObject o = dmUnreadMap();
            if (delta < 0) o.remove(peer);
            else o.put(peer, o.optInt(peer) + delta);
            prefs.edit().putString("c2DmUnread", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private int dmUnreadTotal() {
        JSONObject o = dmUnreadMap();
        int n = 0;
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) n += o.optInt(it.next());
        return n;
    }

    private void updateDmIcon() {
        if (chatDmIcon == null || chatDmIcon.getParent() == null) return;
        ViewGroup p = (ViewGroup) chatDmIcon.getParent();
        int i = p.indexOfChild(chatDmIcon);
        p.removeView(chatDmIcon);
        chatDmIcon = headIcon(R.drawable.ic_lock, dmUnreadTotal(), getColor(R.color.hud_text), "Личные сообщения", v -> showDialogs());
        p.addView(chatDmIcon, i);
    }

    /** Список личных переписок и участников, кому можно написать. */
    private void showDialogs() {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        sheet("Личные сообщения");
        Map<String, ChatClient.Msg> last = chat.dialogs();
        JSONObject unread = dmUnreadMap();
        List<ChatClient.Person> ps = new ArrayList<>();
        for (ChatClient.Person p : chat.people()) if (p.active() && !p.id.equals(chat.deviceId())) ps.add(p);
        Collections.sort(ps, (a, b) -> {
            ChatClient.Msg ma = last.get(a.id), mb = last.get(b.id);
            long ta = ma == null ? 0 : ma.t, tb = mb == null ? 0 : mb.t;
            return ta != tb ? Long.compare(tb, ta) : a.callsign.compareToIgnoreCase(b.callsign);
        });
        if (ps.isEmpty()) {
            emptyState(R.drawable.ic_lock, "Пока некому писать", "Когда в группе появятся другие участники, им можно будет написать лично.");
        }
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US), dm = new SimpleDateFormat("dd.MM", Locale.US);
        for (ChatClient.Person p : ps) {
            ChatClient.Msg m = last.get(p.id);
            String sub = m == null ? "написать" : (m.mine ? "Вы: " : "") + snippet(m) + " · "
                    + (dayLabel(m.t).equals("Сегодня") ? hm : dm).format(new Date(m.t));
            int n = unread.optInt(p.id);
            View right = null;
            if (n > 0) {
                TextView b = new TextView(this);
                b.setText(Integer.toString(n));
                b.setTextSize(11);
                b.setTextColor(Color.WHITE);
                b.setGravity(android.view.Gravity.CENTER);
                b.setMinWidth((int) dp(20));
                b.setPadding((int) dp(5), (int) dp(1), (int) dp(5), (int) dp(1));
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(10));
                g.setColor(getColor(R.color.hud_accent));
                b.setBackground(g);
                right = b;
            }
            hRow(personDot(p), p.callsign.isEmpty() ? "без позывного" : p.callsign, sub, right, v -> openDm(p.id));
        }
        // люди из других ваших групп: нажатие — перейти в ту группу и написать
        for (ChatClient.Group g : chat.groups()) {
            if (g.active) continue;
            String myId = chat.myIdIn(g.room);
            boolean head = false;
            for (String[] pp : g.people) {
                if (pp[0].equals(myId)) continue;
                if (!head) { hSection("ГРУППА «" + g.name.toUpperCase(new Locale("ru")) + "»"); head = true; }
                TextView av = new TextView(this);
                av.setText(MembersOverlay.initials(pp[1]));
                av.setTextColor(Color.WHITE);
                av.setTextSize(11);
                av.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                av.setGravity(android.view.Gravity.CENTER);
                GradientDrawable ag = new GradientDrawable();
                ag.setShape(GradientDrawable.OVAL);
                ag.setColor(callColor(pp[1]));
                av.setBackground(ag);
                av.setLayoutParams(new LinearLayout.LayoutParams((int) dp(30), (int) dp(30)));
                final String pid = pp[0];
                hRow(av, pp[1].isEmpty() ? "без позывного" : pp[1], "написать · откроется группа «" + g.name + "»", null, v -> {
                    switchGroup(g);
                    openDm(pid);
                });
            }
        }
        TextView t = new TextView(this);
        t.setText("Личное сообщение шифруется ключом, который есть только у вас двоих. Ни сервер, ни остальные, ни создатель группы его не прочтут.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12);
        t.setPadding((int) dp(4), (int) dp(12), (int) dp(4), 0);
        sheetContent.addView(t);
        hChipRow(hChip("Назад в чат", R.drawable.ic_back, false, v -> { chatPeer = ""; openChat(); }));
    }

    // ---------- поиск ----------

    private void chatSearch(String q0) {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        final String peer = chatPeer;
        sheet(peer.isEmpty() ? "Поиск по чату" : "Поиск · " + peerName(peer));
        final EditText in = hInput("Слово, позывной или название точки", q0);
        in.setSingleLine(true);
        sheetContent.addView(in);
        final String[] kind = {"all"};
        String[][] kinds = {{"all", "Всё"}, {"msg", "Текст"}, {"pt", "Точки"}, {"photo", "Фото"}, {"voice", "Голос"}, {"st", "Статусы"}, {"mine", "Мои"}};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, (int) dp(8), 0, (int) dp(4));
        hs.addView(chips);
        sheetContent.addView(hs);
        final LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        sheetContent.addView(res);
        final Runnable[] run = new Runnable[1];
        final List<TextView> cl = new ArrayList<>();
        for (String[] k : kinds) {
            TextView c = hChip(k[1], 0, k[0].equals("all"), v -> {
                kind[0] = k[0];
                for (int i = 0; i < cl.size(); i++) styleChip(cl.get(i), kinds[i][0].equals(kind[0]));
                run[0].run();
            });
            cl.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(34));
            lp.setMarginEnd((int) dp(6));
            chips.addView(c, lp);
        }
        run[0] = () -> {
            res.removeAllViews();
            String q = in.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<ChatClient.Msg> all = convHistory();
            int n = 0;
            SimpleDateFormat f = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
            for (int i = all.size() - 1; i >= 0 && n < 100; i--) {
                ChatClient.Msg m = all.get(i);
                if (m.kind.equals("obj")) continue;
                String k = kind[0];
                if (k.equals("mine") ? !m.mine : !k.equals("all") && !(k.equals("st") ? m.kind.equals("st") || m.kind.equals("sos") : m.kind.equals(k))) continue;
                String hay = (m.text + " " + m.ptName + " " + m.from).toLowerCase(Locale.ROOT);
                if (!q.isEmpty() && !hay.contains(q)) continue;
                n++;
                TextView av = new TextView(this);
                av.setText(MembersOverlay.initials(m.mine ? chat.callsign() : m.from));
                av.setTextColor(Color.WHITE);
                av.setTextSize(11);
                av.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                av.setGravity(android.view.Gravity.CENTER);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(callColor(m.mine ? chat.callsign() : m.from));
                av.setBackground(g);
                av.setLayoutParams(new LinearLayout.LayoutParams((int) dp(30), (int) dp(30)));
                LinearLayout keep = sheetContent;
                sheetContent = res;
                final long seq = m.seq;
                hRow(av, snippet(m), (m.mine ? "Вы" : m.from) + " · " + f.format(new Date(m.t)), null, v -> {
                    hideKeyboard();
                    chatPeer = peer;
                    chatJump = seq;
                    openChat();
                });
                sheetContent = keep;
            }
            if (n == 0) {
                TextView t = new TextView(this);
                t.setText(q.isEmpty() && kind[0].equals("all") ? "Введите слово для поиска" : "Ничего не найдено");
                t.setTextColor(getColor(R.color.hud_text2));
                t.setTextSize(13);
                t.setPadding((int) dp(6), (int) dp(14), 0, 0);
                res.addView(t);
            }
        };
        in.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a1, int a2, int a3) {}
            @Override public void onTextChanged(CharSequence c, int a1, int a2, int a3) {}
            @Override public void afterTextChanged(Editable e) { run[0].run(); }
        });
        run[0].run();
        in.requestFocus();
        showKeyboard(in);
        hChipRow(hChip("Назад в чат", R.drawable.ic_back, false, v -> { hideKeyboard(); chatPeer = peer; openChat(); }));
    }

    private void styleChip(TextView c, boolean on) {
        c.setBackgroundResource(on ? R.drawable.bg_accent_btn : R.drawable.bg_sheet_chip);
        c.setTextColor(on ? Color.WHITE : getColor(R.color.hud_text));
    }


    // ================= Оформление группы, закреп, удаление =================

    private static final int REQ_GAVATAR = 37;
    private String pendingAvatar;

    private static final int[] GROUP_COLORS = {0, 0xFFFF5A4E, 0xFFFFB300, 0xFF66BB6A, 0xFF29B6F6, 0xFF7E57C2, 0xFFEC407A, 0xFF8D6E63};

    /** Круглый аватар: картинка (base64) или инициалы на цветном круге. */
    private View avatarFrom(String b64, String seed, String name, int sizeDp, int ring) {
        android.graphics.Bitmap bm = null;
        if (b64 != null && !b64.isEmpty()) try {
            byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            bm = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception ignored) {
        }
        if (bm != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF22262C);
            if (ring != 0) g.setStroke((int) dp(2), ring);
            iv.setBackground(g);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            return iv;
        }
        TextView av = new TextView(this);
        av.setText(MembersOverlay.initials(name));
        av.setTextColor(Color.WHITE);
        av.setTextSize(sizeDp * 0.34f);
        av.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        av.setGravity(android.view.Gravity.CENTER);
        GradientDrawable ag = new GradientDrawable();
        ag.setShape(GradientDrawable.OVAL);
        ag.setColor(callColor(seed));
        if (ring != 0) ag.setStroke((int) dp(2), ring);
        av.setBackground(ag);
        return av;
    }

    private View groupAvatarView(ChatClient.GObj info, String name, int sizeDp) {
        return avatarFrom(info == null ? "" : info.icon, name + chat.room(), name, sizeDp, info == null ? 0 : info.color);
    }

    /** Закреплённое сообщение под шапкой чата. */
    private View pinnedBar(JSONObject pin) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding((int) dp(10), (int) dp(6), (int) dp(2), (int) dp(6));
        ChatClient.GObj info = chat.groupInfo();
        int acc = info != null && info.color != 0 ? info.color : getColor(R.color.hud_accent);
        View stripe = new View(this);
        stripe.setBackgroundColor(acc);
        bar.addView(stripe, new LinearLayout.LayoutParams((int) dp(3), (int) dp(30)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding((int) dp(10), 0, (int) dp(6), 0);
        TextView a = new TextView(this);
        a.setText("Закреплено" + (pin.optString("by").isEmpty() ? "" : " · " + pin.optString("by")));
        a.setTextSize(11.5f);
        a.setTextColor(acc);
        a.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tx.addView(a);
        TextView b = new TextView(this);
        b.setText(pin.optString("pin"));
        b.setTextSize(13);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setTextColor(getColor(R.color.hud_text));
        tx.addView(b);
        bar.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final long seq = pin.optLong("seq");
        bar.setOnClickListener(v -> {
            if (bubbleRows.containsKey(seq)) jumpTo(seq);
            else new AlertDialog.Builder(this).setTitle("Закреплено").setMessage(pin.optString("pin")).setPositiveButton("OK", null).show();
        });
        if (chat.isAdmin()) {
            ImageView x = hIconButton(R.drawable.ic_close, getColor(R.color.hud_text2), v ->
                    chat.setGroupInfo(null, null, null, null, null, true, () -> Toast.makeText(this, "Откреплено", Toast.LENGTH_SHORT).show(),
                            err -> Toast.makeText(this, "Не получилось: " + err, Toast.LENGTH_LONG).show()));
            x.setContentDescription("Открепить");
            bar.addView(x, new LinearLayout.LayoutParams((int) dp(36), (int) dp(36)));
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1C2026);
        bar.setBackground(g);
        return bar;
    }

    /** Оформление группы: аватар, название, описание, цвет. Видят все участники. */
    private void showGroupLook() {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        sheet("Оформление группы");
        ChatClient.GObj info = chat.groupInfo();
        final int[] color = {info == null ? 0 : info.color};
        if (pendingAvatar == null) pendingAvatar = info == null ? "" : info.icon;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        top.setPadding(0, (int) dp(4), 0, (int) dp(10));
        View av = avatarFrom(pendingAvatar, chat.groupName() + chat.room(), chat.groupName(), 88, color[0]);
        top.addView(av, new LinearLayout.LayoutParams((int) dp(88), (int) dp(88)));
        TextView pick = new TextView(this);
        pick.setText(pendingAvatar.isEmpty() ? "Выбрать фото" : "Сменить фото");
        pick.setTextColor(getColor(R.color.hud_accent));
        pick.setTextSize(14);
        pick.setPadding((int) dp(10), (int) dp(8), (int) dp(10), (int) dp(4));
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(Intent.createChooser(i, "Аватар группы"), REQ_GAVATAR);
        });
        top.addView(pick);
        if (!pendingAvatar.isEmpty()) {
            TextView rm = new TextView(this);
            rm.setText("Убрать фото");
            rm.setTextColor(getColor(R.color.hud_text2));
            rm.setTextSize(12.5f);
            rm.setOnClickListener(v -> { pendingAvatar = ""; showGroupLook(); });
            top.addView(rm);
        }
        sheetContent.addView(top);
        final EditText name = hInput("Название группы", chat.groupName());
        name.setSingleLine(true);
        sheetContent.addView(name);
        final EditText desc = hInput("Описание (необязательно)", info == null ? "" : info.note);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) dp(8);
        sheetContent.addView(desc, dl);
        hSection("ЦВЕТ ГРУППЫ");
        LinearLayout row = new LinearLayout(this);
        final List<View> sw = new ArrayList<>();
        for (int c : GROUP_COLORS) {
            View v = new View(this);
            int show = c == 0 ? 0xFF555B66 : c;
            v.setBackground(swatchHud(show, c == color[0]));
            v.setContentDescription(c == 0 ? "без цвета" : "цвет");
            v.setOnClickListener(x -> {
                color[0] = c;
                for (int k = 0; k < sw.size(); k++) sw.get(k).setBackground(swatchHud(GROUP_COLORS[k] == 0 ? 0xFF555B66 : GROUP_COLORS[k], GROUP_COLORS[k] == c));
            });
            sw.add(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(34), 1);
            lp.setMarginEnd((int) dp(8));
            row.addView(v, lp);
        }
        sheetContent.addView(row);
        TextView n = new TextView(this);
        n.setText("Аватар, название и описание увидят все участники — и новые тоже. Сообщение можно закрепить: долгое нажатие → «Закрепить».");
        n.setTextColor(getColor(R.color.hud_text2));
        n.setTextSize(12);
        n.setPadding((int) dp(4), (int) dp(10), (int) dp(4), 0);
        sheetContent.addView(n);
        footer("Назад", "Сохранить", v -> {
            String nm = name.getText().toString().trim();
            hideKeyboard();
            chat.setGroupInfo(nm.isEmpty() ? null : nm, desc.getText().toString().trim(), pendingAvatar, color[0], null, false, () -> {
                Toast.makeText(this, "Оформление сохранено", Toast.LENGTH_SHORT).show();
            }, err -> Toast.makeText(this, "Не сохранено: " + err, Toast.LENGTH_LONG).show());
            if (!nm.isEmpty()) prefs.edit().putString("c2Group", nm).apply();
            pendingAvatar = null;
            openChat();
        });
    }

    /** Фото для аватара: квадрат по центру, 160×160, JPEG. */
    private void onGroupAvatarPicked(Uri uri) {
        new Thread(() -> {
            try {
                android.graphics.Bitmap src;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    src = android.graphics.BitmapFactory.decodeStream(in, null, o);
                }
                if (src == null) throw new Exception("не картинка");
                int side = Math.min(src.getWidth(), src.getHeight());
                android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(sq, 160, 160, true);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                sm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bo);
                String b64 = android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
                runOnUiThread(() -> {
                    pendingAvatar = b64;
                    showGroupLook();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось открыть фото: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** Удалить группу у всех (только создатель). */
    private void confirmDestroyGroup() {
        String nm = chat.groupName();
        new AlertDialog.Builder(this)
                .setTitle("Удалить группу «" + nm + "» у всех?")
                .setMessage("Переписка, карта группы и участники будут стёрты с сервера. У всех участников группа пропадёт. Отменить нельзя.")
                .setPositiveButton("Удалить навсегда", (d, w) -> chat.destroy(() -> {
                    closeSheet();
                    membersOverlay.setMembers(chat.members());
                    refreshGroupMap();
                    Toast.makeText(this, "Группа «" + nm + "» удалена" + (chat.configured() ? " · открыта «" + chat.groupName() + "»" : ""), Toast.LENGTH_LONG).show();
                }, err -> Toast.makeText(this, "Не удалено: " + err, Toast.LENGTH_LONG).show()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Долгое нажатие на группу в «Мои группы». */
    private void groupManage(ChatClient.Group g) {
        List<String> items = new ArrayList<>();
        items.add(g.active ? "Открыть чат" : "Перейти в группу");
        items.add("Выйти из группы");
        if (g.role.equals("owner")) items.add("Удалить группу у всех");
        new AlertDialog.Builder(this)
                .setTitle(g.name)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (!g.active) switchGroup(g);
                    if (w == 0) { chatPeer = ""; openChat(); }
                    else if (w == 1) chatSettings();
                    else confirmDestroyGroup();
                })
                .show();
    }

    // ---------- несколько групп ----------

    private static String roleLabel(String r) {
        switch (r) {
            case "owner": return "вы создатель";
            case "admin": return "вы администратор";
            case "member": return "участник";
            case "pending": return "ждёт подтверждения";
            default: return "";
        }
    }

    private static String peopleCount(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? "участник" : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "участника" : "участников";
        return n + " " + w;
    }

    /** Отдельный список групп на телефоне: нажатие — перейти. */
    private void showGroups() {
        chatOpen = false;
        sheetFooter.setVisibility(View.GONE);
        sheet("Мои группы");
        List<ChatClient.Group> gs = chat.groups();
        Map<String, Integer> same = new HashMap<>();
        for (ChatClient.Group g : gs) same.put(g.name, same.containsKey(g.name) ? same.get(g.name) + 1 : 1);
        for (ChatClient.Group g : gs) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding((int) dp(12), (int) dp(12), (int) dp(12), (int) dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(g.active ? 0xFF2A2326 : 0xFF1F232A);
            bg.setStroke((int) dp(g.active ? 1.5f : 1), g.active ? getColor(R.color.hud_accent) : getColor(R.color.hud_line));
            card.setBackground(bg);
            View av = avatarFrom(g.avatar, g.name + g.room, g.name, 44, 0);
            card.addView(av, new LinearLayout.LayoutParams((int) dp(44), (int) dp(44)));
            final ChatClient.Group gg = g;
            card.setOnLongClickListener(v -> { groupManage(gg); return true; });
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding((int) dp(12), 0, (int) dp(8), 0);
            TextView nm = new TextView(this);
            boolean dup = same.get(g.name) > 1;
            nm.setText(g.name + (dup ? "  ·  #" + g.room.substring(0, 4).toUpperCase(Locale.ROOT) : ""));
            nm.setTextSize(16);
            nm.setTextColor(getColor(R.color.hud_text));
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(nm);
            TextView sub = new TextView(this);
            String r = roleLabel(g.role);
            sub.setText((r.isEmpty() ? "" : r + " · ") + peopleCount(Math.max(1, g.count)));
            sub.setTextSize(12.5f);
            sub.setTextColor(getColor(R.color.hud_text2));
            tx.addView(sub);
            card.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            if (g.active) {
                TextView on = new TextView(this);
                on.setText("открыта");
                on.setTextSize(12);
                on.setTextColor(getColor(R.color.hud_accent));
                card.addView(on);
            } else {
                card.addView(hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18), new LinearLayout.LayoutParams((int) dp(20), (int) dp(20)));
            }
            card.setOnClickListener(v -> {
                if (g.active) { chatPeer = ""; openChat(); }
                else switchGroup(g);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) dp(8);
            sheetContent.addView(card, lp);
        }
        TextView t = new TextView(this);
        t.setText("Сообщения приходят из открытой группы. Подержите палец на группе — выйти из неё или удалить (если вы создатель).");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12);
        t.setPadding((int) dp(4), (int) dp(4), (int) dp(4), (int) dp(4));
        sheetContent.addView(t);
        actionRow(
                vAction(R.drawable.ic_qr, "Вступить по QR", false, v -> { closeSheet(); scanQr(); }),
                vAction(R.drawable.ic_add, "Создать", false, v -> createGroup()),
                vAction(R.drawable.ic_back, "В чат", false, v -> { chatPeer = ""; openChat(); }));
    }

    private void switchGroup(ChatClient.Group g) {
        if (prefs.getBoolean("shareLoc", false)) chat.sendLoc(0, 0, 0, Float.NaN, true); // в старой группе меня больше не видно
        ShareLoc.reset();
        ShareLoc.clearTail();
        chatPeer = "";
        chat.switchTo(g.room);
        Toast.makeText(this, "Открыта группа «" + chat.groupName() + "»", Toast.LENGTH_SHORT).show();
        openChat();
    }



    // ================= Инструкция =================

    private static final Object[][] GUIDE = {
            {R.drawable.ic_layers, "Карта и слои", new String[]{
                    "Кнопка «Слои» — подложка (спутник, рельеф, топо, схема), сетка СК-42, дороги, ЛБС.",
                    "Красная линия — ЛБС, линия боевого соприкосновения. Обновить: меню → «ЛБС» → «Обновить».",
                    "Поиск сверху: название села или координаты (СК-42, градусы, MGRS). Нажмите «Поиск» на клавиатуре — карта сразу перейдёт туда.",
                    "Долгое нажатие на карту — координаты, высота и действия с этим местом.",
                    "Без связи: меню → «Офлайн-карты» — заранее скачайте район."}},
            {R.drawable.ic_place, "Точки", new String[]{
                    "Наведите прицел (крестик в центре) и нажмите «＋» — появится точка.",
                    "В точке: название, заметка, цвет, значок, голосовая заметка.",
                    "«Навести» — стрелка и расстояние до точки. «Вести» — пошаговое ведение.",
                    "«Текстом» и «QR-код» — передать точку другому; «На общую карту группы» — увидят все свои."}},
            {R.drawable.ic_route, "Маршруты и ведение", new String[]{
                    "Инструменты → «По прямым» или «По дорогам» — ставьте точки маршрута прицелом.",
                    "Готовый маршрут → «Вести». Ведёт по GPS, а без GPS — по шагам или по скорости, с предупреждением о поворотах.",
                    "Линейка и «Видно ли» — расстояние, азимут и профиль высот между точками."}},
            {R.drawable.ic_sector, "Секторы, круги, зона видимости", new String[]{
                    "Нажмите на точку → «Сектор»: азимут (градусы или 7-50), ширина и дальность — на карте появится сектор обзора или огня. «Азимут на центр экрана» — направьте карту и возьмите азимут одним касанием.",
                    "«Круги» — кольца дальности вокруг точки: 1, 3, 5 км или свои числа через запятую.",
                    "Отправьте точку «На общую карту группы» — свои увидят и сектор, и круги.",
                    "«Зона видимости» — откуда смотрим и на какой высоте (глаза, мачта, ретранслятор, дрон). Зелёным — куда видно по рельефу, тёмным — нет. Лес и дома не учтены.",
                    "Плашка «👁 Видно …%» сверху — нажмите, чтобы убрать зону с карты."}},
            {R.drawable.ic_sign_attack, "Тактические знаки и кроки", new String[]{
                    "Инструменты → «Тактические знаки». Выберите «Свои» (синий) или «Противник» (красный) и знак: направление удара, отход, рубеж, заграждение, район.",
                    "Ставьте точки прицелом, как у маршрута, и нажмите «Сохранить». У стрелки ставьте от начала к острию; у рубежа зубцы — слева по ходу.",
                    "Знаки лежат вместе с маршрутами. «Маршруты» → знак → «На карту группы» — увидят свои.",
                    "«Кроки» — рисуйте пальцем прямо по карте: цвет, толщина, подписи, отмена. «Двигать» — сдвинуть карту, рисунок едет вместе с ней.",
                    "«Готово» → в чат группы, отправить в мессенджер или сохранить картинкой в «Загрузки». Рисунок остаётся на карте, пока его не сотрёте."}},
            {R.drawable.ic_layers, "Свои слои и сетка", new String[]{
                    "«Слои» → «Мои слои» → «Загрузить слой». Карты AlpineQuest (.pgd) ложатся как есть — например, кодированные лесополосы. Метки и линии — KML, KMZ, GPX.",
                    "У карты-слоя можно менять прозрачность: нажмите на слой в списке.",
                    "Слой лежит отдельно от ваших точек: включайте, выключайте, переименовывайте, удаляйте. Названия — как в файле.",
                    "Нажмите на лесополосу или квадрат — название, длина, СК-42, квадрат и улитка; можно навести, сохранить в точки или отправить в чат.",
                    "«Найти» в слое — поиск лесополосы по названию.",
                    "Сетка СК-42: при крупном масштабе номера километров видны по краям экрана (X — слева, Y — сверху), под перекрестием — улитка 1–9."}},
            {R.drawable.ic_lbs, "ЛБС: сравнить даты", new String[]{
                    "Инструменты → «ЛБС: сравнить даты». Нужен интернет.",
                    "«Было»: неделя, месяц, полгода, год назад или любая дата с 2022 г. «Стало»: последний снимок или своя дата.",
                    "На карте: голубой пунктир — было, красная — стало, янтарным — где линия сдвинулась больше чем на 300 м.",
                    "Плашка сверху — список участков по величине сдвига с ближайшим населённым пунктом; нажмите — карта покажет участок. ✕ — закончить."}},
            {R.drawable.ic_flag, "Цель и поправки", new String[]{
                    "Инструменты → «Цель и поправки». Задайте ОП (откуда), цель и, если нужно, НП (откуда наблюдают): «Где я», прицел карты, наведённая цель, координаты или точка.",
                    "Сразу видно: дальность, дирекционный угол в делениях, высоты, превышение и угол места цели.",
                    "«Ваш предел, км» — впишите своё число, и приложение скажет, в пределах цель или дальше.",
                    "Смещение: как видит НП — «вправо +/влево −» и «дальше +/ближе −» в метрах. Цель переедет, а от ОП покажется новая дальность и дир. угол с разницей.",
                    "«Вернуть прежнюю цель» — отменить последнее смещение. Линии ОП и НП видны на карте, их можно скрыть.",
                    "Это расчёт по карте и рельефу (±10 м по высоте), без баллистики."}},
            {R.drawable.ic_moon, "Ночной режим", new String[]{
                    "Кнопка с луной справа — весь экран становится красным и тёмным: меньше слепит и хуже виден со стороны.",
                    "Удерживайте луну — сменить яркость (темно / средне / ярче). То же — в «Слои» → «Ночь»."}},
            {R.drawable.ic_chat, "Группа и чат", new String[]{
                    "Кнопка чата → создать группу (нужен адрес сервера) или вступить по QR от администратора.",
                    "Новых участников подтверждает администратор: «Участники» → «Принять».",
                    "Сообщение: смахните влево — ответить; долгое нажатие — реакция, изменить, удалить у всех.",
                    "🔒 в шапке — личные сообщения: их видите только вы двое.",
                    "Нет связи — сообщения ждут с пометкой 🕓 и уйдут сами.",
                    "«Показывать меня на карте» — свои увидят вас; SOS — тревога всем со звуком.",
                    "Несколько групп — значок «Мои группы» в шапке чата."}},
            {R.drawable.ic_compass, "Где я — засечка без GPS", new String[]{
                    "Найдите вокруг 2–3 заметных ориентира, которые есть и на карте: вышка, церковь, перекрёсток, мост. Лучше, чтобы они были в разных сторонах — идеально под прямым углом друг к другу.",
                    "Инструменты → «Где я (засечка)».",
                    "Двигайте карту так, чтобы прицел (крестик) встал точно на первый ориентир, и нажмите «Прицел карты». Или «Из точек», если ориентир уже сохранён.",
                    "Откроется экран азимута. Положите телефон горизонтально, верхним краем точно на ориентир на местности, подождите пару секунд, чтобы цифры успокоились, и нажмите «Засечь компасом». Отойдите от машины и металла — они сбивают компас.",
                    "Есть свой компас или буссоль? Введите азимут вручную: градусы (123) или деления угломера (20-50). Переключатель «Магнитный» оставьте включённым — склонение прибавится само.",
                    "Повторите для второго и третьего ориентира. После двух появится «Вы здесь» с координатами; третий проверяет точность (±… м).",
                    "Предупреждение «за спиной» или «линии расходятся» — перепроверьте азимут этого ориентира.",
                    "«На карте» — показать место, «В точки» — сохранить, «В чат» — отправить своим."}},
            {R.drawable.ic_download, "Печать района", new String[]{
                    "Покажите на карте нужный район: масштаб, подложку, сетку, точки, маршруты — что видно, то и попадёт на лист.",
                    "Инструменты → «Печать района» → название → PDF (для печати) или картинка (для мессенджера) → «Создать лист».",
                    "Появится предпросмотр. Файл сохраняется в «Загрузки»; «Отправить» — сразу переслать.",
                    "Портретный экран даёт лист A4 книжный. На листе — масштаб, север и координаты центра."}},
            {R.drawable.ic_save, "Безопасность и резервная копия", new String[]{
                    "Меню → «Резервная копия» — сохранить точки, маршруты и группы в файл; «Восстановить» — вернуть.",
                    "Настройки → PIN-код и код экстренного удаления: при вводе этого кода всё стирается.",
                    "Чат шифруется на телефоне. Сервер видит только зашифрованные данные."}},
    };

    static final String TG_CHANNEL = "https://t.me/+m4r3-5RMTBA0ZmE6";

    /** Канал Рубежа в Telegram: новости, обновления, вопросы в комментариях. */
    private void openTgChannel() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TG_CHANNEL)));
        } catch (Exception e) {
            copy(TG_CHANNEL, "Ссылка на канал скопирована");
        }
    }

    /** Инструкция: короткие разделы, открываются по нажатию. open — какой раздел раскрыть (-1 — ни одного). */
    private void showGuide(int open) {
        sheet("Инструкция");
        if (open < 0) {
            hChipRow(hChip("О карте и легенда", R.drawable.ic_info, false, v -> showInfo()),
                    hChip("Telegram-канал", R.drawable.ic_send, false, v -> openTgChannel()));
            View gap = new View(this);
            sheetContent.addView(gap, new LinearLayout.LayoutParams(1, (int) dp(10)));
        }
        final List<View> bodies = new ArrayList<>();
        for (int i = 0; i < GUIDE.length; i++) {
            Object[] g = GUIDE[i];
            LinearLayout card = layerBox();
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cl.bottomMargin = (int) dp(8);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.setPadding((int) dp(14), (int) dp(12), (int) dp(12), (int) dp(12));
            head.addView(hIcon((int) g[0], getColor(R.color.hud_accent), 22), new LinearLayout.LayoutParams((int) dp(24), (int) dp(24)));
            TextView t = new TextView(this);
            t.setText((String) g[1]);
            t.setTextSize(15.5f);
            t.setTextColor(getColor(R.color.hud_text));
            t.setPadding((int) dp(12), 0, 0, 0);
            head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            final ImageView ch = hIcon(R.drawable.ic_chevron, getColor(R.color.hud_text2), 18);
            ch.setRotation(i == open ? 270 : 90);
            head.addView(ch, new LinearLayout.LayoutParams((int) dp(20), (int) dp(20)));
            card.addView(head);
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding((int) dp(14), 0, (int) dp(14), (int) dp(12));
            String[] steps = (String[]) g[2];
            for (int k = 0; k < steps.length; k++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, (int) dp(5), 0, (int) dp(5));
                TextView n = new TextView(this);
                n.setText(Integer.toString(k + 1));
                n.setTextSize(12);
                n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                n.setTextColor(Color.WHITE);
                n.setGravity(android.view.Gravity.CENTER);
                GradientDrawable ng = new GradientDrawable();
                ng.setShape(GradientDrawable.OVAL);
                ng.setColor(0xFF3A404A);
                n.setBackground(ng);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams((int) dp(22), (int) dp(22));
                nl.setMarginEnd((int) dp(10));
                row.addView(n, nl);
                TextView st = new TextView(this);
                st.setText(steps[k]);
                st.setTextSize(13.5f);
                st.setLineSpacing(dp(2), 1f);
                st.setTextColor(getColor(R.color.hud_text));
                row.addView(st, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                body.addView(row);
            }
            body.setVisibility(i == open ? View.VISIBLE : View.GONE);
            bodies.add(body);
            card.addView(body);
            head.setOnClickListener(v -> {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                ch.setRotation(show ? 270 : 90);
            });
            sheetContent.addView(card, cl);
        }
        if (open == 4) hChipRow(hChip("К засечке", R.drawable.ic_compass, true, v -> showResection()));
        else if (open == 5) hChipRow(hChip("К печати", R.drawable.ic_download, true, v -> showPrint()));
    }

    // ================= Обратная засечка =================

    private final List<Resection.Mark> resMarks = new ArrayList<>();
    private float resLiveAz = Float.NaN;
    private TextView resLiveView;
    private boolean resCompassOn;

    private final android.hardware.SensorEventListener resCompass = new android.hardware.SensorEventListener() {
        private final float[] rm = new float[9], or = new float[3];
        private double sn, cs;
        private boolean init;
        private long at;

        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            android.hardware.SensorManager.getRotationMatrixFromVector(rm, e.values);
            android.hardware.SensorManager.getOrientation(rm, or);
            double az = Math.toRadians(Math.toDegrees(or[0]) + resDecl());
            if (!init) { sn = Math.sin(az); cs = Math.cos(az); init = true; }
            sn += (Math.sin(az) - sn) * 0.12;
            cs += (Math.cos(az) - cs) * 0.12;
            resLiveAz = (float) Resection.norm(Math.toDegrees(Math.atan2(sn, cs)));
            long t = System.currentTimeMillis();
            if (resLiveView != null && t - at > 150) {
                at = t;
                resLiveView.setText(String.format(Locale.US, "%.0f°", resLiveAz) + "   " + Resection.mils(resLiveAz));
            }
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor s, int a) {}
    };

    private float resDecl() {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        return new android.hardware.GeomagneticField((float) c.getLatitude(), (float) c.getLongitude(), 0,
                System.currentTimeMillis()).getDeclination();
    }

    private void stopResCompass() {
        if (resCompassOn && sensors != null) sensors.unregisterListener(resCompass);
        resCompassOn = false;
        resLiveView = null;
        resLiveAz = Float.NaN;
    }

    private void addResMark(String name, double lat, double lon) {
        Resection.Mark m = new Resection.Mark();
        m.name = name;
        m.lat = lat;
        m.lon = lon;
        resMarks.add(m);
        editResMark(resMarks.size() - 1);
    }

    /** Список ориентиров и результат. */
    private void showResection() {
        sheet("Где я · обратная засечка");
        if (resMarks.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Найдёт ваше место без GPS. Нужны 2–3 ориентира, которые вы видите вокруг и можете найти на карте "
                    + "(вышка, церковь, перекрёсток, отдельное дерево). Добавьте первый: наведите прицел карты на ориентир → «Прицел карты».");
            t.setTextColor(getColor(R.color.hud_text2));
            t.setTextSize(12.5f);
            t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(6));
            sheetContent.addView(t);
        }
        if (!resMarks.isEmpty()) hSection("ОРИЕНТИРЫ");
        for (int i = 0; i < resMarks.size(); i++) {
            final int k = i;
            Resection.Mark m = resMarks.get(i);
            TextView num = new TextView(this);
            num.setText(Integer.toString(i + 1));
            num.setTextColor(Color.WHITE);
            num.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            num.setGravity(android.view.Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Double.isNaN(m.az) ? 0xFF555B66 : getColor(R.color.hud_accent));
            num.setBackground(g);
            num.setLayoutParams(new LinearLayout.LayoutParams((int) dp(28), (int) dp(28)));
            ImageView del = hIconButton(R.drawable.ic_close, getColor(R.color.hud_text2), v -> { resMarks.remove(k); showResection(); });
            del.setLayoutParams(new LinearLayout.LayoutParams((int) dp(36), (int) dp(36)));
            hRow(num, m.name, Double.isNaN(m.az) ? "азимут не задан — нажмите" : String.format(Locale.US, "азимут %.1f° · %s", m.az, Resection.mils(m.az)),
                    del, v -> editResMark(k));
        }
        hChipRow(hChip("Как пользоваться", R.drawable.ic_info, false, v -> showGuide(4)));
        hChipRow(hChip("Прицел карты", R.drawable.ic_crosshair_add, resMarks.isEmpty(), v -> {
                    GeoPoint c = (GeoPoint) map.getMapCenter();
                    addResMark("Ориентир " + (resMarks.size() + 1), c.getLatitude(), c.getLongitude());
                }),
                hChip("Из точек", R.drawable.ic_place, false, v -> pickResPoint()));
        Resection.Result r = Resection.solve(resMarks);
        if (r != null) {
            hSection("ВЫ ЗДЕСЬ");
            LinearLayout card = hCard();
            addKV(card, "Точность", r.err > 0 ? "±" + Geo.dist(r.err * 2) : "по двум ориентирам — без проверки");
            addKV(card, "Угол засечки", String.format(Locale.US, "%.0f°", r.minAngle) + (r.minAngle < 30 ? " — мал, неточно" : ""));
            sheetContent.addView(card);
            if (r.behind || r.minAngle < 20 || r.err > 300) {
                TextView w = new TextView(this);
                w.setText(r.behind ? "⚠ Один из ориентиров получился «за спиной» — проверьте его азимут."
                        : r.minAngle < 20 ? "⚠ Ориентиры почти на одной линии — возьмите ещё один сбоку."
                        : "⚠ Линии сильно расходятся — перепроверьте азимуты или добавьте ориентир.");
                w.setTextColor(0xFFFFB74D);
                w.setTextSize(12.5f);
                w.setPadding((int) dp(4), (int) dp(6), (int) dp(4), (int) dp(2));
                sheetContent.addView(w);
            }
            coordsCard(r.lat, r.lon);
            final double la = r.lat, lo = r.lon;
            actionRow(
                    vAction(R.drawable.ic_place, "На карте", true, v -> {
                        closeSheet();
                        goTo(la, lo, "Я здесь (засечка)", "по " + resMarks.size() + " ориентирам", Math.max(15, map.getZoomLevelDouble()));
                    }),
                    vAction(R.drawable.ic_add, "В точки", false, v -> editPoint(null, la, lo, "Засечка")),
                    chat.configured() && chat.active() ? vAction(R.drawable.ic_chat, "В чат", false, v -> { sendPoint("Я здесь (засечка)", la, lo); openChat(); }) : null,
                    vAction(R.drawable.ic_delete, "Сначала", false, v -> { resMarks.clear(); showResection(); }));
        } else if (resMarks.size() >= 2) {
            TextView w = new TextView(this);
            w.setText("Задайте азимут хотя бы на два ориентира.");
            w.setTextColor(getColor(R.color.hud_text2));
            w.setTextSize(12.5f);
            w.setPadding((int) dp(4), (int) dp(8), 0, 0);
            sheetContent.addView(w);
        }
    }

    private void pickResPoint() {
        sheet("Ориентир из точек");
        boolean any = false;
        for (UserPoints.Point p : userPoints) {
            any = true;
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(p.color, getResources().getDisplayMetrics().density, p.icon));
            hRow(pin, p.name, Sk42.format(p.lat, p.lon), null, v -> addResMark(p.name, p.lat, p.lon));
        }
        if (chat.configured()) for (ChatClient.GObj g : chat.objects()) {
            if (!g.type.equals("pt") || g.pts.isEmpty()) continue;
            any = true;
            ImageView pin = new ImageView(this);
            pin.setImageBitmap(UserPoints.pin(g.color, getResources().getDisplayMetrics().density, g.icon));
            hRow(pin, g.name, "карта группы · " + Sk42.format(g.pts.get(0)[0], g.pts.get(0)[1]), null,
                    v -> addResMark(g.name, g.pts.get(0)[0], g.pts.get(0)[1]));
        }
        if (!any) emptyState(R.drawable.ic_place, "Точек нет", "Наведите прицел карты на ориентир и выберите «Прицел карты».");
        hChipRow(hChip("Назад", R.drawable.ic_back, false, v -> showResection()));
    }

    /** Азимут на ориентир: компасом телефона или вручную (градусы или деления угломера). */
    private void editResMark(int i) {
        Resection.Mark m = resMarks.get(i);
        sheet("Азимут · " + m.name);
        final EditText name = hInput("Название ориентира", m.name);
        name.setSingleLine(true);
        sheetContent.addView(name);
        if (sensors == null) sensors = getSystemService(android.hardware.SensorManager.class);
        android.hardware.Sensor rv = sensors == null ? null : sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) {
            hSection("КОМПАС ТЕЛЕФОНА");
            LinearLayout card = hCard();
            TextView hint = new TextView(this);
            hint.setText("Держите телефон горизонтально, верхним краем точно на ориентир. Подальше от металла и машины.");
            hint.setTextColor(getColor(R.color.hud_text2));
            hint.setTextSize(12);
            card.addView(hint);
            resLiveView = new TextView(this);
            resLiveView.setText("…");
            resLiveView.setTextSize(30);
            resLiveView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            resLiveView.setTextColor(getColor(R.color.hud_text));
            resLiveView.setGravity(android.view.Gravity.CENTER);
            resLiveView.setPadding(0, (int) dp(8), 0, (int) dp(8));
            card.addView(resLiveView);
            sheetContent.addView(card);
            resCompassOn = true;
            sensors.registerListener(resCompass, rv, android.hardware.SensorManager.SENSOR_DELAY_UI);
            hChipRow(hChip("Засечь компасом", R.drawable.ic_compass, true, v -> {
                if (Float.isNaN(resLiveAz)) return;
                m.az = resLiveAz;
                m.name = name.getText().toString().trim().isEmpty() ? m.name : name.getText().toString().trim();
                vibrate(40);
                hideKeyboard();
                showResection();
            }));
        }
        hSection("ИЛИ ВРУЧНУЮ");
        final EditText az = hInput("Азимут: 45 или 7-50 (деления)", Double.isNaN(m.az) ? "" : String.format(Locale.US, "%.1f", m.az));
        az.setSingleLine(true);
        az.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        sheetContent.addView(az);
        final float decl = resDecl();
        final boolean[] mag = {prefs.getBoolean("resMag", true)};
        LinearLayout box = layerBox();
        setSwitch(box, String.format(Locale.US, "Магнитный (прибавлю склонение %+.1f°)", decl), mag[0], on -> {
            mag[0] = on;
            prefs.edit().putBoolean("resMag", on).apply();
        });
        sheetContent.addView(box);
        footer("Назад", "Сохранить", v -> {
            String n = name.getText().toString().trim();
            if (!n.isEmpty()) m.name = n;
            String a = az.getText().toString().trim();
            if (!a.isEmpty()) {
                double d = Resection.parseAz(a);
                if (Double.isNaN(d)) {
                    Toast.makeText(this, "Не понял азимут: «45», «123.5» или «20-50»", Toast.LENGTH_LONG).show();
                    return;
                }
                m.az = Resection.norm(d + (mag[0] ? decl : 0));
            }
            hideKeyboard();
            showResection();
        });
    }

    // ================= Печать района =================

    private void showPrint() {
        sheet("Печать района");
        TextView t = new TextView(this);
        t.setText("На лист попадёт то, что сейчас видно на карте: подложка, сетка, ЛБС, точки и маршруты. "
                + "Добавлю название, дату, координаты центра, масштаб и стрелку на север.");
        t.setTextColor(getColor(R.color.hud_text2));
        t.setTextSize(12.5f);
        t.setPadding((int) dp(4), 0, (int) dp(4), (int) dp(8));
        sheetContent.addView(t);
        final EditText title = hInput("Название листа", prefs.getString("printTitle", "Район"));
        title.setSingleLine(true);
        sheetContent.addView(title);
        final int[] fmt = {prefs.getInt("printFmt", 0)};
        LinearLayout box = layerBox();
        setSeg(box, "Формат", new String[]{"PDF · A4", "Картинка PNG"}, new int[]{0, 1}, fmt[0], v -> { fmt[0] = v; prefs.edit().putInt("printFmt", v).apply(); });
        sheetContent.addView(box);
        final LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        sheetContent.addView(out);
        footer("Закрыть", "Создать лист", v -> {
            String tt = title.getText().toString().trim();
            if (tt.isEmpty()) tt = "Район";
            prefs.edit().putString("printTitle", tt).apply();
            hideKeyboard();
            makePrint(tt, fmt[0] == 0, out);
        });
    }

    private void printStatus(LinearLayout out, String text, boolean err) {
        out.removeAllViews();
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTextColor(err ? 0xFFFF6B60 : getColor(R.color.hud_text));
        t.setPadding((int) dp(4), (int) dp(12), (int) dp(4), (int) dp(6));
        out.addView(t);
    }

    private void makePrint(String title, boolean pdf, LinearLayout out) {
        int w = map.getWidth(), h = map.getHeight();
        if (w <= 0 || h <= 0) {
            printStatus(out, "Карта ещё не готова — попробуйте через секунду.", true);
            return;
        }
        printStatus(out, "Готовлю лист…", false);
        final Bitmap shot;
        try {
            shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas sc = new android.graphics.Canvas(shot);
            sc.drawColor(0xFFE8E4DC);
            map.draw(sc);
        } catch (Throwable e) {
            printStatus(out, "Не удалось снять карту: " + e, true);
            return;
        }
        // масштаб: сколько метров в одной точке экрана по горизонтали через центр
        org.osmdroid.api.IGeoPoint a = map.getProjection().fromPixels(0, h / 2), b = map.getProjection().fromPixels(w, h / 2);
        final double mPerPx = Geo.inverse(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude())[0] / w;
        final GeoPoint c = (GeoPoint) map.getMapCenter();
        final float rot = map.getMapOrientation();
        new Thread(() -> {
            try {
                boolean land = w > h;
                int pw = land ? 842 : 595, ph = land ? 595 : 842; // A4 в пунктах
                byte[] bytes;
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                String name = "Rubezh_" + title.replaceAll("[^\\p{L}\\p{N}]+", "_") + "_" + stamp + (pdf ? ".pdf" : ".png");
                Bitmap prev = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
                drawSheet(new android.graphics.Canvas(prev), pw, ph, shot, mPerPx, c, rot, title);
                if (pdf) {
                    android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
                    android.graphics.pdf.PdfDocument.Page page = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(pw, ph, 1).create());
                    drawSheet(page.getCanvas(), pw, ph, shot, mPerPx, c, rot, title);
                    doc.finishPage(page);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    doc.writeTo(bo);
                    doc.close();
                    bytes = bo.toByteArray();
                } else {
                    int k = 2;
                    Bitmap big = Bitmap.createBitmap(pw * k, ph * k, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(big);
                    cv.scale(k, k);
                    drawSheet(cv, pw, ph, shot, mPerPx, c, rot, title);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    big.compress(Bitmap.CompressFormat.PNG, 100, bo);
                    big.recycle();
                    bytes = bo.toByteArray();
                }
                shot.recycle();
                String mime = pdf ? "application/pdf" : "image/png";
                Uri uri = LbsCore.saveToDownloads(getApplicationContext(), name, bytes, mime);
                runOnUiThread(() -> {
                    out.removeAllViews();
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(prev);
                    iv.setAdjustViewBounds(true);
                    iv.setBackgroundColor(Color.WHITE);
                    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    il.topMargin = (int) dp(10);
                    out.addView(iv, il);
                    TextView t = new TextView(this);
                    t.setText("✓ Сохранено в «Загрузки»: " + name);
                    t.setTextColor(0xFF81C784);
                    t.setTextSize(12.5f);
                    t.setPadding((int) dp(4), (int) dp(8), (int) dp(4), (int) dp(4));
                    out.addView(t);
                    LinearLayout keep = sheetContent;
                    sheetContent = out;
                    actionRow(
                            vAction(R.drawable.ic_share, "Отправить", true, v -> {
                                Intent i = new Intent(Intent.ACTION_SEND);
                                i.setType(mime);
                                i.putExtra(Intent.EXTRA_STREAM, uri);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                try {
                                    startActivity(Intent.createChooser(i, "Отправить лист"));
                                } catch (Exception e) {
                                    Toast.makeText(this, "Нет приложения для отправки", Toast.LENGTH_LONG).show();
                                }
                            }),
                            vAction(R.drawable.ic_eye, "Открыть", false, v -> {
                                Intent i = new Intent(Intent.ACTION_VIEW);
                                i.setDataAndType(uri, mime);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                try {
                                    startActivity(i);
                                } catch (Exception e) {
                                    Toast.makeText(this, pdf ? "Нет приложения для PDF — выберите «Картинка PNG»" : "Нет приложения для картинок",
                                            Toast.LENGTH_LONG).show();
                                }
                            }));
                    sheetContent = keep;
                    ScrollView sv = findViewById(R.id.genScroll);
                    sv.post(() -> sv.smoothScrollTo(0, out.getTop()));
                });
            } catch (Throwable e) {
                runOnUiThread(() -> printStatus(out, "Не удалось: " + e.getMessage(), true));
            }
        }).start();
    }

    /** Лист: шапка, карта в рамке, масштаб, север. Единицы — пункты A4. */
    private static void drawSheet(android.graphics.Canvas cv, int pw, int ph, Bitmap shot, double mPerPx, GeoPoint c, float rot, String title) {
        cv.drawColor(Color.WHITE);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float m = 24, head = 44, foot = 40;
        p.setColor(0xFF111111);
        p.setTextSize(15);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cv.drawText(title, m, m + 14, p);
        p.setTypeface(android.graphics.Typeface.DEFAULT);
        p.setTextSize(8.5f);
        p.setColor(0xFF444444);
        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(new Date());
        cv.drawText("Центр: " + Sk42.format(c.getLatitude(), c.getLongitude()) + "   ·   "
                + String.format(Locale.US, "%.5f, %.5f", c.getLatitude(), c.getLongitude()), m, m + 30, p);
        p.setTextAlign(android.graphics.Paint.Align.RIGHT);
        cv.drawText(date, pw - m, m + 14, p);
        p.setTextAlign(android.graphics.Paint.Align.LEFT);
        // карта — вписать с сохранением пропорций
        float aw = pw - 2 * m, ah = ph - 2 * m - head - foot;
        float s = Math.min(aw / shot.getWidth(), ah / shot.getHeight());
        float dw = shot.getWidth() * s, dh = shot.getHeight() * s;
        float x0 = m + (aw - dw) / 2, y0 = m + head + (ah - dh) / 2;
        android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(shot, null, new android.graphics.RectF(x0, y0, x0 + dw, y0 + dh), bp);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(0.8f);
        p.setColor(0xFF111111);
        cv.drawRect(x0, y0, x0 + dw, y0 + dh, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        // масштаб
        double mPerPt = mPerPx / s;
        long scale = Math.round(mPerPt / 0.000352778);
        double mag = Math.pow(10, Math.max(0, Math.floor(Math.log10(Math.max(1, scale))) - 1));
        long ns = Math.round(scale / mag) * (long) mag; // две значащие цифры
        double target = dw / 3 * mPerPt, len = 1;
        for (double k : new double[]{1, 2, 5}) for (double e = 1; e <= 1e6; e *= 10) if (k * e <= target) len = Math.max(len, k * e);
        float bar = (float) (len / mPerPt), by = y0 + dh + 18, bx = x0;
        p.setColor(0xFF111111);
        cv.drawRect(bx, by, bx + bar / 2, by + 4, p);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        cv.drawRect(bx, by, bx + bar, by + 4, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setTextSize(8.5f);
        cv.drawText("0", bx - 2, by + 14, p);
        cv.drawText(len >= 1000 ? (len / 1000 == Math.floor(len / 1000) ? String.format(Locale.US, "%.0f км", len / 1000) : String.format(Locale.US, "%.1f км", len / 1000))
                : String.format(Locale.US, "%.0f м", len), bx + bar - 8, by + 14, p);
        cv.drawText("≈ 1:" + String.format(Locale.US, "%,d", ns).replace(',', ' '), bx + bar + 18, by + 6, p);
        // север
        float nx = x0 + dw - 16, ny = y0 + dh + 20;
        cv.save();
        cv.rotate(rot, nx, ny);
        android.graphics.Path ar = new android.graphics.Path();
        ar.moveTo(nx, ny - 13);
        ar.lineTo(nx - 6, ny + 6);
        ar.lineTo(nx, ny + 2);
        ar.lineTo(nx + 6, ny + 6);
        ar.close();
        p.setColor(0xFFC62828);
        cv.drawPath(ar, p);
        cv.restore();
        p.setColor(0xFF111111);
        p.setTextSize(8);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cv.drawText("С", nx - 22, ny + 4, p);
        p.setTypeface(android.graphics.Typeface.DEFAULT);
        p.setColor(0xFF888888);
        p.setTextSize(7);
        p.setTextAlign(android.graphics.Paint.Align.RIGHT);
        cv.drawText("Рубеж · сетка СК-42", pw - m, ph - m + 6, p);
    }

    // ================= Прочитано =================

    private final Map<Long, TextView> readMarks = new HashMap<>();

    private String readText(ChatClient.Msg m) {
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.t));
        String ttl = (m.exp > 0 ? "⏱ " : "") + (m.edited > 0 ? "изм. " : "");
        if (m.queued) return ttl + time + "  🕓 ждёт связи";
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
                chat.leaveAll();
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
        if (chatPeer.isEmpty()) cats.add(new String[]{"⚑ Статус", "#status"}); // быстрые статусы — одним касанием
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
                prefs.edit().putString("emojiTab", cats.get(ci)[0]).apply();
            });
            tabViews.add(t);
            tabs.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
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
        int start = 0;
        String lastTab = prefs.getString("emojiTab", "");
        for (int i = 0; i < cats.size(); i++) if (cats.get(i)[0].equals(lastTab)) start = i;
        for (int k = 0; k < tabViews.size(); k++) styleSeg(tabViews.get(k), k == start);
        fillEmojiGrid(grid, cats.get(start)[1], input);
        LinearLayout gp = findViewById(R.id.genPanel);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = (int) dp(8);
        gp.addView(panel, gp.indexOfChild(sheetFooter) + 1, pl);
        emojiPanel = panel;
        scrollChatDown();
    }

    private void fillEmojiGrid(LinearLayout grid, String list, EditText input) {
        grid.removeAllViews();
        if (list.equals("#status")) {
            fillStatusGrid(grid);
            return;
        }
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


    // ================= Список изменений =================

    /** Пункты «- …» из описания выпуска. */
    private static String notesOf(String body) {
        StringBuilder sb = new StringBuilder();
        for (String l : body.split("\n")) {
            l = l.trim();
            if (l.startsWith("- ")) sb.append(l.substring(2)).append('\n');
        }
        return sb.toString().trim();
    }

    /** Изменения версии из встроенного файла changes.txt. */
    private String localNotes(String ver) {
        try (InputStream in = getAssets().open("changes.txt")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            String all = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            boolean on = false;
            for (String l : all.split("\n")) {
                if (l.startsWith("## ")) { on = l.substring(3).trim().equals(ver); continue; }
                if (on && l.trim().startsWith("- ")) sb.append(l.trim().substring(2)).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Все версии и что в них сделано (из встроенного списка изменений). */
    private void showVersions() {
        String mine = "";
        try {
            mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        sheet("Обновления");
        LinearLayout card = hCard();
        addKV(card, "Установлена", "v" + mine);
        String av = prefs.getString("updVer", "");
        addKV(card, "Последняя", av.isEmpty() ? "не проверялась" : "v" + av + (updateAvailable() ? " — можно обновить" : " — у вас она"));
        sheetContent.addView(card);
        hChipRow(updateAvailable() ? hChip("Обновить до v" + av, R.drawable.ic_download, true, v -> downloadUpdate())
                : hChip("Проверить обновления", R.drawable.ic_refresh, true, v -> checkUpdatesNow()));
        String all;
        try (InputStream in = getAssets().open("changes.txt")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            all = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            all = "";
        }
        String ver = null;
        StringBuilder sb = new StringBuilder();
        List<String[]> secs = new ArrayList<>();
        for (String l : (all + "\n## end").split("\n")) {
            if (l.startsWith("## ")) {
                if (ver != null && sb.length() > 0) secs.add(new String[]{ver, sb.toString().trim()});
                ver = l.substring(3).trim();
                sb.setLength(0);
            } else if (l.trim().startsWith("- ")) sb.append(l.trim().substring(2)).append('\n');
        }
        for (String[] sec : secs) {
            hSection("ВЕРСИЯ " + sec[0] + (sec[0].equals(mine) ? " · УСТАНОВЛЕНА" : ""));
            changeList(sec[1]);
        }
        if (secs.isEmpty()) emptyState(R.drawable.ic_table, "Список изменений пуст", "Он появится в следующей версии.");
    }

    private void changeList(String notes) {
        LinearLayout box = layerBox();
        box.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));
        for (String n : notes.split("\n")) {
            if (n.trim().isEmpty()) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) dp(4), 0, (int) dp(4));
            TextView dot = new TextView(this);
            dot.setText("•");
            dot.setTextSize(15);
            dot.setTextColor(getColor(R.color.hud_accent));
            dot.setPadding(0, 0, (int) dp(10), 0);
            row.addView(dot);
            TextView t = new TextView(this);
            t.setText(n.trim());
            t.setTextSize(14);
            t.setTextColor(getColor(R.color.hud_text));
            t.setLineSpacing(dp(2), 1f);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            box.addView(row);
        }
        sheetContent.addView(box);
    }

    /** После обновления — один раз «Что нового». */
    private void maybeShowWhatsNew() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = pi.getLongVersionCode();
            long seen = prefs.getLong("seenVersionCode", 0);
            prefs.edit().putLong("seenVersionCode", code).remove("updError").apply();
            if (seen == 0 || seen >= code) return; // первая установка или та же версия
            String notes = localNotes(pi.versionName);
            if (notes.isEmpty()) return;
            map.postDelayed(() -> {
                if (sheetOpen() || findViewById(R.id.lockView).getVisibility() == View.VISIBLE) return;
                sheet("Что нового в v" + pi.versionName);
                changeList(notes);
                hChipRow(hChip("Понятно", R.drawable.ic_check, true, v -> closeSheet()));
            }, 900);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unused")
    private static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
