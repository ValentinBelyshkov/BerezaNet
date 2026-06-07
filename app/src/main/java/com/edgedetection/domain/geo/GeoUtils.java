package com.edgedetection.domain.geo;

public final class GeoUtils {
    private GeoUtils() {}
    public static final double A = 6378137.0;
    public static final double F = 1.0 / 298.257223563;
    public static final double E2 = 2*F - F*F;

    public static double[] llaToEcef(double lat, double lon, double alt) {
        double latR = Math.toRadians(lat), lonR = Math.toRadians(lon);
        double sLat = Math.sin(latR), cLat = Math.cos(latR);
        double sLon = Math.sin(lonR), cLon = Math.cos(lonR);
        double N = A / Math.sqrt(1 - E2 * sLat * sLat);
        return new double[]{
                (N + alt) * cLat * cLon,
                (N + alt) * cLat * sLon,
                (N * (1 - E2) + alt) * sLat
        };
    }

    public static double[] ecefToEnu(double refLat, double refLon, double refAlt,
                                     double tgtLat, double tgtLon, double tgtAlt) {
        double[] r = llaToEcef(refLat, refLon, refAlt);
        double[] t = llaToEcef(tgtLat, tgtLon, tgtAlt);
        double dx = t[0]-r[0], dy = t[1]-r[1], dz = t[2]-r[2];
        return ecefOffsetToEnu(refLat, refLon, dx, dy, dz);
    }

    public static double[] ecefOffsetToEnu(double refLat, double refLon, double dx, double dy, double dz) {
        double latR = Math.toRadians(refLat), lonR = Math.toRadians(refLon);
        double sLat = Math.sin(latR), cLat = Math.cos(latR);
        double sLon = Math.sin(lonR), cLon = Math.cos(lonR);
        return new double[]{
                -sLon*dx + cLon*dy,
                -sLat*cLon*dx - sLat*sLon*dy + cLat*dz,
                cLat*cLon*dx + cLat*sLon*dy + sLat*dz
        };
    }

    public static double[] enuToEcefOffset(double refLat, double refLon, double east, double north, double up) {
        double latR = Math.toRadians(refLat), lonR = Math.toRadians(refLon);
        double sLat = Math.sin(latR), cLat = Math.cos(latR);
        double sLon = Math.sin(lonR), cLon = Math.cos(lonR);

        double dx = -sLon * east - sLat * cLon * north + cLat * cLon * up;
        double dy =  cLon * east - sLat * sLon * north + cLat * sLon * up;
        double dz =  cLat * north + sLat * up;
        return new double[]{dx, dy, dz};
    }

    public static double[] ecefToLla(double x, double y, double z) {
        double lon = Math.atan2(y, x);
        double p = Math.sqrt(x * x + y * y);
        double theta = Math.atan2(z * A, p * A * (1 - E2));
        double lat = Math.atan2(z + (A * E2 / (1 - E2)) * Math.pow(Math.sin(theta), 3),
                p - A * E2 * Math.pow(Math.cos(theta), 3));
        double n = A / Math.sqrt(1 - E2 * Math.sin(lat) * Math.sin(lat));
        double alt = p / Math.cos(lat) - n;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), alt};
    }

    public static float[] enuToFilament(double[] enu) {
        return new float[]{(float)enu[0], (float)enu[2], (float)enu[1]};
    }
}
