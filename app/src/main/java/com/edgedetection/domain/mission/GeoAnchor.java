package com.edgedetection.domain.mission;

public final class GeoAnchor {
    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final double altitudeAmsl;
    public final String modelUri;
    public final float scale;
    public final float headingDegrees;
    public final float pitchDegrees;
    public final float rollDegrees;
    public final boolean visible;

    public GeoAnchor(String id, String name, double latitude, double longitude,
                     double altitudeAmsl, String modelUri, float scale,
                     float headingDegrees, float pitchDegrees, float rollDegrees,
                     boolean visible) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeAmsl = altitudeAmsl;
        this.modelUri = modelUri;
        this.scale = scale;
        this.headingDegrees = headingDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.visible = visible;
    }

    public GeoAnchor withPosition(double lat, double lon, double alt) {
        return new GeoAnchor(id, name, lat, lon, alt, modelUri, scale, headingDegrees, pitchDegrees, rollDegrees, visible);
    }

    public GeoAnchor withAltitudeAmsl(double altitudeAmsl) {
        return new GeoAnchor(id, name, latitude, longitude, altitudeAmsl, modelUri, scale, headingDegrees, pitchDegrees, rollDegrees, visible);
    }

    public GeoAnchor withOrientation(float heading, float pitch, float roll) {
        return new GeoAnchor(id, name, latitude, longitude, altitudeAmsl, modelUri, scale, heading, pitch, roll, visible);
    }

    public GeoAnchor withVisibility(boolean visible) {
        return new GeoAnchor(id, name, latitude, longitude, altitudeAmsl, modelUri, scale, headingDegrees, pitchDegrees, rollDegrees, visible);
    }
}