package com.edgedetection.ui.battle.components;

import android.content.Context;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.edgedetection.EdgeDetector;
import com.edgedetection.jni.VITTracker;
import com.edgedetection.opengl.EdgeDetectionGLView;
import com.edgedetection.ui.battle.BattleViewModel;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;

import java.nio.ByteBuffer;

public class BattleFrameProcessor {
    private static final String TAG = "BattleFrameProcessor";
    private static final float T_FLIGHT_SEC = 2.0f;

    private final VITTracker vitTracker;
    private final BattleViewModel viewModel;
    private final EdgeDetectionGLView glView;
    private volatile VITTracker.TargetState lastTargetState = VITTracker.TargetState.EMPTY;
    private long lastFrameTime = 0;

    public BattleFrameProcessor(Context context, BattleViewModel viewModel, EdgeDetectionGLView glView, float targetWidthM, float targetLengthM) {
        this.viewModel = viewModel;
        this.glView = glView;
        this.vitTracker = new VITTracker();
        boolean vitOk = vitTracker.init(context, targetWidthM, targetLengthM);
        Log.i(TAG, "VIT Tracker init: " + vitOk);
    }

    public void processFrameMat(Mat rgba, long frameTimestampNs, float lastGyroX, float lastGyroY, float lastGyroZ, long lastGyroTimestampNs, float pitch, float yaw, float roll) {
        try {
            int width = rgba.width();
            int height = rgba.height();

            if (vitTracker != null && VITTracker.isLibraryLoaded() && lastGyroTimestampNs > 0) {
                int bufSize = width * height * 4;
                ByteBuffer rgbaBuffer = ByteBuffer.allocateDirect(bufSize);
                byte[] rgbaBytes = new byte[bufSize];
                rgba.get(0, 0, rgbaBytes);
                rgbaBuffer.put(rgbaBytes);
                rgbaBuffer.position(0);
                lastTargetState = vitTracker.processFrame(
                        rgbaBuffer, width, height,
                        frameTimestampNs,
                        lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs,
                        T_FLIGHT_SEC,
                        pitch, yaw, roll
                );
            }

            long currentTime = System.currentTimeMillis();
            if (lastFrameTime != 0) {
                double fps = 1000.0 / (currentTime - lastFrameTime);
                viewModel.setFps(fps);
            }
            lastFrameTime = currentTime;

            Mat edges = viewModel.getEdges();
            if (edges == null || edges.width() != width || edges.height() != height) {
                viewModel.initMats(width, height);
                edges = viewModel.getEdges();
            }

            Boolean edgeEnabled = viewModel.isEdgeDetectionEnabled().getValue();
            if (edgeEnabled == null) edgeEnabled = false;

            Mat finalMat = (edgeEnabled && EdgeDetector.isLibraryLoaded() && edges != null) ? edges : rgba;

            if (edgeEnabled && EdgeDetector.isLibraryLoaded() && edges != null) {
                VITTracker.TargetState ts = lastTargetState;
                EdgeDetector.detectEdgesWithReticle(
                        rgba.getNativeObjAddr(),
                        edges.getNativeObjAddr(),
                        50, 150, 5,
                        ts.detected ? Math.round(ts.bboxX + ts.bboxW / 2f) : 0,
                        ts.detected ? Math.round(ts.bboxY + ts.bboxH / 2f) : 0,
                        ts.detected,
                        false
                );
            }

            drawCenterRedDot(finalMat);

            if (vitTracker != null) {
                vitTracker.drawOverlay(finalMat, lastTargetState, pitch, yaw, roll, lastGyroX, lastGyroY, lastGyroZ);
            }

            if (glView != null) glView.updateFrame(finalMat);

            rgba.release();
        } catch (Exception e) {
            Log.e(TAG, "processFrameMat error: " + e.getMessage(), e);
        }
    }

