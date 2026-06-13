package com.edgedetection.ui.battle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.View;

public class RouteHintsOverlayView extends View {

    private static final float POLE_HEIGHT = 25f;
    private static final float POLE_WIDTH = 8f;
    private static final float CAP_HALF = 28f;

    private final float[] viewMat = new float[16];
    private final float[] projMat = new float[16];
    private final float[] mvpMat = new float[16];
    private final float[] tmpVec = new float[4];
    private final float[] tmpOut = new float[4];
    private int screenW, screenH;
    private boolean hasMatrices = false;

    private volatile float[][] waypoints = null;

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPoleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPoleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPoleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPoleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path routePath = new Path();

    public RouteHintsOverlayView(Context context) {
        super(context);
        init();
    }

    public RouteHintsOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        routePaint.setColor(0xFF00FF88);
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(4f);
        routePaint.setPathEffect(new DashPathEffect(new float[]{22f, 12f}, 0));

        startPoleStroke.setColor(0xFF00FF44);
        startPoleStroke.setStyle(Paint.Style.STROKE);
        startPoleStroke.setStrokeWidth(POLE_WIDTH);
        startPoleStroke.setStrokeCap(Paint.Cap.ROUND);

        startPoleFill.setColor(0x6600FF44);
        startPoleFill.setStyle(Paint.Style.FILL);

        endPoleStroke.setColor(0xFFFF3300);
        endPoleStroke.setStyle(Paint.Style.STROKE);
        endPoleStroke.setStrokeWidth(POLE_WIDTH);
        endPoleStroke.setStrokeCap(Paint.Cap.ROUND);

        endPoleFill.setColor(0x66FF3300);
        endPoleFill.setStyle(Paint.Style.FILL);

        startLabelPaint.setColor(0xFF00FF44);
        startLabelPaint.setTextSize(40f);
        startLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);

        endLabelPaint.setColor(0xFFFF4400);
        endLabelPaint.setTextSize(40f);
        endLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);

        dotPaint.setColor(0xBB00CCFF);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setWaypoints(float[][] waypointsWorld) {
        this.waypoints = waypointsWorld;
        postInvalidate();
    }

    public void setCameraMatrices(float[] view, float[] proj, int w, int h) {
        synchronized (this) {
            System.arraycopy(view, 0, viewMat, 0, 16);
            System.arraycopy(proj, 0, projMat, 0, 16);
            screenW = w;
            screenH = h;
            hasMatrices = true;
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float[][] pts = waypoints;
        if (!hasMatrices || pts == null || pts.length < 2 || screenW == 0) return;

        float[] v = new float[16], p = new float[16];
        int w, h;
        synchronized (this) {
            System.arraycopy(viewMat, 0, v, 0, 16);
            System.arraycopy(projMat, 0, p, 0, 16);
            w = screenW;
            h = screenH;
        }
        Matrix.multiplyMM(mvpMat, 0, p, 0, v, 0);

        // Draw route line (dashed)
        routePath.reset();
        boolean pathStarted = false;
        for (float[] pt : pts) {
            float[] s = project(pt[0], pt[1], pt[2], w, h);
            if (s != null) {
                if (!pathStarted) {
                    routePath.moveTo(s[0], s[1]);
                    pathStarted = true;
                } else {
                    routePath.lineTo(s[0], s[1]);
                }
            } else {
                pathStarted = false;
            }
        }
        canvas.drawPath(routePath, routePaint);

        // Intermediate waypoints — small cyan dots
        for (int i = 1; i < pts.length - 1; i++) {
            float[] s = project(pts[i][0], pts[i][1], pts[i][2], w, h);
            if (s != null) canvas.drawCircle(s[0], s[1], 9f, dotPaint);
        }

        // Poles at start and end
        drawPole(canvas, pts[0], startPoleStroke, startPoleFill, startLabelPaint, "СТАРТ", w, h);
        drawPole(canvas, pts[pts.length - 1], endPoleStroke, endPoleFill, endLabelPaint, "ЦЕЛЬ", w, h);
    }

    private void drawPole(Canvas canvas, float[] wp, Paint strokePaint, Paint fillPaint,
                          Paint labelPaint, String label, int w, int h) {
        float bx = wp[0], by = wp[1], bz = wp[2];
        float[] base = project(bx, by, bz, w, h);
        float[] top = project(bx, by + POLE_HEIGHT, bz, w, h);

        if (base == null && top == null) return;

        float tx, ty; // top screen coords
        float basX, basY; // base screen coords

        if (top != null) {
            tx = top[0]; ty = top[1];
        } else {
            // top is behind camera — clamp to a point above base on screen
            tx = base[0]; ty = base[1] - 120f;
        }

        if (base != null) {
            basX = base[0]; basY = base[1];
        } else {
            basX = top[0]; basY = top[1] + 120f;
        }

        // Pole shaft
        canvas.drawLine(basX, basY, tx, ty, strokePaint);

        // Cap — horizontal bar at top
        Paint capPaint = new Paint(strokePaint);
        capPaint.setStrokeWidth(POLE_WIDTH * 1.5f);
        canvas.drawLine(tx - CAP_HALF, ty, tx + CAP_HALF, ty, capPaint);

        // Small diamond at cap center
        float d = 12f;
        Path diamond = new Path();
        diamond.moveTo(tx, ty - d);
        diamond.lineTo(tx + d, ty);
        diamond.lineTo(tx, ty + d);
        diamond.lineTo(tx - d, ty);
        diamond.close();
        canvas.drawPath(diamond, fillPaint);
        Paint diamStroke = new Paint(strokePaint);
        diamStroke.setStyle(Paint.Style.STROKE);
        diamStroke.setStrokeWidth(3f);
        canvas.drawPath(diamond, diamStroke);

        // Label
        float lw = labelPaint.measureText(label);
        canvas.drawText(label, tx - lw / 2f, ty - d - 8f, labelPaint);
    }

    private float[] project(float wx, float wy, float wz, int w, int h) {
        tmpVec[0] = wx; tmpVec[1] = wy; tmpVec[2] = wz; tmpVec[3] = 1f;
        Matrix.multiplyMV(tmpOut, 0, mvpMat, 0, tmpVec, 0);
        float d = tmpOut[3];
        if (d <= 0f) return null;
        float sx = (tmpOut[0] / d) * 0.5f + 0.5f;
        float sy = (-tmpOut[1] / d) * 0.5f + 0.5f;
        return new float[]{sx * w, sy * h, d};
    }
}
