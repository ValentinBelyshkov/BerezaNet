package com.edgedetection.core.camera;

import android.util.Log;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.net.InetAddress;

/**
 * External Camera Source implementation using OpenCV for RTSP streaming.
 */
public class ExternalCameraSource implements CameraSource {
    private static final String TAG = "ExternalCameraSource";
    private static final String RTSP_URL = "rtsp://192.168.42.1:8554/video";
    private static final String HOST_IP = "192.168.42.1";

    private CameraSourceListener listener;
    private StatusListener statusListener;
    private volatile boolean isRunning = false;
    private Thread captureThread;

    @Override
    public void start(CameraSourceListener listener) {
        if (isRunning) return;
        this.listener = listener;
        this.isRunning = true;
        
        captureThread = new Thread(this::captureLoop, "RTSPCaptureThread");
        captureThread.start();
        Log.i(TAG, "External camera source started");
    }

    @Override
    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    @Override
    public void stop() {
        isRunning = false;
        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        this.listener = null;
        Log.i(TAG, "External camera source stopped");
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    private void captureLoop() {
        Mat bgrFrame = new Mat();
        Mat rgbaFrame = new Mat();
        
        while (isRunning) {
            // 1. Ping check
            updateStatus("Проверка связи (192.168.42.1)...", false, false);
            if (!pingHost(HOST_IP, 1500)) {
                Log.w(TAG, "Ping failed for " + HOST_IP + ", retrying in 3s...");
                updateStatus("Нет пинга до 192.168.42.1", true, true);
                sleepSafe(3000);
                continue;
            }

            // 2. Open RTSP stream
            updateStatus("Загрузка RTSP потока...", false, false);
            VideoCapture videoCapture = new VideoCapture();
            videoCapture.open(RTSP_URL);

            if (!videoCapture.isOpened()) {
                Log.w(TAG, "Failed to open RTSP stream: " + RTSP_URL + ", retrying in 3s...");
                updateStatus("Нет изображения по " + RTSP_URL, true, true);
                videoCapture.release();
                sleepSafe(3000);
                continue;
            }

            Log.i(TAG, "RTSP stream opened successfully");
            updateStatus("Поток подключен", false, false);

            // 3. Read frames
            boolean firstFrame = true;
            while (isRunning) {
                if (videoCapture.read(bgrFrame)) {
                    if (bgrFrame.empty()) continue;
                    
                    if (firstFrame) {
                        updateStatus(null, false, false); // Hide error overlay
                        firstFrame = false;
                    }
                    
                    // Convert BGR to RGBA for BattleFrameProcessor
                    Imgproc.cvtColor(bgrFrame, rgbaFrame, Imgproc.COLOR_BGR2RGBA);
                    
                    if (listener != null) {
                        listener.onFrame(rgbaFrame);
                    }
                } else {
                    Log.w(TAG, "Failed to read frame from RTSP stream, reconnecting...");
                    updateStatus("Потеряно соединение с RTSP", true, true);
                    break;
                }
            }
            
            videoCapture.release();
            if (isRunning) {
                sleepSafe(3000);
            }
        }
        
        bgrFrame.release();
        rgbaFrame.release();
    }

    private void updateStatus(String message, boolean isError, boolean isRetrying) {
        if (statusListener != null) {
            statusListener.onStatusUpdate(message, isError, isRetrying);
        }
    }

    private boolean pingHost(String host, int timeoutMs) {
        try {
            // Using a simple reachable check as a fallback for /system/bin/ping
            Process process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 2 " + host);
            int exitValue = process.waitFor();
            if (exitValue == 0) return true;
        } catch (Exception e) {
            Log.w(TAG, "Shell ping command failed, trying fallback reachable check", e);
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isReachable(timeoutMs);
        } catch (Exception e) {
            Log.e(TAG, "InetAddress check failed for " + host, e);
            return false;
        }
    }

    private void sleepSafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