    public void processFrame(ImageProxy image, float lastGyroX, float lastGyroY, float lastGyroZ, long lastGyroTimestampNs, float pitch, float yaw, float roll) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            long frameTimestampNs = image.getImageInfo().getTimestamp();

            Mat rgba;
            if (image.getPlanes().length == 1) {
                rgba = new Mat(height, width, CvType.CV_8UC4);
                ImageProxy.PlaneProxy plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int rowStride = plane.getRowStride();
                int pixelStride = plane.getPixelStride();

                if (rowStride == width * 4 && pixelStride == 4 && buffer.remaining() >= width * height * 4) {
                    byte[] bytes = new byte[width * height * 4];
                    int bufferOriginalPosition = buffer.position();
                    buffer.get(bytes);
                    rgba.put(0, 0, bytes);
                    buffer.position(bufferOriginalPosition); // restore
                } else {
                    byte[] rowBytes = new byte[width * 4];
                    int bufferOriginalPosition = buffer.position();
                    for (int row = 0; row < height; row++) {
                        buffer.position(bufferOriginalPosition + row * rowStride);
                        if (pixelStride == 4) {
                            buffer.get(rowBytes);
                            rgba.put(row, 0, rowBytes);
                        } else {
                            for (int col = 0; col < width; col++) {
                                int offset = col * pixelStride;
                                rowBytes[col * 4] = buffer.get(buffer.position() + offset);
                                rowBytes[col * 4 + 1] = buffer.get(buffer.position() + offset + 1);
                                rowBytes[col * 4 + 2] = buffer.get(buffer.position() + offset + 2);
                                rowBytes[col * 4 + 3] = buffer.get(buffer.position() + offset + 3);
                            }
                            rgba.put(row, 0, rowBytes);
                        }
                    }
                    buffer.position(bufferOriginalPosition); // restore position
                }
            } else {
                // Convert YUV_420_888 to RGBA using OpenCV
                byte[] yuvBytes = new byte[image.getPlanes()[0].getBuffer().remaining()
                        + image.getPlanes()[1].getBuffer().remaining()
                        + image.getPlanes()[2].getBuffer().remaining()];

                int offset = 0;
                for (int i = 0; i < 3; i++) {
                    ByteBuffer buffer = image.getPlanes()[i].getBuffer();
                    byte[] planeData = new byte[buffer.remaining()];
                    buffer.get(planeData);
                    System.arraycopy(planeData, 0, yuvBytes, offset, planeData.length);
                    offset += planeData.length;
                }

                Mat yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
                yuvMat.put(0, 0, yuvBytes);

                rgba = new Mat(height, width, CvType.CV_8UC4);
                org.opencv.imgproc.Imgproc.cvtColor(yuvMat, rgba, org.opencv.imgproc.Imgproc.COLOR_YUV2RGBA_NV21);

                yuvMat.release();
            }

            // ======== VIT Tracker processing ========
            if (vitTracker != null && VITTracker.isLibraryLoaded() && lastGyroTimestampNs > 0) {
                int bufSize = rgba.width() * rgba.height() * 4;
                ByteBuffer rgbaBuffer = ByteBuffer.allocateDirect(bufSize);
                byte[] rgbaBytes = new byte[bufSize];
                rgba.get(0, 0, rgbaBytes);
                rgbaBuffer.put(rgbaBytes);
                rgbaBuffer.position(0);
                lastTargetState = vitTracker.processFrame(
                        rgbaBuffer, rgba.width(), rgba.height(),
                        frameTimestampNs,
                        lastGyroX, lastGyroY, lastGyroZ, lastGyroTimestampNs,
                        T_FLIGHT_SEC,
                        pitch, yaw, roll
                );
            }

            // ======== Edge detection with reticle ========
            long currentTime = System.currentTimeMillis();
            if (lastFrameTime != 0) {
                double fps = 1000.0 / (currentTime - lastFrameTime);
                viewModel.setFps(fps);
            }
            lastFrameTime = currentTime;

