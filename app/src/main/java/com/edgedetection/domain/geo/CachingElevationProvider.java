package com.edgedetection.domain.geo;

import android.util.LruCache;

import java.util.Locale;

public class CachingElevationProvider implements ElevationProvider {
    private final ElevationProvider delegate;
    private final LruCache<String, Double> cache;

    public CachingElevationProvider(ElevationProvider delegate, int maxSize) {
        this.delegate = delegate;
        this.cache = new LruCache<>(maxSize);
    }

    @Override
    public double getElevation(double latitude, double longitude) {
        String key = String.format(Locale.US, "%.6f,%.6f", latitude, longitude);
        Double cached = cache.get(key);
        if (cached != null) return cached;
        double value = delegate.getElevation(latitude, longitude);
        cache.put(key, value);
        return value;
    }
}
