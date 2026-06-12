package com.edgedetection.ui.shared;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.edgedetection.domain.geo.ElevationProvider;
import com.edgedetection.domain.mission.DronePosition;
import com.edgedetection.domain.mission.GeoAnchor;
import com.edgedetection.domain.mission.Mission;
import com.edgedetection.domain.mission.MissionRepository;
import com.edgedetection.domain.mission.Waypoint;
import com.edgedetection.domain.mission.WaypointAction;
import com.edgedetection.domain.mission.persistence.AppDatabase;
import com.edgedetection.domain.mission.persistence.RoomMissionRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MissionViewModel extends AndroidViewModel {

    private final MissionRepository repository;
    private final MutableLiveData<Mission> missionData;
    private final MutableLiveData<List<Mission>> allMissions = new MutableLiveData<>(new ArrayList<>());
    private final Executor executor;

    private static final String PREFS_NAME = "mission_settings";
    private static final String KEY_LIVES = "maxLives";
    private static final String KEY_SPEED = "speedKmh";
    private static final String KEY_ALTITUDE = "altitudeMeters";
    private static final String KEY_SPAWN = "spawnIntervalSeconds";

    private static final int[] MISSION_COLORS = {
            0xFFFF0000, // Red
            0xFF00FF00, // Green
            0xFF0000FF, // Blue
            0xFFFFFF00, // Yellow
            0xFFFF00FF, // Magenta
            0xFF00FFFF, // Cyan
            0xFFFFA500, // Orange
            0xFF800080, // Purple
            0xFF008080, // Teal
            0xFFA52A2A  // Brown
    };

    public MissionViewModel(@NonNull Application application) {
        super(application);
        this.repository = new RoomMissionRepository(AppDatabase.getDatabase(application).missionDao());

        SharedPreferences prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int savedLives = prefs.getInt(KEY_LIVES, 3);
        float savedSpeed = prefs.getFloat(KEY_SPEED, 200f);
        float savedAltitude = prefs.getFloat(KEY_ALTITUDE, 100f);
        float savedSpawn = prefs.getFloat(KEY_SPAWN, 90f);
        boolean savedSound = prefs.getBoolean("sound_enabled", false);

        this.missionData = new MutableLiveData<>(new Mission(
                UUID.randomUUID().toString(), "Маршрут 1",
                new ArrayList<>(), new ArrayList<>(),
                null, null, null, null, 1, 0, Mission.SimulationState.IDLE, MISSION_COLORS[0],
                savedLives, savedSpeed, savedAltitude, savedSpawn,
                null, null, null, false, savedSound));
        this.executor = Executors.newSingleThreadExecutor();
        loadAllMissions();
    }

    public LiveData<List<Mission>> getAllMissions() {
        return allMissions;
    }

    private void loadAllMissions() {
        executor.execute(() -> {
            List<Mission> missions = repository.getAllMissions();
            allMissions.postValue(missions);
        });
    }

    // --- Realtime drone position for AR ---
    private final MutableLiveData<DronePosition> dronePosition = new MutableLiveData<>();
    private final MutableLiveData<Integer> hitDroneIndex = new MutableLiveData<>();

    public LiveData<DronePosition> getDronePosition() {
        return dronePosition;
    }

    public LiveData<Integer> getHitDroneIndex() {
        return hitDroneIndex;
    }

    public void setDronePosition(DronePosition pos) {
        dronePosition.setValue(pos);
    }
    
    public LiveData<Mission> getMissionState() {
        return missionData;
    }

    public void dispatch(MissionIntent intent) {
        if (intent instanceof MissionIntent.SetMissionName) {
            update(m -> m.withName(((MissionIntent.SetMissionName) intent).name));
        } else if (intent instanceof MissionIntent.AddWaypoint) {
            addWaypoint((MissionIntent.AddWaypoint) intent);
        } else if (intent instanceof MissionIntent.RemoveWaypoint) {
            removeWaypoint(((MissionIntent.RemoveWaypoint) intent).id);
        } else if (intent instanceof MissionIntent.MoveWaypoint) {
            moveWaypoint((MissionIntent.MoveWaypoint) intent);
        } else if (intent instanceof MissionIntent.UpdateWaypointAltitude) {
            updateAltitude((MissionIntent.UpdateWaypointAltitude) intent);
        } else if (intent instanceof MissionIntent.UpdateWaypointAction) {
            updateAction((MissionIntent.UpdateWaypointAction) intent);
        } else if (intent instanceof MissionIntent.ReorderWaypoints) {
            reorder((MissionIntent.ReorderWaypoints) intent);
        } else if (intent instanceof MissionIntent.AddGeoAnchor) {
            addAnchor((MissionIntent.AddGeoAnchor) intent);
        } else if (intent instanceof MissionIntent.RemoveGeoAnchor) {
            removeAnchor(((MissionIntent.RemoveGeoAnchor) intent).id);
        } else if (intent instanceof MissionIntent.MoveGeoAnchor) {
            moveAnchor((MissionIntent.MoveGeoAnchor) intent);
        } else if (intent instanceof MissionIntent.ClearMission) {
            clearMission();
        } else if (intent instanceof MissionIntent.SetDroneCount) {
            setDroneCount(((MissionIntent.SetDroneCount) intent).count);
        } else if (intent instanceof MissionIntent.UpdateGeoAnchorOrientation) {
            updateAnchorOrientation((MissionIntent.UpdateGeoAnchorOrientation) intent);
        } else if (intent instanceof MissionIntent.ApplyTerrainHeights) {
            applyTerrain((MissionIntent.ApplyTerrainHeights) intent);
        } else if (intent instanceof MissionIntent.StartSimulation) {
            update(m -> m.withSimState(Mission.SimulationState.RUNNING));
        } else if (intent instanceof MissionIntent.PauseSimulation) {
            update(m -> m.withSimState(Mission.SimulationState.PAUSED));
        } else if (intent instanceof MissionIntent.StopSimulation) {
            update(m -> m.withSimState(Mission.SimulationState.IDLE)
                    .withShotDownCount(0));
        } else if (intent instanceof MissionIntent.ShotDownDrone) {
            int index = ((MissionIntent.ShotDownDrone) intent).droneIndex;
            hitDroneIndex.setValue(index);
            update(m -> m.withShotDownCount(m.shotDownCount + 1));
        } else if (intent instanceof MissionIntent.LoadMission) {
            loadMission(((MissionIntent.LoadMission) intent).id);
        } else if (intent instanceof MissionIntent.DeleteMission) {
            deleteMission(((MissionIntent.DeleteMission) intent).id);
        } else if (intent instanceof MissionIntent.SetMaxLives) {
            int val = ((MissionIntent.SetMaxLives) intent).maxLives;
            getApplication().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_LIVES, val).apply();
            update(m -> m.withMaxLives(val));
        } else if (intent instanceof MissionIntent.SetSpeed) {
            float val = ((MissionIntent.SetSpeed) intent).speedKmh;
            getApplication().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, val).apply();
            update(m -> m.withSpeedKmh(val));
        } else if (intent instanceof MissionIntent.SetAltitude) {
            float val = ((MissionIntent.SetAltitude) intent).altitudeMeters;
            getApplication().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_ALTITUDE, val).apply();
            update(m -> m.withAltitudeMeters(val));
        } else if (intent instanceof MissionIntent.SetSpawnInterval) {
            float val = ((MissionIntent.SetSpawnInterval) intent).spawnIntervalSeconds;
            getApplication().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SPAWN, val).apply();
            update(m -> m.withSpawnIntervalSeconds(val));
        } else if (intent instanceof MissionIntent.SetManualUserPosition) {
            MissionIntent.SetManualUserPosition i = (MissionIntent.SetManualUserPosition) intent;
            update(m -> m.withUserPosition(i.lat, i.lon, i.altAmsl));
        } else if (intent instanceof MissionIntent.SetUseManualGps) {
            update(m -> m.withUseManualGps(((MissionIntent.SetUseManualGps) intent).use));
        } else if (intent instanceof MissionIntent.SetSoundEnabled) {
            boolean val = ((MissionIntent.SetSoundEnabled) intent).enabled;
            getApplication().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("sound_enabled", val).apply();
            update(m -> m.withSoundEnabled(val));
        }
    }

    private void loadMission(String id) {
        executor.execute(() -> {
            Mission m = repository.load(id);
            if (m != null) {
                missionData.postValue(m);
            }
        });
    }

    private void deleteMission(String id) {
        executor.execute(() -> {
            repository.delete(id);
            loadAllMissions();
            Mission current = missionData.getValue();
            if (current != null && current.id.equals(id)) {
                clearMission();
            }
        });
    }

    public void cleanupMissions() {
        executor.execute(() -> {
            List<Mission> missions = repository.getAllMissions();
            Mission current = missionData.getValue();
            boolean changed = false;
            for (Mission m : missions) {
                if (m.waypoints.size() < 2) {
                    if (current != null && m.id.equals(current.id)) {
                        continue;
                    }
                    repository.delete(m.id);
                    changed = true;
                }
            }
            if (changed) {
                loadAllMissions();
            }
        });
    }

    // --- Waypoints ---

    private void addWaypoint(MissionIntent.AddWaypoint intent) {
        String id = UUID.randomUUID().toString();
        Waypoint wp = new Waypoint(id, 0, intent.lat, intent.lon, intent.altAmsl,
                null, 5.0f, new WaypointAction.Hover(), null);

        update(m -> {
            List<Waypoint> list = new ArrayList<>(m.waypoints);
            int order = list.size();
            list.add(wp.withOrderIndex(order));

            double[] origin = m.resolveOrigin();
            if (origin == null) {
                return new Mission(m.id, m.name, list, m.geoAnchors, m.geoFence,
                        intent.lat, intent.lon, intent.altAmsl, m.droneCount, m.shotDownCount, m.simState, m.color,
                        m.maxLives, m.speedKmh, m.altitudeMeters, m.spawnIntervalSeconds,
                        m.userLatitude, m.userLongitude, m.userAltitudeAmsl, m.useManualGps, m.soundEnabled);
            }
            return m.withWaypoints(list);
        });
    }

    private void removeWaypoint(String id) {
        update(m -> {
            List<Waypoint> filtered = new ArrayList<>();
            for (Waypoint wp : m.waypoints) {
                if (!wp.id.equals(id)) filtered.add(wp);
            }
            for (int i = 0; i < filtered.size(); i++) {
                filtered.set(i, filtered.get(i).withOrderIndex(i));
            }
            return m.withWaypoints(filtered);
        });
    }

    private void moveWaypoint(MissionIntent.MoveWaypoint intent) {
        update(m -> {
            List<Waypoint> list = new ArrayList<>();
            for (Waypoint wp : m.waypoints) {
                if (wp.id.equals(intent.id)) {
                    list.add(wp.withLatitude(intent.lat).withLongitude(intent.lon));
                } else {
                    list.add(wp);
                }
            }
            return m.withWaypoints(list);
        });
    }

    private void updateAltitude(MissionIntent.UpdateWaypointAltitude intent) {
        update(m -> {
            List<Waypoint> list = new ArrayList<>();
            for (Waypoint wp : m.waypoints) {
                if (wp.id.equals(intent.id)) {
                    list.add(wp.withAltitudeAmsl(intent.altAmsl));
                } else {
                    list.add(wp);
                }
            }
            return m.withWaypoints(list);
        });
    }

    private void updateAction(MissionIntent.UpdateWaypointAction intent) {
        update(m -> {
            List<Waypoint> list = new ArrayList<>();
            for (Waypoint wp : m.waypoints) {
                if (wp.id.equals(intent.id)) {
                    list.add(wp.withAction(intent.action));
                } else {
                    list.add(wp);
                }
            }
            return m.withWaypoints(list);
        });
    }

    private void reorder(MissionIntent.ReorderWaypoints intent) {
        update(m -> {
            Map<String, Waypoint> map = new HashMap<>();
            for (Waypoint wp : m.waypoints) map.put(wp.id, wp);

            List<Waypoint> ordered = new ArrayList<>();
            for (String id : intent.orderedIds) {
                Waypoint wp = map.get(id);
                if (wp != null) ordered.add(wp);
            }
            for (int i = 0; i < ordered.size(); i++) {
                ordered.set(i, ordered.get(i).withOrderIndex(i));
            }
            return m.withWaypoints(ordered);
        });
    }

    // --- GeoAnchors ---

    private void addAnchor(MissionIntent.AddGeoAnchor intent) {
        update(m -> {
            List<GeoAnchor> list = new ArrayList<>(m.geoAnchors);
            list.add(intent.anchor);

            double[] origin = m.resolveOrigin();
            if (origin == null) {
                GeoAnchor a = intent.anchor;
                return new Mission(m.id, m.name, m.waypoints, list, m.geoFence,
                        a.latitude, a.longitude, a.altitudeAmsl, m.droneCount, m.shotDownCount, m.simState, m.color,
                        m.maxLives, m.speedKmh, m.altitudeMeters, m.spawnIntervalSeconds,
                        m.userLatitude, m.userLongitude, m.userAltitudeAmsl, m.useManualGps, m.soundEnabled);
            }
            return m.withGeoAnchors(list);
        });
    }

    private void removeAnchor(String id) {
        update(m -> {
            List<GeoAnchor> list = new ArrayList<>();
            for (GeoAnchor a : m.geoAnchors) {
                if (!a.id.equals(id)) list.add(a);
            }
            return m.withGeoAnchors(list);
        });
    }

    private void moveAnchor(MissionIntent.MoveGeoAnchor intent) {
        update(m -> {
            List<GeoAnchor> list = new ArrayList<>();
            for (GeoAnchor a : m.geoAnchors) {
                if (a.id.equals(intent.id)) {
                    list.add(a.withPosition(intent.lat, intent.lon, intent.altAmsl));
                } else {
                    list.add(a);
                }
            }
            return m.withGeoAnchors(list);
        });
    }

    private void updateAnchorOrientation(MissionIntent.UpdateGeoAnchorOrientation intent) {
        update(m -> {
            List<GeoAnchor> list = new ArrayList<>();
            for (GeoAnchor a : m.geoAnchors) {
                if (a.id.equals(intent.id)) {
                    list.add(a.withOrientation(intent.heading, intent.pitch, intent.roll));
                } else {
                    list.add(a);
                }
            }
            return m.withGeoAnchors(list);
        });
    }

    private void setDroneCount(int count) {
        update(m -> m.withDroneCount(count));
    }

    // --- Terrain ---

    private void applyTerrain(MissionIntent.ApplyTerrainHeights intent) {
        executor.execute(() -> {
            Mission current = missionData.getValue();
            if (current == null) return;

            List<Waypoint> wps = new ArrayList<>();
            for (Waypoint wp : current.waypoints) {
                double ground = intent.provider.getElevation(wp.latitude, wp.longitude);
                wps.add(wp.withAltitudeAgL(wp.altitudeAmsl - ground));
            }

            List<GeoAnchor> anchors = new ArrayList<>();
            for (GeoAnchor a : current.geoAnchors) {
                double ground = intent.provider.getElevation(a.latitude, a.longitude);
                anchors.add(a.withAltitudeAmsl(ground));
            }

            Mission updated = current.withWaypoints(wps).withGeoAnchors(anchors);
            missionData.postValue(updated);
            executor.execute(() -> {
                repository.save(updated);
                loadAllMissions();
            });
        });
    }

    // --- Utils ---

    private void update(MissionTransform transform) {
        Mission current = missionData.getValue();
        if (current == null) return;
        Mission next = transform.apply(current);
        missionData.setValue(next);
        executor.execute(() -> {
            repository.save(next);
            // Reload list if name might have changed or it's a new mission
            List<Mission> missions = repository.getAllMissions();
            allMissions.postValue(missions);
        });
    }

    private void clearMission() {
        int count = (allMissions.getValue() != null) ? allMissions.getValue().size() : 0;
        String name = "Маршрут " + (count + 1);
        int colorIndex = count % MISSION_COLORS.length;
        int color = MISSION_COLORS[colorIndex];
        Mission current = missionData.getValue();
        
        Mission next;
        if (current != null) {
            next = new Mission(UUID.randomUUID().toString(), name,
                new ArrayList<>(), new ArrayList<>(),
                current.geoFence, null, null, null, current.droneCount, 0, Mission.SimulationState.IDLE, color,
                current.maxLives, current.speedKmh, current.altitudeMeters, current.spawnIntervalSeconds,
                null, null, null, false, current.soundEnabled);
        } else {
            SharedPreferences prefs = getApplication().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int savedLives = prefs.getInt(KEY_LIVES, 3);
            float savedSpeed = prefs.getFloat(KEY_SPEED, 200f);
            float savedAltitude = prefs.getFloat(KEY_ALTITUDE, 100f);
            float savedSpawn = prefs.getFloat(KEY_SPAWN, 90f);
            boolean savedSound = prefs.getBoolean("sound_enabled", false);

            next = new Mission(UUID.randomUUID().toString(), name,
                new ArrayList<>(), new ArrayList<>(),
                null, null, null, null, 1, 0, Mission.SimulationState.IDLE, color,
                savedLives, savedSpeed, savedAltitude, savedSpawn,
                null, null, null, false, savedSound);
        }
        missionData.postValue(next);
        executor.execute(() -> {
            repository.save(next);
            loadAllMissions();
        });
    }

    public interface MissionTransform {
        Mission apply(Mission mission);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdown();
        }
    }
}
