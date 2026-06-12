package com.edgedetection.ui.shared;

import com.edgedetection.domain.geo.ElevationProvider;
import com.edgedetection.domain.mission.GeoAnchor;
import com.edgedetection.domain.mission.WaypointAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class MissionIntent {
    private MissionIntent() {}

    public static final class SetMissionName extends MissionIntent {
        public final String name;
        public SetMissionName(String name) { this.name = name; }
    }

    public static final class AddWaypoint extends MissionIntent {
        public final double lat, lon, altAmsl;
        public AddWaypoint(double lat, double lon, double altAmsl) {
            this.lat = lat; this.lon = lon; this.altAmsl = altAmsl;
        }
    }

    public static final class RemoveWaypoint extends MissionIntent {
        public final String id;
        public RemoveWaypoint(String id) { this.id = id; }
    }

    public static final class MoveWaypoint extends MissionIntent {
        public final String id;
        public final double lat, lon;
        public MoveWaypoint(String id, double lat, double lon) {
            this.id = id; this.lat = lat; this.lon = lon;
        }
    }

    public static final class UpdateWaypointAltitude extends MissionIntent {
        public final String id;
        public final double altAmsl;
        public UpdateWaypointAltitude(String id, double altAmsl) {
            this.id = id; this.altAmsl = altAmsl;
        }
    }

    public static final class UpdateWaypointAction extends MissionIntent {
        public final String id;
        public final WaypointAction action;
        public UpdateWaypointAction(String id, WaypointAction action) {
            this.id = id; this.action = action;
        }
    }

    public static final class ReorderWaypoints extends MissionIntent {
        public final List<String> orderedIds;
        public ReorderWaypoints(List<String> orderedIds) {
            this.orderedIds = Collections.unmodifiableList(new ArrayList<>(orderedIds));
        }
    }

    public static final class AddGeoAnchor extends MissionIntent {
        public final GeoAnchor anchor;
        public AddGeoAnchor(GeoAnchor anchor) { this.anchor = anchor; }
    }

    public static final class RemoveGeoAnchor extends MissionIntent {
        public final String id;
        public RemoveGeoAnchor(String id) { this.id = id; }
    }

    public static final class MoveGeoAnchor extends MissionIntent {
        public final String id;
        public final double lat, lon, altAmsl;
        public MoveGeoAnchor(String id, double lat, double lon, double altAmsl) {
            this.id = id; this.lat = lat; this.lon = lon; this.altAmsl = altAmsl;
        }
    }

    public static final class UpdateGeoAnchorOrientation extends MissionIntent {
        public final String id;
        public final float heading, pitch, roll;
        public UpdateGeoAnchorOrientation(String id, float heading, float pitch, float roll) {
            this.id = id; this.heading = heading; this.pitch = pitch; this.roll = roll;
        }
    }

    public static final class ApplyTerrainHeights extends MissionIntent {
        public final ElevationProvider provider;
        public ApplyTerrainHeights(ElevationProvider provider) { this.provider = provider; }
    }

    // === Новые ===
    public static final class ClearMission extends MissionIntent {
        public ClearMission() {}
    }

    public static final class SetDroneCount extends MissionIntent {
        public final int count;
        public SetDroneCount(int count) { this.count = count; }
    }

    // === Симуляция ===
    public static final class StartSimulation extends MissionIntent {
        public StartSimulation() {}
    }

    public static final class PauseSimulation extends MissionIntent {
        public PauseSimulation() {}
    }

    public static final class StopSimulation extends MissionIntent {
        public StopSimulation() {}
    }

    public static final class ShotDownDrone extends MissionIntent {
        public final int droneIndex;
        public ShotDownDrone(int droneIndex) { this.droneIndex = droneIndex; }
    }

    public static final class LoadMission extends MissionIntent {
        public final String id;
        public LoadMission(String id) { this.id = id; }
    }

    public static final class DeleteMission extends MissionIntent {
        public final String id;
        public DeleteMission(String id) { this.id = id; }
    }

    public static final class SetMaxLives extends MissionIntent {
        public final int maxLives;
        public SetMaxLives(int maxLives) { this.maxLives = maxLives; }
    }

    public static final class SetSpeed extends MissionIntent {
        public final float speedKmh;
        public SetSpeed(float speedKmh) { this.speedKmh = speedKmh; }
    }

    public static final class SetAltitude extends MissionIntent {
        public final float altitudeMeters;
        public SetAltitude(float altitudeMeters) { this.altitudeMeters = altitudeMeters; }
    }

    public static final class SetSpawnInterval extends MissionIntent {
        public final float spawnIntervalSeconds;
        public SetSpawnInterval(float spawnIntervalSeconds) { this.spawnIntervalSeconds = spawnIntervalSeconds; }
    }

    // === Manual GPS Position ===
    public static final class SetManualUserPosition extends MissionIntent {
        public final double lat, lon, altAmsl;
        public SetManualUserPosition(double lat, double lon, double altAmsl) {
            this.lat = lat; this.lon = lon; this.altAmsl = altAmsl;
        }
    }

    public static final class SetUseManualGps extends MissionIntent {
        public final boolean use;
        public SetUseManualGps(boolean use) { this.use = use; }
    }

    public static final class SetSoundEnabled extends MissionIntent {
        public final boolean enabled;
        public SetSoundEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static final class SetTargetSize extends MissionIntent {
        public final float targetSizeM;
        public SetTargetSize(float targetSizeM) { this.targetSizeM = targetSizeM; }
    }

    public static final class SetBulletDiameter extends MissionIntent {
        public final float bulletDiameterM;
        public SetBulletDiameter(float bulletDiameterM) { this.bulletDiameterM = bulletDiameterM; }
    }
}
