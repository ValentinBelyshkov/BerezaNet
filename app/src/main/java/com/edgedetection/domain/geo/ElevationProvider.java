package com.edgedetection.domain.geo;

public interface ElevationProvider {
    double getElevation(double latitude, double longitude);
}