package com.edgedetection.core.camera;

import android.util.Log;

/**
 * Placeholder implementation of an External Camera Source (e.g., UVC).
 */
public class ExternalCameraSource implements CameraSource {
    private static final String TAG = "ExternalCameraSource";
    private CameraSourceListener listener;
    private boolean isRunning = false;

    @Override
    public void start(CameraSourceListener listener) {
        this.listener = listener;
        this.isRunning = true;
        Log.i(TAG, "External camera source started (placeholder)");
        // In a real implementation, we would start the USB camera feed here
        // and call listener.onFrame(image) for each frame.
    }

    @Override
    public void stop() {
        this.isRunning = false;
        this.listener = null;
        Log.i(TAG, "External camera source stopped");
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }
}
