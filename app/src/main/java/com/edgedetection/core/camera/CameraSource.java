package com.edgedetection.core.camera;

import androidx.camera.core.ImageProxy;

/**
 * Interface representing a camera source.
 */
public interface CameraSource {
    /**
     * Start the camera source.
     * @param listener Callback for receiving frames.
     */
    void start(CameraSourceListener listener);

    /**
     * Stop the camera source.
     */
    void stop();

    /**
     * Check if the camera source is currently running.
     * @return true if running, false otherwise.
     */
    boolean isRunning();

    /**
     * Set a listener for status updates.
     */
    default void setStatusListener(StatusListener listener) {}

    interface StatusListener {
        void onStatusUpdate(String message, boolean isError, boolean isRetrying);
    }

    /**
     * Callback interface for receiving frames from a CameraSource.
     */
    interface CameraSourceListener {
        /**
         * Called when a new frame is available.
         * @param image The frame as an ImageProxy.
         */
        void onFrame(androidx.camera.core.ImageProxy image);

        /**
         * Called when a new frame is available as an OpenCV Mat.
         * @param frame The frame as a Mat (RGBA).
         */
        default void onFrame(org.opencv.core.Mat frame) {}
    }
}
