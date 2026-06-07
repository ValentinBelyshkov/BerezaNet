package com.edgedetection.domain.geo;

public class Wgs84ToEnuConverter {
    private final double originLat, originLon, originAlt;
    private final double originX, originY, originZ;

    public Wgs84ToEnuConverter(double originLat, double originLon, double originAlt) {
        this.originLat = originLat;
        this.originLon = originLon;
        this.originAlt = originAlt;
        double[] ecef = GeoUtils.llaToEcef(originLat, originLon, originAlt);
        this.originX = ecef[0];
        this.originY = ecef[1];
        this.originZ = ecef[2];
    }

    public EnuPosition toEnu(double lat, double lon, double alt) {
        double[] ecef = GeoUtils.llaToEcef(lat, lon, alt);
        double dx = ecef[0] - originX;
        double dy = ecef[1] - originY;
        double dz = ecef[2] - originZ;
        double[] enu = GeoUtils.ecefOffsetToEnu(originLat, originLon, dx, dy, dz);
        return new EnuPosition(enu[0], enu[1], enu[2]);
    }

    public double[] toWgs84(EnuPosition enu) {
        double[] offset = GeoUtils.enuToEcefOffset(originLat, originLon, enu.east, enu.north, enu.up);
        return GeoUtils.ecefToLla(originX + offset[0], originY + offset[1], originZ + offset[2]);
    }
}
