package com.edgedetection.core.camera;

import androidx.camera.core.ImageProxy;

import org.opencv.core.Mat;

public interface CameraSource {

    void start(CameraSourceListener listener);

    void stop();

    boolean isRunning();

    interface CameraSourceListener {

        void onFrame(ImageProxy image);

        default void onFrameMat(Mat rgba, long timestampNs) {}
    }
}
