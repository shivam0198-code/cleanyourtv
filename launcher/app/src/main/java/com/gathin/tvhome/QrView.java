package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

public final class QrView extends View {
    private static final int QUIET = 4;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int DARK = 0xFF0E1022;
    private final Paint paint = new Paint();
    private boolean[][] modules;

    public QrView(Context context) {
        this(context, null);
    }

    public QrView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(DARK);
        paint.setAntiAlias(false);
        float radius = 12 * context.getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    public boolean setText(String text) {
        modules = text == null || text.isEmpty() ? null : Qr.encode(text);
        invalidate();
        return modules != null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(LIGHT);
        if (modules == null) return;
        int n = modules.length + 2 * QUIET;
        int side = Math.min(getWidth(), getHeight());
        int scale = Math.max(1, side / n);
        int left = (getWidth() - scale * n) / 2 + QUIET * scale;
        int top = (getHeight() - scale * n) / 2 + QUIET * scale;
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) {
                if (modules[y][x]) {
                    canvas.drawRect(left + x * scale, top + y * scale,
                            left + (x + 1) * scale, top + (y + 1) * scale, paint);
                }
            }
        }
    }
}
