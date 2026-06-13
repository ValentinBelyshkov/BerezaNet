package com.edgedetection.ui.battle.components;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;
import androidx.fragment.app.FragmentActivity;

import com.edgedetection.domain.ballistics.Bullet;
import com.edgedetection.ui.shared.MissionIntent;
import com.edgedetection.ui.shared.MissionViewModel;

import java.util.ArrayList;
import java.util.List;

public class BattleBallisticsManager {
    private static final String TAG = "BattleBallisticsManager";
    private static final float EYE_HEIGHT = 1.6f;

    private final List<Bullet> bullets = new ArrayList<>();
    private final Context context;
    private final FragmentActivity activity;
    private float bestMissDistance = Float.MAX_VALUE;
    private Runnable onHitCallback;
    private float mBulletRadius = 0.15f;
    private float mTargetRadius = -1f;

    public void setBulletDiameterM(float diameterM) {
        mBulletRadius = diameterM / 2f;
    }

    public void setTargetRadiusM(float radiusM) {
        mTargetRadius = radiusM;
    }

    public BattleBallisticsManager(Context context, FragmentActivity activity) {
        this.context = context;
        this.activity = activity;
    }

    public void setOnHitCallback(Runnable callback) {
        this.onHitCallback = callback;
    }

    public void fireBullet(float camForwardX, float camForwardY, float camForwardZ) {
        float speed = Bullet.SPEED_MPS;
        float vx = camForwardX * speed;
        float vy = camForwardY * speed;
        float vz = camForwardZ * speed;

        // Выстрел из центра экрана = из позиции камеры + 0.5м вперёд
        float sx = camForwardX * 0.5f;
        float sy = EYE_HEIGHT + camForwardY * 0.5f;
        float sz = camForwardZ * 0.5f;

        Bullet b = new Bullet(sx, sy, sz, vx, vy, vz);
        bullets.add(b);
        Log.i(TAG, "FIRE! count=" + bullets.size());
    }

    public void updateBullets(float dt, boolean simulationActive,
                               float lastDroneX, float lastDroneY, float lastDroneZ,
                               float droneRadius, int currentDroneIndex) {
        if (bullets.isEmpty()) return;
        List<Bullet> toRemove = new ArrayList<>();

        float effectiveTarget = mTargetRadius > 0 ? mTargetRadius : droneRadius;
        // Комбинированный радиус: сфера дрона + радиус пули (CCD корректный)
        float combinedR = effectiveTarget + mBulletRadius;

        for (Bullet b : bullets) {
            // update() сохраняет prevPos ДО перемещения, затем двигает пулю
            b.update(dt);

            if (b.active && simulationActive) {

                // ── CCD: Swept-sphere collision ─────────────────────────────────────
                // Проверяем, проходит ли ОТРЕЗОК [prevPos → pos] ближе чем combinedR
                // к центру дрона. Это решает проблему туннелирования при скорости
                // 700 м/с (за кадр 60 Гц пуля пролетает ~12 м, дрон = 1 м).
                //
                // Математика:
                //   w = prevPos - D               (вектор от дрона к начальной точке)
                //   v = pos - prevPos             (вектор перемещения за кадр)
                //   t* = clamp(-dot(w,v)/dot(v,v), 0, 1)  — ближайшая точка на отрезке
                //   d_min = |w + t*·v|            — минимальное расстояние
                //   hit ⟺  d_min < combinedR
                // ───────────────────────────────────────────────────────────────────
                float wx = b.prevPos[0] - lastDroneX;
                float wy = b.prevPos[1] - lastDroneY;
                float wz = b.prevPos[2] - lastDroneZ;

                float vx = b.pos[0] - b.prevPos[0];
                float vy = b.pos[1] - b.prevPos[1];
                float vz = b.pos[2] - b.prevPos[2];

                float vLen2 = vx*vx + vy*vy + vz*vz;

                float minDist;
                if (vLen2 < 1e-10f) {
                    // Пуля почти не двигалась — обычная точечная проверка
                    minDist = (float) Math.sqrt(wx*wx + wy*wy + wz*wz);
                } else {
                    float tStar = -(wx*vx + wy*vy + wz*vz) / vLen2;
                    tStar = Math.max(0f, Math.min(1f, tStar));
                    float cx = wx + tStar * vx;
                    float cy = wy + tStar * vy;
                    float cz = wz + tStar * vz;
                    minDist = (float) Math.sqrt(cx*cx + cy*cy + cz*cz);
                }

                // Текущее расстояние для отслеживания промахов
                float curDist = b.distanceTo(lastDroneX, lastDroneY, lastDroneZ);
                float trackDist = Math.min(minDist, curDist);
                if (trackDist < b.minDistToDrone) b.minDistToDrone = trackDist;

                Log.d(TAG, "Bullet swept minDist=" + String.format("%.3f", minDist)
                        + "m  combinedR=" + String.format("%.3f", combinedR)
                        + "m  stepLen=" + String.format("%.2f", (float)Math.sqrt(vLen2)) + "m");

                if (minDist < combinedR) {
                    b.active = false;
                    b.hit = true;
                    Log.i(TAG, ">>> HIT DRONE! swept minDist=" + String.format("%.3f", minDist)
                            + "m  combinedR=" + String.format("%.3f", combinedR) + "m"
                            + "  stepLen=" + String.format("%.2f", (float)Math.sqrt(vLen2)) + "m");
                    Toast.makeText(context, "Попадание!", Toast.LENGTH_SHORT).show();
                    MissionViewModel missionVm = new ViewModelProvider(activity).get(MissionViewModel.class);
                    missionVm.dispatch(new MissionIntent.ShotDownDrone(currentDroneIndex));
                    if (onHitCallback != null) onHitCallback.run();
                }

            } else if (!b.active && !b.hit) {
                if (b.minDistToDrone < Float.MAX_VALUE) {
                    if (b.minDistToDrone < bestMissDistance) {
                        bestMissDistance = b.minDistToDrone;
                    }
                    Log.i(TAG, "MISS: " + String.format("%.2f", b.minDistToDrone)
                            + "m  |  BEST: " + String.format("%.2f", bestMissDistance) + "m");
                }
                toRemove.add(b);
            } else if (b.hit) {
                if (System.currentTimeMillis() - b.spawnTime > 2000) toRemove.add(b);
            }
        }
        bullets.removeAll(toRemove);
    }

    public List<Bullet> getBullets() {
        return bullets;
    }

    public float getBestMissDistance() {
        return bestMissDistance;
    }

    public boolean hasBestMiss() {
        return bestMissDistance < Float.MAX_VALUE;
    }

    public float getEffectiveTargetRadius(float modelRadius) {
        return mTargetRadius > 0 ? mTargetRadius : modelRadius;
    }

    public void clear() {
        bullets.clear();
    }
}
