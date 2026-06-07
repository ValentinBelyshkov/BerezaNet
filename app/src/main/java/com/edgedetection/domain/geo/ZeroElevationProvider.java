package com.edgedetection.domain.geo;

public class ZeroElevationProvider implements ElevationProvider {
    @Override
    public double getElevation(double latitude, double longitude) {
        return 0.0;
    }
}