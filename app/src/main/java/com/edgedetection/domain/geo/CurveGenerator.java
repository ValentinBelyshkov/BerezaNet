package com.edgedetection.domain.geo;

import com.edgedetection.domain.mission.Waypoint;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.List;

public class CurveGenerator {
    /**
     * Catmull-Rom spline. stepsPerSegment=20 даёт плавную кривую.
     */
    public static List<LatLng> generateCatmullRom(List<Waypoint> waypoints, int stepsPerSegment) {
        List<LatLng> result = new ArrayList<>();
        if (waypoints == null || waypoints.size() < 2) return result;

        List<LatLng> pts = new ArrayList<>();
        pts.add(new LatLng(waypoints.get(0).latitude, waypoints.get(0).longitude));
        for (Waypoint wp : waypoints) pts.add(new LatLng(wp.latitude, wp.longitude));
        pts.add(new LatLng(waypoints.get(waypoints.size() - 1).latitude,
                waypoints.get(waypoints.size() - 1).longitude));

        for (int i = 1; i < pts.size() - 2; i++) {
            LatLng p0 = pts.get(i - 1);
            LatLng p1 = pts.get(i);
            LatLng p2 = pts.get(i + 1);
            LatLng p3 = pts.get(i + 2);
            for (int s = 0; s <= stepsPerSegment; s++) {
                double t = s / (double) stepsPerSegment;
                result.add(catmullRom(p0, p1, p2, p3, t));
            }
        }
        return result;
    }

    private static LatLng catmullRom(LatLng p0, LatLng p1, LatLng p2, LatLng p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double lat = 0.5 * ((2.0 * p1.getLatitude()) +
                (-p0.getLatitude() + p2.getLatitude()) * t +
                (2.0 * p0.getLatitude() - 5.0 * p1.getLatitude() + 4.0 * p2.getLatitude() - p3.getLatitude()) * t2 +
                (-p0.getLatitude() + 3.0 * p1.getLatitude() - 3.0 * p2.getLatitude() + p3.getLatitude()) * t3);
        double lon = 0.5 * ((2.0 * p1.getLongitude()) +
                (-p0.getLongitude() + p2.getLongitude()) * t +
                (2.0 * p0.getLongitude() - 5.0 * p1.getLongitude() + 4.0 * p2.getLongitude() - p3.getLongitude()) * t2 +
                (-p0.getLongitude() + 3.0 * p1.getLongitude() - 3.0 * p2.getLongitude() + p3.getLongitude()) * t3);
        return new LatLng(lat, lon);
    }
}