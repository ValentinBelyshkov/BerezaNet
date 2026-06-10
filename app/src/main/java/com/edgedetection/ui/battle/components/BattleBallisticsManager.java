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

    public BattleBallisticsManager(Context context, FragmentActivity activity) {
        this.context = context;
        this.activity = activity;
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

    public void updateBullets(float dt, boolean simulationActive, float lastDroneX, float lastDroneY, float lastDroneZ, float droneRadius, int currentDroneIndex) {
        if (bullets.isEmpty()) return;
        List<Bullet> toRemove = new ArrayList<>();

        for (Bullet b : bullets) {
            b.update(dt);
            if (b.active && simulationActive) {
                float d = b.distanceTo(lastDroneX, lastDroneY, lastDroneZ);
                if (d < droneRadius + 0.05f) {
                    b.active = false;
                    b.hit = true;
                    Log.i(TAG, ">>> HIT DRONE! dist=" + d + "m");
                    Toast.makeText(context, "Попадание!", Toast.LENGTH_SHORT).show();
                    
                    MissionViewModel missionVm = new ViewModelProvider(activity).get(MissionViewModel.class);
                    missionVm.dispatch(new MissionIntent.ShotDownDrone(currentDroneIndex));
                }
            } else if (!b.hit) {
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

    public void clear() {
        bullets.clear();
    }
}
