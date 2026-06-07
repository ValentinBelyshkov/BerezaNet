package com.edgedetection.ui.shared;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.edgedetection.domain.geo.ElevationProvider;
import com.edgedetection.domain.mission.DronePosition;
import com.edgedetection.domain.mission.GeoAnchor;
import com.edgedetection.domain.mission.InMemoryMissionRepository;
import com.edgedetection.domain.mission.Mission;
import com.edgedetection.domain.mission.MissionRepository;
import com.edgedetection.domain.mission.Waypoint;
import com.edgedetection.domain.mission.WaypointAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MissionViewModel extends ViewModel {

    private final MissionRepository repository;
    private final MutableLiveData<Mission> missionData;
    private final Executor executor;

    public MissionViewModel() {
        this(new InMemoryMissionRepository(null));
    }

    // --- Realtime drone position for AR ---
    private final MutableLiveData<DronePosition> dronePosition = new MutableLiveData<>();

    public LiveData<DronePosition> getDronePosition() {
        return dronePosition;
    }

    public void setDronePosition(DronePosition pos) {
        dronePosition.setValue(pos);
    }
    public MissionViewModel(MissionRepository repository) {
        this.repository = repository;
        this.missionData = new MutableLiveData<>(new Mission(
                UUID.randomUUID().toString(), "Unnamed Mission",
                Collections.emptyList(), Collections.emptyList(),
                null, null, null, null));
        this.executor = Executors.newSingleThreadExecutor();
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
            update(m -> m.withShotDownCount(m.shotDownCount + 1));
        }
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
                        intent.lat, intent.lon, intent.altAmsl);
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
                        a.latitude, a.longitude, a.altitudeAmsl);
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
    private void clearMission() {
        update(m -> new Mission(m.id, m.name,
                Collections.emptyList(), Collections.emptyList(),
                m.geoFence, null, null, null, m.droneCount,m.shotDownCount, m.simState));
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
            repository.save(updated);
        });
    }

    // --- Utils ---

    private void update(MissionTransform transform) {
        Mission current = missionData.getValue();
        if (current == null) return;
        Mission next = transform.apply(current);
        missionData.setValue(next);
        repository.save(next);
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