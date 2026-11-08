package ru.lbs.kml;

import android.content.Context;
import android.graphics.Canvas;

import org.osmdroid.tileprovider.MapTileProviderBase;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.TilesOverlay;

/** Второй снимок поверх основного, видимый только слева от шторки (x < split). */
public class CompareOverlay extends TilesOverlay {

    private float split = -1;

    public CompareOverlay(MapTileProviderBase prov, Context c) {
        super(prov, c);
        setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT);
        setLoadingLineColor(android.graphics.Color.TRANSPARENT);
    }

    void setSplit(float x) {
        split = x;
    }

    // без @Override: в разных версиях osmdroid вызывается тот или другой вариант
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow || split <= 0) return;
        c.save();
        c.clipRect(0, 0, split, mv.getHeight());
        super.draw(c, mv, shadow);
        c.restore();
    }

    public void draw(Canvas c, Projection pj) {
        if (split <= 0) return;
        c.save();
        c.clipRect(0, 0, split, c.getHeight());
        super.draw(c, pj);
        c.restore();
    }
}
