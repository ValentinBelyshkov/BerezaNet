package com.edgedetection.domain.geo;

public final class EnuPosition {
    public final double east;
    public final double north;
    public final double up;

    public EnuPosition(double east, double north, double up) {
        this.east = east;
        this.north = north;
        this.up = up;
    }
}
