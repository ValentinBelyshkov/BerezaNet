package com.edgedetection.domain.mission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Mission {
    public final String id;
    public final String name;
    public final List<Waypoint> waypoints;
    public final List<GeoAnchor> geoAnchors;
    public final GeoFence geoFence;
    public final Double originLatitude;
    public final Double originLongitude;
    public final Double originAltitudeAmsl;
    public final int droneCount;

    public final int shotDownCount;
    public final SimulationState simState;

    public enum SimulationState { IDLE, RUNNING, PAUSED }


    public Mission(String id, String name, List<Waypoint> waypoints, List<GeoAnchor> geoAnchors,
                   GeoFence geoFence, Double originLatitude, Double originLongitude,
                   Double originAltitudeAmsl, int droneCount, int shotDownCount, SimulationState simState) {
        this.id = id;
        this.name = name;
        this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints));
        this.geoAnchors = Collections.unmodifiableList(new ArrayList<>(geoAnchors));
        this.geoFence = geoFence;
        this.originLatitude = originLatitude;
        this.originLongitude = originLongitude;
        this.originAltitudeAmsl = originAltitudeAmsl;
        this.droneCount = Math.max(1, droneCount);
        this.shotDownCount = shotDownCount;
        this.simState = simState != null ? simState : SimulationState.IDLE;
    }

    // overload для совместимости
    public Mission(String id, String name, List<Waypoint> waypoints, List<GeoAnchor> geoAnchors,
                   GeoFence geoFence, Double originLatitude, Double originLongitude,
                   Double originAltitudeAmsl) {
        this(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, 1, 0, SimulationState.IDLE);
    }

    public Mission withName(String name) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, droneCount, shotDownCount, simState);
    }

    public Mission withWaypoints(List<Waypoint> waypoints) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, droneCount, shotDownCount, simState);
    }

    public Mission withGeoAnchors(List<GeoAnchor> geoAnchors) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, droneCount, shotDownCount, simState);
    }

    public Mission withGeoFence(GeoFence geoFence) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, droneCount, shotDownCount, simState);
    }

    public Mission withOrigin(double lat, double lon, double alt) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, droneCount, shotDownCount, simState);
    }

    public Mission withDroneCount(int count) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence,
                originLatitude, originLongitude, originAltitudeAmsl,
                Math.max(1, count), shotDownCount, simState);
    }

    public double[] resolveOrigin() {
        if (originLatitude != null && originLongitude != null && originAltitudeAmsl != null) {
            return new double[]{originLatitude, originLongitude, originAltitudeAmsl};
        }
        if (!waypoints.isEmpty()) {
            Waypoint wp = waypoints.get(0);
            return new double[]{wp.latitude, wp.longitude, wp.altitudeAmsl};
        }
        if (!geoAnchors.isEmpty()) {
            GeoAnchor a = geoAnchors.get(0);
            return new double[]{a.latitude, a.longitude, a.altitudeAmsl};
        }
        return null;
    }
    public Mission withShotDownCount(int count) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, droneCount, count, simState);
    }

    public Mission withSimState(SimulationState state) {
        return new Mission(id, name, waypoints, geoAnchors, geoFence, originLatitude, originLongitude, originAltitudeAmsl, droneCount, shotDownCount, state);
    }
}