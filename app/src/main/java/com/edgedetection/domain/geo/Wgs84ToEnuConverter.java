package com.edgedetection.domain.geo;

public class Wgs84ToEnuConverter {
    private static final double A = 6_378_137.0;
    private static final double E2 = 0.00669437999013;

    private final double originX, originY, originZ;
    private final double lat0Rad, lon0Rad;

    public Wgs84ToEnuConverter(double originLat, double originLon, double originAlt) {
        this.lat0Rad = Math.toRadians(originLat);
        this.lon0Rad = Math.toRadians(originLon);
        double[] ecef = wgs84ToEcef(originLat, originLon, originAlt);
        this.originX = ecef[0];
        this.originY = ecef[1];
        this.originZ = ecef[2];
    }

    public EnuPosition toEnu(double lat, double lon, double alt) {
        double[] ecef = wgs84ToEcef(lat, lon, alt);
        double dx = ecef[0] - originX;
        double dy = ecef[1] - originY;
        double dz = ecef[2] - originZ;

        double east  = -Math.sin(lon0Rad) * dx + Math.cos(lon0Rad) * dy;
        double north = -Math.sin(lat0Rad) * Math.cos(lon0Rad) * dx
                - Math.sin(lat0Rad) * Math.sin(lon0Rad) * dy
                + Math.cos(lat0Rad) * dz;
        double up    =  Math.cos(lat0Rad) * Math.cos(lon0Rad) * dx
                + Math.cos(lat0Rad) * Math.sin(lon0Rad) * dy
                + Math.sin(lat0Rad) * dz;

        return new EnuPosition(east, north, up);
    }

    public double[] toWgs84(EnuPosition enu) {
        double dx = -Math.sin(lon0Rad) * enu.east
                - Math.sin(lat0Rad) * Math.cos(lon0Rad) * enu.north
                + Math.cos(lat0Rad) * Math.cos(lon0Rad) * enu.up;
        double dy =  Math.cos(lon0Rad) * enu.east
                - Math.sin(lat0Rad) * Math.sin(lon0Rad) * enu.north
                + Math.cos(lat0Rad) * Math.sin(lon0Rad) * enu.up;
        double dz =  Math.cos(lat0Rad) * enu.north
                + Math.sin(lat0Rad) * enu.up;

        return ecefToWgs84(originX + dx, originY + dy, originZ + dz);
    }

    private static double[] wgs84ToEcef(double lat, double lon, double alt) {
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double n = A / Math.sqrt(1 - E2 * Math.sin(latRad) * Math.sin(latRad));
        double x = (n + alt) * Math.cos(latRad) * Math.cos(lonRad);
        double y = (n + alt) * Math.cos(latRad) * Math.sin(lonRad);
        double z = (n * (1 - E2) + alt) * Math.sin(latRad);
        return new double[]{x, y, z};
    }

    private static double[] ecefToWgs84(double x, double y, double z) {
        double lon = Math.atan2(y, x);
        double p = Math.sqrt(x * x + y * y);
        double theta = Math.atan2(z * A, p * A * (1 - E2));
        double lat = Math.atan2(z + (A * E2 / (1 - E2)) * Math.sin(theta) * Math.sin(theta) * Math.sin(theta),
                p - A * E2 * Math.cos(theta) * Math.cos(theta) * Math.cos(theta));
        double n = A / Math.sqrt(1 - E2 * Math.sin(lat) * Math.sin(lat));
        double alt = p / Math.cos(lat) - n;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), alt};
    }
}

