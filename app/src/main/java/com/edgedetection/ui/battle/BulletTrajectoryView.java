package com.edgedetection.ui.battle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.View;

import com.edgedetection.domain.ballistics.Bullet;

import java.util.ArrayList;
import java.util.List;

public class BulletTrajectoryView extends View {
    private  Paint bulletPaint;
    private  Paint trailPaint;
    private  Paint hitPaint;
    private final List<Bullet> bullets = new ArrayList<>();
    private final float[] viewMat = new float[16];
    private final float[] projMat = new float[16];
    private final float[] mvpMat = new float[16];
    private final float[] tempVec = new float[4];
    private int screenW, screenH;
    private boolean hasMatrices = false;

    // --- Lead point (точка упреждения) ---
    private float leadWorldX, leadWorldY, leadWorldZ;
    private boolean leadPointVisible = false;
    private final Paint leadPaint;
    private static final float LEAD_SQUARE_SIZE = 20f;

    {
        leadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        leadPaint.setColor(0xFFFFFF00); // жёлтый заполненный
        leadPaint.setStyle(Paint.Style.FILL);
    }

    public BulletTrajectoryView(Context context) { super(context); init(); }
    public BulletTrajectoryView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setWillNotDraw(false);
        bulletPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bulletPaint.setColor(Color.RED);
        bulletPaint.setStyle(Paint.Style.FILL);

        trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trailPaint.setColor(0xFFFFFF00); // жёлтый
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(4f);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);

        hitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hitPaint.setColor(Color.GREEN);
        hitPaint.setStyle(Paint.Style.FILL);
    }

    public void setLeadPoint(float worldX, float worldY, float worldZ, boolean visible) {
        synchronized (this) {
            leadWorldX = worldX;
            leadWorldY = worldY;
            leadWorldZ = worldZ;
            leadPointVisible = visible;
        }
        invalidate();
    }

    public void setCameraMatrices(float[] view, float[] proj, int w, int h) {
        synchronized (this) {
            System.arraycopy(view, 0, viewMat, 0, 16);
            System.arraycopy(proj, 0, projMat, 0, 16);
            screenW = w;
            screenH = h;
            hasMatrices = true;
        }
    }

    public void setBullets(List<Bullet> bullets) {
        synchronized (this.bullets) {
            this.bullets.clear();
            this.bullets.addAll(bullets);
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

        synchronized (bullets) {
            for (Bullet b : bullets) {
                if (!b.active && !b.hit) continue;

                // Траектория
                if (b.trail.size() > 1) {
                    Path path = new Path();
                    boolean first = true;
                    for (float[] pt : b.trail) {
                        float[] s = worldToScreen(pt, w, h);
                        if (s[2] > 0) { // впереди камеры
                            if (first) {
                                path.moveTo(s[0], s[1]);
                                first = false;
                            } else {
                                path.lineTo(s[0], s[1]);
                            }
                        }
                    }
                    if (!first) canvas.drawPath(path, trailPaint);
                }

                // Пуля / попадание
                float[] s = worldToScreen(b.pos, w, h);
                if (s[2] > 0) {
                    if (b.active) {
                        canvas.drawCircle(s[0], s[1], 8f, bulletPaint);
                    } else if (b.hit) {
                        canvas.drawCircle(s[0], s[1], 18f, hitPaint);
                    }
                }
            }
        }

        // Точка упреждения (жёлтый квадрат)
        boolean showLead;
        float lx, ly, lz;
        synchronized (this) {
            showLead = leadPointVisible;
            lx = leadWorldX; ly = leadWorldY; lz = leadWorldZ;
        }
        if (showLead && hasMatrices && screenW > 0) {
            float[] s = worldToScreen(new float[]{lx, ly, lz}, w, h);
            if (s[2] > 0) {
                canvas.drawRect(s[0] - LEAD_SQUARE_SIZE, s[1] - LEAD_SQUARE_SIZE,
                                s[0] + LEAD_SQUARE_SIZE, s[1] + LEAD_SQUARE_SIZE,
                                leadPaint);
            }
        }
    }

    private float[] worldToScreen(float[] world, int w, int h) {
        tempVec[0] = world[0];
        tempVec[1] = world[1];
        tempVec[2] = world[2];
        tempVec[3] = 1f;
        Matrix.multiplyMV(tempVec, 0, mvpMat, 0, tempVec, 0);
        float d = tempVec[3];
        if (d <= 0) d = 0.0001f;
        float x = (tempVec[0] / d) * 0.5f + 0.5f;
        float y = (-tempVec[1] / d) * 0.5f + 0.5f;
        return new float[]{x * w, y * h, d};
    }
}