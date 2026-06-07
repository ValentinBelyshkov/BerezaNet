package com.edgedetection.domain.geo;

public final class GeoUtils {
    private GeoUtils() {}
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double E2 = 2*F - F*F;

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
        double latR = Math.toRadians(refLat), lonR = Math.toRadians(refLon);
        double sLat = Math.sin(latR), cLat = Math.cos(latR);
        double sLon = Math.sin(lonR), cLon = Math.cos(lonR);
        return new double[]{
                -sLon*dx + cLon*dy,
                -sLat*cLon*dx - sLat*sLon*dy + cLat*dz,
                cLat*cLon*dx + cLat*sLon*dy + sLat*dz
        };
    }

    public static float[] enuToFilament(double[] enu) {
        return new float[]{(float)enu[0], (float)enu[2], (float)enu[1]};
    }
}