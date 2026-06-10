package com.edgedetection.jni;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;

/**
 * VIT Tracker - визуально-инерциальный трекинг цели
 *
 * Получает видеопоток RGBA и данные гироскопа, компенсирует ego-motion,
 * определяет 3D-положение цели и выдаёт точку упреждения.
 */
public class VITTracker {
    private static final String TAG = "VITTracker";
    private static boolean libraryLoaded = false;

    static {
        try {
            System.loadLibrary("opencv_java4");
            Log.i(TAG, "OpenCV native library loaded successfully in VITTracker!");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load opencv_java4 in VITTracker static block: " + e.getMessage());
        }

        try {
            System.loadLibrary("vit_tracker");
            libraryLoaded = true;
            Log.i(TAG, "Native vit_tracker library loaded successfully!");
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
            Log.e(TAG, "Failed to load native vit_tracker library: " + e.getMessage());
        }
    }

    /**
     * Состояние цели на каждом кадре
     */
    public static class TargetState {
        public final boolean detected;
        public final boolean tracking;
        public final float bboxX;
        public final float bboxY;
        public final float bboxW;
        public final float bboxH;
        public final float distanceM;
        public final float worldX;
        public final float worldY;
        public final float worldZ;
        public final float velX;
        public final float velY;
        public final float velZ;
        public final float leadX;
        public final float leadY;
        public final float leadZ;
        public final float azimuthDeg;
        public final float elevationDeg;
        public final float confidence;

        public static final TargetState EMPTY = new TargetState(
            false, false,
            0f, 0f, 0f, 0f,
            0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f,
            0f
        );

        public TargetState(
            boolean detected, boolean tracking,
            float bboxX, float bboxY, float bboxW, float bboxH,
            float distanceM,
            float worldX, float worldY, float worldZ,
            float velX, float velY, float velZ,
            float leadX, float leadY, float leadZ,
            float azimuthDeg, float elevationDeg,
            float confidence
        ) {
            this.detected = detected;
            this.tracking = tracking;
            this.bboxX = bboxX;
            this.bboxY = bboxY;
            this.bboxW = bboxW;
            this.bboxH = bboxH;
            this.distanceM = distanceM;
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldZ = worldZ;
            this.velX = velX;
            this.velY = velY;
            this.velZ = velZ;
            this.leadX = leadX;
            this.leadY = leadY;
            this.leadZ = leadZ;
            this.azimuthDeg = azimuthDeg;
            this.elevationDeg = elevationDeg;
            this.confidence = confidence;
        }
    }

    private boolean initialized = false;

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    /**
     * Инициализация трекера
     *
     * @param context Context для доступа к assets
     */
    public boolean init(Context context) {
        return init(context, 2.0f, 4.0f);
    }

    /**
     * Инициализация трекера
     *
     * @param context      Context для доступа к assets
     * @param targetWidth  Ширина цели в метрах (по умолчанию 2.0)
     * @param targetLength Длина цели в метрах (по умолчанию 4.0)
     */
    public boolean init(Context context, float targetWidth, float targetLength) {
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot init - library not loaded");
            return false;
        }

        // Load calibration from assets
        String calibJson;
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("calib.json")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            calibJson = sb.toString();
        } catch (IOException e) {
            Log.w(TAG, "calib.json not found in assets, using defaults: " + e.getMessage());
            // Default calibration for 640x480 with ~60° FOV
            calibJson = "{\"fx\":640.0,\"fy\":640.0,\"cx\":320.0,\"cy\":240.0,\"k1\":0.0,\"k2\":0.0,\"imu_to_cam\":[[1,0,0],[0,1,0],[0,0,1]]}";
        }

        boolean ok = nativeInit(calibJson, targetWidth, targetLength);
        initialized = ok;
        Log.i(TAG, "VITTracker init: " + ok);
        return ok;
    }

    /**
     * Обработать кадр
     *
     * @param rgbaBuffer       Direct ByteBuffer с RGBA данными кадра
     * @param width            Ширина кадра
     * @param height           Высота кадра
     * @param frameTimestampNs Таймстемп кадра (наносекунды)
     * @param gyroX            Гироскоп X (rad/s)
     * @param gyroY            Гироскоп Y (rad/s)
     * @param gyroZ            Гироскоп Z (rad/s)
     * @param gyroTimestampNs  Таймстемп гироскопа (наносекунды)
     */
    public TargetState processFrame(
        ByteBuffer rgbaBuffer, int width, int height, long frameTimestampNs,
        float gyroX, float gyroY, float gyroZ, long gyroTimestampNs
    ) {
        return processFrame(rgbaBuffer, width, height, frameTimestampNs,
            gyroX, gyroY, gyroZ, gyroTimestampNs, 2.0f);
    }

    /**
     * Обработать кадр
     *
     * @param rgbaBuffer       Direct ByteBuffer с RGBA данными кадра
     * @param width            Ширина кадра
     * @param height           Высота кадра
     * @param frameTimestampNs Таймстемп кадра (наносекунды)
     * @param gyroX            Гироскоп X (rad/s)
     * @param gyroY            Гироскоп Y (rad/s)
     * @param gyroZ            Гироскоп Z (rad/s)
     * @param gyroTimestampNs  Таймстемп гироскопа (наносекунды)
     * @param tFlightSec       Время полёта снаряда (секунды, по умолчанию 2.0)
     */
    public TargetState processFrame(
        ByteBuffer rgbaBuffer, int width, int height, long frameTimestampNs,
        float gyroX, float gyroY, float gyroZ, long gyroTimestampNs,
        float tFlightSec
    ) {
        if (!initialized || !libraryLoaded) {
            return TargetState.EMPTY;
        }

        return nativeProcessFrame(rgbaBuffer, width, height, frameTimestampNs,
            gyroX, gyroY, gyroZ, gyroTimestampNs, tFlightSec);
    }

    /**
     * Получить матрицу стабилизации для OpenGL шейдера
     *
     * @return 3x3 matrix (9 floats) or null
     */
    public float[] getStabMatrix() {
        if (!initialized) return null;
        float[] mat = new float[9];
        return nativeGetStabMatrix(mat) ? mat : null;
    }

    /**
     * Сброс трекера (очистить состояние, не пересоздавая)
     */
    public void reset() {
        initialized = false;
        nativeReset();
    }

    /**
     * Полное освобождение ресурсов
     */
    public void release() {
        initialized = false;
        nativeRelease();
    }

    // ======== Native methods (instance methods matching JNI bridge) ========

    private native boolean nativeInit(
        String calibrationJson,
        float targetWidth,
        float targetLength
    );

    private native TargetState nativeProcessFrame(
        ByteBuffer rgbaBuffer,
        int width,
        int height,
        long frameTimestampNs,
        float gyroX,
        float gyroY,
        float gyroZ,
        long gyroTimestampNs,
        float tFlightSec
    );

    private native boolean nativeGetStabMatrix(float[] outMatrix);

    private native void nativeReset();

    private native void nativeRelease();
}