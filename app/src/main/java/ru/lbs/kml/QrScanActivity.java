package ru.lbs.kml;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.EnumMap;
import java.util.Map;

/** Сканер QR-кода на камере (без интернета и сторонних приложений). Возвращает текст в EXTRA "qr". */
@SuppressWarnings("deprecation")
public class QrScanActivity extends Activity implements SurfaceHolder.Callback {

    private SurfaceView surface;
    private Camera cam;
    private HandlerThread worker;
    private Handler workHandler;
    private volatile boolean busy, done;
    private final QRCodeReader reader = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new Frame(this), new FrameLayout.LayoutParams(-1, -1));
        TextView hint = new TextView(this);
        hint.setText("Наведите камеру на QR-код Рубеж");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(32, 32, 32, 160);
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        TextView close = new TextView(this);
        close.setText("✕  Закрыть");
        close.setTextColor(Color.WHITE);
        close.setTextSize(16);
        close.setPadding(40, 140, 40, 40);
        close.setOnClickListener(v -> finish());
        root.addView(close, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(root);
        surface.getHolder().addCallback(this);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 5);
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (surface.getHolder().getSurface().isValid()) openCamera(surface.getHolder());
        } else {
            Toast.makeText(this, "Без доступа к камере QR-код не считать", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { openCamera(h); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { closeCamera(); }

    private void openCamera(SurfaceHolder h) {
        if (cam != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
            }
            Camera.getCameraInfo(id, info);
            cam = Camera.open(id);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            int deg = rot == Surface.ROTATION_90 ? 90 : rot == Surface.ROTATION_180 ? 180 : rot == Surface.ROTATION_270 ? 270 : 0;
            cam.setDisplayOrientation((info.orientation - deg + 360) % 360);
            Camera.Parameters p = cam.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width * s.height > 1920 * 1080) continue;
                if (best == null || s.width * s.height > best.width * best.height) best = s;
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            cam.setParameters(p);
            cam.setPreviewDisplay(h);
            worker = new HandlerThread("qr");
            worker.start();
            workHandler = new Handler(worker.getLooper());
            final Camera.Size size = cam.getParameters().getPreviewSize();
            cam.setPreviewCallback((data, c) -> {
                if (busy || done) return;
                busy = true;
                final byte[] copy = data.clone();
                workHandler.post(() -> decode(copy, size.width, size.height));
            });
            cam.startPreview();
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(p.getFocusMode())) {
                surface.setOnClickListener(v -> { try { cam.autoFocus(null); } catch (Exception ignored) { } });
                try { cam.autoFocus(null); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Камера не открылась: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void decode(byte[] data, int w, int h) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
            Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            if (r != null && r.getText() != null && !done) {
                done = true;
                final String text = r.getText();
                runOnUiThread(() -> {
                    setResult(RESULT_OK, new Intent().putExtra("qr", text));
                    finish();
                });
            }
        } catch (Exception ignored) {
        } finally {
            reader.reset();
            busy = false;
        }
    }

    private void closeCamera() {
        if (cam != null) {
            try {
                cam.setPreviewCallback(null);
                cam.stopPreview();
            } catch (Exception ignored) {
            }
            cam.release();
            cam = null;
        }
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surface.getHolder().getSurface().isValid()) openCamera(surface.getHolder());
    }

    /** Рамка прицеливания. */
    private static final class Frame extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Frame(android.content.Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xFF42A5F5);
            p.setStrokeWidth(6);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) * 0.72f;
            float l = (getWidth() - s) / 2, t = (getHeight() - s) / 2;
            c.drawRoundRect(l, t, l + s, t + s, 28, 28, p);
        }
    }
}
