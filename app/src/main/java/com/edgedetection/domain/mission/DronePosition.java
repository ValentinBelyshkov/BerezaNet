package com.edgedetection.domain.mission;

public class DronePosition {
    public final int index;
    public final double lat, lon, alt;
    public final float heading;
    public final boolean active;

    public DronePosition(int index, double lat, double lon, double alt, float heading, boolean active) {
        this.index = index;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.heading = heading;
        this.active = active;
    }
}