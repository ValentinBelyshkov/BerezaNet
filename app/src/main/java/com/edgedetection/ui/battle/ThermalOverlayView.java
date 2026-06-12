package com.edgedetection.ui.battle;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

public class ThermalOverlayView extends View {

    private static final float HALF_FOV_H_DEG = 4.0f;
    private static final float EYE_HEIGHT = 1.6f;
    private float droneSizeM = 3.0f;

    public void setTargetSizeM(float sizeM) {
        this.droneSizeM = Math.max(0.3f, sizeM);
        postInvalidate();
    }

    private float fx, fy, fz, ux, uy, uz;
    private float droneX, droneY, droneZ;
    private float leadX, leadY, leadZ;
    private boolean leadVisible = false;
    private float droneDistanceM = 0f;
    private float bestMissDistM = -1f;
    private boolean hasData = false;

    private float[][] trajectoryPoints = null;

    private final Paint bgPaint = new Paint();
    private final Paint hotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trajectoryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint landingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint landingLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trajPath = new Path();

    public ThermalOverlayView(Context context) {
        super(context);
        init();
    }

    public ThermalOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        bgPaint.setColor(Color.BLACK);
        bgPaint.setStyle(Paint.Style.FILL);

        hotPaint.setColor(Color.WHITE);
        hotPaint.setStyle(Paint.Style.FILL);

