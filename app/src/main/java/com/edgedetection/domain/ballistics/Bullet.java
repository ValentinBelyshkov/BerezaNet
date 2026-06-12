package com.edgedetection.domain.ballistics;

import java.util.ArrayList;
import java.util.List;

public class Bullet {
    public final float[] pos = new float[3];
    public final float[] vel = new float[3];
    public final List<float[]> trail = new ArrayList<>();
    public boolean active = true;
    public boolean hit = false;
    public long spawnTime;
    public float minDistToDrone = Float.MAX_VALUE;

    // 7.62×39mm-like параметры
    public static final float SPEED_MPS = 700f; // 700 м/с
    public static final float DRAG_COEF = 0.295f;
    public static final float AREA_M2  = 0.000047f; // сечение ~7.62 мм
    public static final float MASS_KG  = 0.0079f;
    public static final float RHO      = 1.225f;   // плотность воздуха
    public static final float G        = 9.81f;
    public static final float MAX_LIFE_MS = 8000f;  // 8 секунд

    public Bullet(float x, float y, float z, float vx, float vy, float vz) {
        pos[0] = x; pos[1] = y; pos[2] = z;
        vel[0] = vx; vel[1] = vy; vel[2] = vz;
        spawnTime = System.currentTimeMillis();
        trail.add(new float[]{x, y, z});
    }

    public void update(float dt) {
        if (!active) return;
        long age = System.currentTimeMillis() - spawnTime;
        if (age > MAX_LIFE_MS) {
            active = false;
            return;
        }
        float vSq = vel[0]*vel[0] + vel[1]*vel[1] + vel[2]*vel[2];
        float v = (float)Math.sqrt(vSq);
        if (v > 0.001f) {
            // Сопротивление: a = 0.5 * rho * Cd * A * v² / m
            float dragAcc = 0.5f * RHO * DRAG_COEF * AREA_M2 * vSq / MASS_KG;
            float dragFactor = dragAcc / v;
            vel[0] -= vel[0] * dragFactor * dt;
            vel[1] -= vel[1] * dragFactor * dt;
            vel[2] -= vel[2] * dragFactor * dt;
        }
        // Гравитация
        vel[1] -= G * dt;

        pos[0] += vel[0] * dt;
        pos[1] += vel[1] * dt;
        pos[2] += vel[2] * dt;

        trail.add(new float[]{pos[0], pos[1], pos[2]});
        if (trail.size() > 120) trail.remove(0);
    }

    public float distanceTo(float x, float y, float z) {
        float dx = pos[0] - x, dy = pos[1] - y, dz = pos[2] - z;
        return (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
}