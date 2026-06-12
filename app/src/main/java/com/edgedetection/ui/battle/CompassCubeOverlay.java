package com.edgedetection.ui.battle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.View;

public class CompassCubeOverlay extends View {

    private static final float CUBE_DIST = 5.0f;
    private static final float EYE_HEIGHT = 1.6f;
    private static final float BASE_HALF_SIZE_PX = 44f;

    // Filament world space: X=East, Y=Up, Z=-North
    private static final float[][] CUBE_POSITIONS = {
        {0f,          EYE_HEIGHT, -CUBE_DIST},  // North  — Blue
        {0f,          EYE_HEIGHT, +CUBE_DIST},  // South  — Red
        {+CUBE_DIST,  EYE_HEIGHT,  0f},          // East   — Yellow
        {-CUBE_DIST,  EYE_HEIGHT,  0f},          // West   — Green
    };

    private static final int[] CUBE_FILL_COLORS = {
        0xCC2196F3,  // Blue   — North
        0xCCFF1744,  // Red    — South
        0xCCFFD600,  // Yellow — East
        0xCC00C853,  // Green  — West
    };

    private static final String[] CUBE_LABELS = {"С", "Ю", "В", "З"};

    private final float[] viewMat  = new float[16];
    private final float[] projMat  = new float[16];
    private final float[] mvpMat   = new float[16];
    private final float[] tempVec  = new float[4];
    private int screenW, screenH;
    private boolean hasMatrices = false;

    private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect        = new RectF();

    public CompassCubeOverlay(Context context) {
        super(context);
        init();
    }

    public CompassCubeOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        fillPaint.setStyle(Paint.Style.FILL);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        borderPaint.setColor(Color.WHITE);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setCameraMatrices(float[] view, float[] proj, int w, int h) {
        synchronized (this) {
            System.arraycopy(view, 0, viewMat, 0, 16);
            System.arraycopy(proj, 0, projMat, 0, 16);
            screenW = w;
            screenH = h;
            hasMatrices = true;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasMatrices || screenW == 0 || screenH == 0) return;

        float[] v = new float[16];
        float[] p = new float[16];
        int w, h;
        synchronized (this) {
            System.arraycopy(viewMat, 0, v, 0, 16);
            System.arraycopy(projMat, 0, p, 0, 16);
            w = screenW;
            h = screenH;
        }
        Matrix.multiplyMM(mvpMat, 0, p, 0, v, 0);

        for (int i = 0; i < CUBE_POSITIONS.length; i++) {
            float[] screen = worldToScreen(CUBE_POSITIONS[i], w, h);
            if (screen[2] <= 0f) continue;

            float sx = screen[0];
            float sy = screen[1];

            float depth = screen[2];
            float scale = Math.max(0.4f, Math.min(3.5f, CUBE_DIST / depth));
            float half  = BASE_HALF_SIZE_PX * scale;

            fillPaint.setColor(CUBE_FILL_COLORS[i]);
            rect.set(sx - half, sy - half, sx + half, sy + half);
            canvas.drawRoundRect(rect, 10f, 10f, fillPaint);
            canvas.drawRoundRect(rect, 10f, 10f, borderPaint);

            float textSize = Math.max(14f, 26f * scale);
            textPaint.setTextSize(textSize);
            canvas.drawText(CUBE_LABELS[i], sx, sy + textSize * 0.38f, textPaint);
        }
    }

    private float[] worldToScreen(float[] world, int w, int h) {
        tempVec[0] = world[0];
        tempVec[1] = world[1];
        tempVec[2] = world[2];
        tempVec[3] = 1f;
        Matrix.multiplyMV(tempVec, 0, mvpMat, 0, tempVec, 0);
        float d = tempVec[3];
        if (d <= 0f) return new float[]{0f, 0f, -1f};
        float x = (tempVec[0] / d) * 0.5f + 0.5f;
        float y = (-tempVec[1] / d) * 0.5f + 0.5f;
        return new float[]{x * w, y * h, d};
    }
}