            Mat edges = viewModel.getEdges();
            if (edges == null || edges.width() != width || edges.height() != height) {
                viewModel.initMats(width, height);
                edges = viewModel.getEdges();
            }

            Boolean edgeEnabled = viewModel.isEdgeDetectionEnabled().getValue();
            if (edgeEnabled == null) edgeEnabled = false;

            Mat finalMat = (edgeEnabled && EdgeDetector.isLibraryLoaded() && edges != null) ? edges : rgba;

            if (edgeEnabled && EdgeDetector.isLibraryLoaded() && edges != null) {
                VITTracker.TargetState ts = lastTargetState;
                EdgeDetector.detectEdgesWithReticle(
                        rgba.getNativeObjAddr(),
                        edges.getNativeObjAddr(),
                        50, 150, 5,
                        ts.detected ? Math.round(ts.bboxX + ts.bboxW / 2f) : 0,
                        ts.detected ? Math.round(ts.bboxY + ts.bboxH / 2f) : 0,
                        ts.detected,
                        false
                );
            }

            drawCenterRedDot(finalMat);

            if (vitTracker != null) {
                vitTracker.drawOverlay(finalMat, lastTargetState, pitch, yaw, roll, lastGyroX, lastGyroY, lastGyroZ);
            }

            if (glView != null) glView.updateFrame(finalMat);

            rgba.release();

        } catch (Exception e) {
            Log.e(TAG, "processFrame error: " + e.getMessage(), e);
        } finally {
            image.close();
        }
    }

    public void resetTracker() {
        if (vitTracker != null) vitTracker.reset();
    }

    public void release() {
        if (vitTracker != null) {
            vitTracker.release();
        }
    }

    private void drawCenterRedDot(Mat mat) {
        if (mat == null || mat.empty()) return;
        org.opencv.imgproc.Imgproc.circle(mat, new Point(mat.cols() / 2f, mat.rows() / 2f), 8, new Scalar(255, 0, 0, 255), -1);
    }

    public VITTracker.TargetState getLastTargetState() {
        return lastTargetState;
    }

    public static class FrameMetadata {
        public final int width;
        public final int height;
        public final long frameTimestampNs;
        public final long wallClockTimestampNs;
        public final float gyroX;
        public final float gyroY;
        public final float gyroZ;
        public final long gyroTimestampNs;
        public final float pitchDeg;
        public final float yawDeg;
        public final float rollDeg;
        public final boolean targetDetected;
        public final boolean targetTracking;
        public final int targetBboxX;
        public final int targetBboxY;
        public final int targetBboxW;
        public final int targetBboxH;
        public final float targetConfidence;

        public FrameMetadata(int width, int height, long frameTimestampNs, long wallClockTimestampNs,
                           float gyroX, float gyroY, float gyroZ, long gyroTimestampNs,
                           float pitchDeg, float yawDeg, float rollDeg,
                           boolean targetDetected, boolean targetTracking,
                           int targetBboxX, int targetBboxY, int targetBboxW, int targetBboxH, float targetConfidence) {
            this.width = width;
            this.height = height;
            this.frameTimestampNs = frameTimestampNs;
            this.wallClockTimestampNs = wallClockTimestampNs;
            this.gyroX = gyroX;
            this.gyroY = gyroY;
            this.gyroZ = gyroZ;
            this.gyroTimestampNs = gyroTimestampNs;
            this.pitchDeg = pitchDeg;
            this.yawDeg = yawDeg;
            this.rollDeg = rollDeg;
            this.targetDetected = targetDetected;
            this.targetTracking = targetTracking;
            this.targetBboxX = targetBboxX;
            this.targetBboxY = targetBboxY;
            this.targetBboxW = targetBboxW;
            this.targetBboxH = targetBboxH;
            this.targetConfidence = targetConfidence;
        }
    }
}
