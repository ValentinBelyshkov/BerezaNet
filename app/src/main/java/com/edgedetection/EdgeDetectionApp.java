package com.edgedetection;

import android.app.Application;
import android.util.Log;

import org.opencv.android.OpenCVLoader;

public class EdgeDetectionApp extends Application {
    private static final String TAG = "EdgeDetectionApp";

    @Override
    public void onCreate() {
        super.onCreate();
        initOpenCV();
    }

    private void initOpenCV() {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed!");
        } else {
            Log.i(TAG, "OpenCV initialized successfully");
        }
    }
}