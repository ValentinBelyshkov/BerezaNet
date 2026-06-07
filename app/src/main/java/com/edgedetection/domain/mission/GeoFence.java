package com.edgedetection.domain.mission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GeoFence {
    public final List<GeoPoint> points;
    public final double minAltitudeAmsl;
    public final double maxAltitudeAmsl;

    public GeoFence(List<GeoPoint> points, double minAltitudeAmsl, double maxAltitudeAmsl) {
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.minAltitudeAmsl = minAltitudeAmsl;
        this.maxAltitudeAmsl = maxAltitudeAmsl;
    }
}