package com.edgedetection.domain.mission;

/**
 * Immutable. Для «изменения» используй with-методы.
 */
public final class Waypoint {
    public final String id;
    public final int orderIndex;
    public final double latitude;
    public final double longitude;
    public final double altitudeAmsl;
    public final Double altitudeAgL; // nullable
    public final float speedMps;
    public final WaypointAction action;
    public final Float headingDegrees; // nullable

    public Waypoint(String id, int orderIndex, double latitude, double longitude,
                    double altitudeAmsl, Double altitudeAgL, float speedMps,
                    WaypointAction action, Float headingDegrees) {
        this.id = id;
        this.orderIndex = orderIndex;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeAmsl = altitudeAmsl;
        this.altitudeAgL = altitudeAgL;
        this.speedMps = speedMps;
        this.action = action;
        this.headingDegrees = headingDegrees;
    }

    public Waypoint withOrderIndex(int orderIndex) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }

    public Waypoint withLatitude(double latitude) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }

    public Waypoint withLongitude(double longitude) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }

    public Waypoint withAltitudeAmsl(double altitudeAmsl) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }

    public Waypoint withAltitudeAgL(Double altitudeAgL) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }

    public Waypoint withSpeedMps(float speedMps) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }

    public Waypoint withAction(WaypointAction action) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }

    public Waypoint withHeadingDegrees(Float headingDegrees) {
        return new Waypoint(id, orderIndex, latitude, longitude, altitudeAmsl, altitudeAgL, speedMps, action, headingDegrees);
    }
}