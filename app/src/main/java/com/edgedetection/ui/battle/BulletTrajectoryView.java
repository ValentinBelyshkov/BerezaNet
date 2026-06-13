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
    private Paint bulletPaint;
    private Paint trailPaint;
    private Paint hitPaint;
    private final List<Bullet> bullets = new ArrayList<>();
    private final float[] viewMat = new float[16];
    private final float[] projMat = new float[16];
    private final float[] mvpMat = new float[16];
    private final float[] tempVec = new float[4];
    private final float[] tempOut = new float[4];
    private int screenW, screenH;
    private boolean hasMatrices = false;

    // --- Lead point ---
    private float leadWorldX, leadWorldY, leadWorldZ;
    private boolean leadPointVisible = false;
    private final Paint leadPaint;
    private static final float LEAD_SQUARE_SIZE = 20f;

    // --- Drone collision sphere ---
    private float sphereWorldX, sphereWorldY, sphereWorldZ;
    private float sphereRadius = 1.5f;
    private boolean sphereVisible = false;

    private final Paint sphereFillNormal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sphereStrokeNormal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sphereFillHit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sphereStrokeHit = new Paint(Paint.ANTI_ALIAS_FLAG);

    {
        leadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        leadPaint.setColor(0xFFFFFF00);
        leadPaint.setStyle(Paint.Style.FILL);

        // Normal sphere: red, alpha ≈ 0.3
        sphereFillNormal.setColor(0x4DFF1100);
        sphereFillNormal.setStyle(Paint.Style.FILL);

        sphereStrokeNormal.setColor(0xAAFF2200);
        sphereStrokeNormal.setStyle(Paint.Style.STROKE);
        sphereStrokeNormal.setStrokeWidth(3f);

        // Hit flash: bright orange-red
        sphereFillHit.setColor(0x99FF4400);
        sphereFillHit.setStyle(Paint.Style.FILL);

        sphereStrokeHit.setColor(0xFFFFFFFF);
        sphereStrokeHit.setStyle(Paint.Style.STROKE);
        sphereStrokeHit.setStrokeWidth(4f);
    }

    public BulletTrajectoryView(Context context) { super(context); init(); }
    public BulletTrajectoryView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setWillNotDraw(false);
        bulletPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bulletPaint.setColor(Color.RED);
        bulletPaint.setStyle(Paint.Style.FILL);

        trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trailPaint.setColor(0xFFFFFF00);
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

    public void setDroneCollisionSphere(float wx, float wy, float wz, float radius, boolean visible) {
        synchronized (this) {
            sphereWorldX = wx;
            sphereWorldY = wy;
            sphereWorldZ = wz;
            sphereRadius = Math.max(0.1f, radius);
            sphereVisible = visible;
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

        // --- Drone collision sphere (draw FIRST so bullets appear on top) ---
        boolean showSphere;
        float sx, sy, sz, sr;
        synchronized (this) {
            showSphere = sphereVisible;
            sx = sphereWorldX; sy = sphereWorldY; sz = sphereWorldZ;
            sr = sphereRadius;
        }
        if (showSphere) {
            drawCollisionSphere(canvas, sx, sy, sz, sr, w, h);
        }

        // --- Bullets ---
        boolean anyHit = false;
        synchronized (bullets) {
            for (Bullet b : bullets) {
                if (b.hit) anyHit = true;
                if (!b.active && !b.hit) continue;

                if (b.trail.size() > 1) {
                    Path path = new Path();
                    boolean first = true;
                    for (float[] pt : b.trail) {
                        float[] s = worldToScreen(pt[0], pt[1], pt[2], w, h);
                        if (s != null) {
                            if (first) { path.moveTo(s[0], s[1]); first = false; }
                            else path.lineTo(s[0], s[1]);
                        }
                    }
                    if (!first) canvas.drawPath(path, trailPaint);
                }

                float[] s = worldToScreen(b.pos[0], b.pos[1], b.pos[2], w, h);
                if (s != null) {
                    if (b.active) {
                        canvas.drawCircle(s[0], s[1], 8f, bulletPaint);
                    } else if (b.hit) {
                        canvas.drawCircle(s[0], s[1], 18f, hitPaint);
                    }
                }
            }
        }

        // Re-draw sphere outline if there was a hit (flash effect on top)
        if (showSphere && anyHit) {
            drawCollisionSphereHitFlash(canvas, sx, sy, sz, sr, w, h);
        }

        // --- Lead point ---
        boolean showLead;
        float lx, ly, lz;
        synchronized (this) {
            showLead = leadPointVisible;
            lx = leadWorldX; ly = leadWorldY; lz = leadWorldZ;
        }
        if (showLead) {
            float[] s = worldToScreen(lx, ly, lz, w, h);
            if (s != null) {
                canvas.drawRect(s[0] - LEAD_SQUARE_SIZE, s[1] - LEAD_SQUARE_SIZE,
                                s[0] + LEAD_SQUARE_SIZE, s[1] + LEAD_SQUARE_SIZE,
                                leadPaint);
            }
        }
    }

    private void drawCollisionSphere(Canvas canvas, float cx, float cy, float cz,
                                     float radius, int w, int h) {
        float[] center = worldToScreen(cx, cy, cz, w, h);
        if (center == null) return;

        // Project a point offset by radius along X to compute screen radius
        float[] edge = worldToScreen(cx + radius, cy, cz, w, h);
        if (edge == null) {
            // Try along Y if X gives nothing
            edge = worldToScreen(cx, cy + radius, cz, w, h);
        }

        float screenR;
        if (edge != null) {
            float dx = edge[0] - center[0];
            float dy = edge[1] - center[1];
            screenR = (float) Math.sqrt(dx * dx + dy * dy);
        } else {
            // Fallback: approximate using depth
            screenR = (radius / center[2]) * w * 0.5f;
        }

        if (screenR < 4f) screenR = 4f;

        canvas.drawCircle(center[0], center[1], screenR, sphereFillNormal);
        canvas.drawCircle(center[0], center[1], screenR, sphereStrokeNormal);
    }

    private void drawCollisionSphereHitFlash(Canvas canvas, float cx, float cy, float cz,
                                              float radius, int w, int h) {
        float[] center = worldToScreen(cx, cy, cz, w, h);
        if (center == null) return;

        float[] edge = worldToScreen(cx + radius, cy, cz, w, h);
        float screenR;
        if (edge != null) {
            float dx = edge[0] - center[0];
            float dy = edge[1] - center[1];
            screenR = (float) Math.sqrt(dx * dx + dy * dy);
        } else {
            screenR = (radius / center[2]) * w * 0.5f;
        }
        if (screenR < 4f) screenR = 4f;

        canvas.drawCircle(center[0], center[1], screenR, sphereFillHit);
        canvas.drawCircle(center[0], center[1], screenR, sphereStrokeHit);
    }

    private float[] worldToScreen(float wx, float wy, float wz, int w, int h) {
        tempVec[0] = wx; tempVec[1] = wy; tempVec[2] = wz; tempVec[3] = 1f;
        Matrix.multiplyMV(tempOut, 0, mvpMat, 0, tempVec, 0);
        float d = tempOut[3];
        if (d <= 0f) return null;
        float x = (tempOut[0] / d) * 0.5f + 0.5f;
        float y = (-tempOut[1] / d) * 0.5f + 0.5f;
        return new float[]{x * w, y * h, d};
    }

    // Legacy overload used by trail loop
    private float[] worldToScreen(float[] world, int w, int h) {
        return worldToScreen(world[0], world[1], world[2], w, h);
    }
}