        glowPaint.setColor(0xCCFFFFFF);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setMaskFilter(new BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL));

        detectionPaint.setColor(0xFFFFFF00);
        detectionPaint.setStyle(Paint.Style.STROKE);
        detectionPaint.setStrokeWidth(2.5f);

        leadPaint.setColor(0xAAFFFF00);
        leadPaint.setStyle(Paint.Style.STROKE);
        leadPaint.setStrokeWidth(2f);

        crosshairPaint.setColor(0xFF00FF44);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(1.5f);

        cornerPaint.setColor(0xFF00FF44);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(2.5f);
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);

        hudTextPaint.setColor(0xFF00FF44);
        hudTextPaint.setTextSize(30f);
        hudTextPaint.setTypeface(Typeface.MONOSPACE);

        hudDimPaint.setColor(0x8800FF44);
        hudDimPaint.setTextSize(22f);
        hudDimPaint.setTypeface(Typeface.MONOSPACE);

        trajectoryPaint.setColor(0xFF00CCFF);
        trajectoryPaint.setStyle(Paint.Style.STROKE);
        trajectoryPaint.setStrokeWidth(2.5f);
        trajectoryPaint.setPathEffect(new DashPathEffect(new float[]{14f, 8f}, 0));

        startPaint.setColor(0xFF00FF44);
        startPaint.setStyle(Paint.Style.FILL);

        landingPaint.setColor(0xFFFF5500);
        landingPaint.setStyle(Paint.Style.FILL);

        startLabelPaint.setColor(0xFF00FF44);
        startLabelPaint.setTextSize(24f);
        startLabelPaint.setTypeface(Typeface.MONOSPACE);

        landingLabelPaint.setColor(0xFFFF5500);
        landingLabelPaint.setTextSize(24f);
        landingLabelPaint.setTypeface(Typeface.MONOSPACE);
    }

    public void setTrajectory(float[][] points) {
        this.trajectoryPoints = points;
        postInvalidate();
    }

    public void update(float fx, float fy, float fz,
                       float ux, float uy, float uz,
                       float droneX, float droneY, float droneZ,
                       float leadX, float leadY, float leadZ,
                       boolean leadVisible,
                       float distanceM, float bestMissM) {
        this.fx = fx; this.fy = fy; this.fz = fz;
        this.ux = ux; this.uy = uy; this.uz = uz;
        this.droneX = droneX; this.droneY = droneY; this.droneZ = droneZ;
        this.leadX = leadX; this.leadY = leadY; this.leadZ = leadZ;
        this.leadVisible = leadVisible;
        this.droneDistanceM = distanceM;
        this.bestMissDistM = bestMissM;
        this.hasData = true;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        canvas.drawRect(0, 0, w, h, bgPaint);

        float tanHalfH = (float) Math.tan(Math.toRadians(HALF_FOV_H_DEG));
        float aspect = (float) w / h;
        float tanHalfV = tanHalfH / aspect;

        if (hasData) {
            float[] droneScreen = project(droneX, droneY, droneZ, tanHalfH, tanHalfV, w, h);
            if (droneScreen != null) {
                float zCam = droneScreen[2];
                float pixelRadius = (droneSizeM / 2f) / (zCam * tanHalfH) * (w / 2f);
                pixelRadius = Math.max(4f, pixelRadius);

                canvas.drawCircle(droneScreen[0], droneScreen[1], pixelRadius * 2.5f, glowPaint);
                canvas.drawCircle(droneScreen[0], droneScreen[1], pixelRadius, hotPaint);

                float squareHalf = Math.max(pixelRadius * 1.6f, 20f);
                canvas.drawRect(
                        droneScreen[0] - squareHalf, droneScreen[1] - squareHalf,
                        droneScreen[0] + squareHalf, droneScreen[1] + squareHalf,
                        detectionPaint);
            }

            if (leadVisible) {
                float[] leadScreen = project(leadX, leadY, leadZ, tanHalfH, tanHalfV, w, h);
                if (leadScreen != null) {
                    float lh = 18f;
                    canvas.drawRect(
                            leadScreen[0] - lh, leadScreen[1] - lh,
                            leadScreen[0] + lh, leadScreen[1] + lh,
                            leadPaint);
                }
            }
        }

        drawTrajectory(canvas, tanHalfH, tanHalfV, w, h);
        drawCrosshair(canvas, w, h);
        drawCornerBrackets(canvas, w, h);
        drawHud(canvas, w, h);
    }

    private void drawTrajectory(Canvas canvas, float tanHalfH, float tanHalfV, int w, int h) {
        float[][] pts = trajectoryPoints;
        if (pts == null || pts.length < 2) return;

        // --- Draw trajectory path (cyan dashed) ---
        trajPath.reset();
        boolean pathStarted = false;
        for (float[] pt : pts) {
            float[] sc = project(pt[0], pt[1], pt[2], tanHalfH, tanHalfV, w, h);
            if (sc == null) {
                pathStarted = false;
                continue;
            }
            if (!pathStarted) {
                trajPath.moveTo(sc[0], sc[1]);
                pathStarted = true;
            } else {
                trajPath.lineTo(sc[0], sc[1]);
            }
        }
        canvas.drawPath(trajPath, trajectoryPaint);

        // --- Draw START marker (bright green circle + label) ---
        float[] sp = project(pts[0][0], pts[0][1], pts[0][2], tanHalfH, tanHalfV, w, h);
        if (sp != null) {
            float r = dp(9);
            canvas.drawCircle(sp[0], sp[1], r, startPaint);
            // triangle "up" marker on top
            Path tri = new Path();
            tri.moveTo(sp[0], sp[1] - r - dp(8));
            tri.lineTo(sp[0] - dp(6), sp[1] - r);
            tri.lineTo(sp[0] + dp(6), sp[1] - r);
            tri.close();
            canvas.drawPath(tri, startPaint);
            canvas.drawText("СТАРТ", sp[0] + r + dp(6), sp[1] + dp(8), startLabelPaint);
        }

        // --- Draw LANDING marker (orange circle + X + label) ---
        float[] lp2 = project(pts[pts.length - 1][0], pts[pts.length - 1][1], pts[pts.length - 1][2], tanHalfH, tanHalfV, w, h);
        if (lp2 != null) {
            float r = dp(9);
            canvas.drawCircle(lp2[0], lp2[1], r, landingPaint);
            Paint xp = new Paint(landingPaint);
            xp.setStyle(Paint.Style.STROKE);
            xp.setStrokeWidth(3f);
            float d = dp(6);
            canvas.drawLine(lp2[0] - d, lp2[1] - d, lp2[0] + d, lp2[1] + d, xp);
            canvas.drawLine(lp2[0] + d, lp2[1] - d, lp2[0] - d, lp2[1] + d, xp);
            canvas.drawText("ПОСАДКА", lp2[0] + r + dp(6), lp2[1] + dp(8), landingLabelPaint);
        }

        // --- Draw intermediate waypoints (small cyan dots) ---
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(0xAA00CCFF);
        dotPaint.setStyle(Paint.Style.FILL);
        for (int i = 1; i < pts.length - 1; i++) {
            float[] sc = project(pts[i][0], pts[i][1], pts[i][2], tanHalfH, tanHalfV, w, h);
            if (sc != null) {
                canvas.drawCircle(sc[0], sc[1], dp(4), dotPaint);
            }
        }
    }

    private void drawCrosshair(Canvas canvas, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        float gap = dp(20);
        float len = dp(36);

        canvas.drawLine(cx - gap - len, cy, cx - gap, cy, crosshairPaint);
        canvas.drawLine(cx + gap, cy, cx + gap + len, cy, crosshairPaint);
        canvas.drawLine(cx, cy - gap - len, cx, cy - gap, crosshairPaint);
        canvas.drawLine(cx, cy + gap, cx, cy + gap + len, crosshairPaint);
        canvas.drawCircle(cx, cy, dp(3), crosshairPaint);
    }

    private void drawCornerBrackets(Canvas canvas, int w, int h) {
        float m = dp(40);
        float len = dp(28);
        drawCorner(canvas, m, m, len, 1, 1);
        drawCorner(canvas, w - m, m, len, -1, 1);
        drawCorner(canvas, m, h - m, len, 1, -1);
        drawCorner(canvas, w - m, h - m, len, -1, -1);
    }

    private void drawCorner(Canvas canvas, float x, float y, float len, float sx, float sy) {
        canvas.drawLine(x, y, x + sx * len, y, cornerPaint);
        canvas.drawLine(x, y, x, y + sy * len, cornerPaint);
    }

    private void drawHud(Canvas canvas, int w, int h) {
        float by = h - dp(14);

        canvas.drawText("THERMAL  FOV 8°", dp(14), by, hudDimPaint);

        if (hasData && droneDistanceM > 0) {
            String dist = String.format(java.util.Locale.US, "DST %5.0f m", droneDistanceM);
            float tx = w / 2f - hudTextPaint.measureText(dist) / 2f;
            canvas.drawText(dist, tx, by, hudTextPaint);
        }

        if (bestMissDistM >= 0) {
            String miss = String.format(java.util.Locale.US, "MISS %.0f m", bestMissDistM);
            canvas.drawText(miss, w - hudTextPaint.measureText(miss) - dp(14), by, hudTextPaint);
        }

        canvas.drawText("THERMAL", dp(14), dp(48), hudDimPaint);
        canvas.drawText("8.0°", w - hudDimPaint.measureText("8.0°") - dp(14), dp(48), hudDimPaint);
    }

    private float[] project(float worldX, float worldY, float worldZ,
                             float tanHalfH, float tanHalfV, int w, int h) {
        float rx = fy * uz - fz * uy;
        float ry = fz * ux - fx * uz;
        float rz = fx * uy - fy * ux;

        float dx = worldX;
        float dy = worldY - EYE_HEIGHT;
        float dz = worldZ;

        float xCam = dx * rx + dy * ry + dz * rz;
        float yCam = dx * ux + dy * uy + dz * uz;
        float zCam = dx * fx + dy * fy + dz * fz;

        if (zCam < 0.1f) return null;

        float ndcX = xCam / (zCam * tanHalfH);
        float ndcY = yCam / (zCam * tanHalfV);

        float scrX = (ndcX * 0.5f + 0.5f) * w;
        float scrY = (0.5f - ndcY * 0.5f) * h;

        return new float[]{scrX, scrY, zCam};
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
