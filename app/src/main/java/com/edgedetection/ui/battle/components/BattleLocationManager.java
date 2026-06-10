package com.edgedetection.ui.battle.components;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class BattleLocationManager {
    private static final String TAG = "BattleLocationManager";

    public interface OnLocationUpdatedListener {
        void onLocationUpdated(double lat, double lon, double alt);
    }

    private final Context context;
    private final FusedLocationProviderClient fusedLocationClient;
    private final OnLocationUpdatedListener listener;
    private LocationCallback locationCallback;

    private double userLat, userLon, userAlt;
    private boolean hasUserLocation = false;

    public BattleLocationManager(Context context, OnLocationUpdatedListener listener) {
        this.context = context;
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        this.listener = listener;
    }

    public void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest request = LocationRequest.create();
        request.setPriority(Priority.PRIORITY_HIGH_ACCURACY);
        request.setInterval(1000);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                
                hasUserLocation = true;
                userLat = loc.getLatitude();
                userLon = loc.getLongitude();
                userAlt = loc.getAltitude();
                
                Log.d(TAG, "User GPS: " + userLat + ", " + userLon + ", " + userAlt);
                listener.onLocationUpdated(userLat, userLon, userAlt);
            }
        };

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }

    public void stopLocationUpdates() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    public boolean hasUserLocation() {
        return hasUserLocation;
    }

    public double getUserLat() {
        return userLat;
    }

    public double getUserLon() {
        return userLon;
    }

    public double getUserAlt() {
        return userAlt;
    }
}
