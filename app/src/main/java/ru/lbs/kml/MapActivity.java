package ru.lbs.kml;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
        File base = new File(getCacheDir(), "osmdroid");
        conf.setOsmdroidBasePath(base);
        conf.setOsmdroidTileCache(new File(base, "tiles"));

        setContentView(R.layout.activity_map);
        applyInsets(findViewById(R.id.mapRoot), findViewById(R.id.topBar), findViewById(R.id.bottomBar));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.setMinZoomLevel(5.0);
        map.setMaxZoomLevel(18.0);

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
        buildChips();

        if (savedInstanceState != null) selected = savedInstanceState.getInt("sel", 0);
        map.addOnFirstLayoutListener((v, l, t, r, b) -> zoomTo(selected, false));
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
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
