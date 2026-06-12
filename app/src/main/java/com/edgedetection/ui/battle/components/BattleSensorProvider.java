package com.edgedetection.ui.battle.components;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class BattleSensorProvider implements SensorEventListener {
    private static final String TAG = "BattleSensorProvider";

    public interface OnSensorChangedListener {
        void onRotationMatrixUpdated(float[] rotationMatrix, float[] orientation, float initialAzimuth);
        void onGyroscopeUpdated(float x, float y, float z, long timestampNs);
    }

    private final SensorManager sensorManager;
    private final Sensor rotationVectorSensor;
    private final Sensor gyroscopeSensor;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final Handler imuHandler;
    private final OnSensorChangedListener listener;

    private final List<ImuSample> imuSamples = new ArrayList<>();

    private final float[] rotationMatrix = new float[16];
    private final float[] remappedMatrix = new float[16];
    private final float[] orientation = new float[3];
    private final float[] lastRotationVector = new float[5];

    // Для режима акселерометр + магнитометр
    private final float[] accelerometerValues = new float[3];
    private final float[] magnetometerValues = new float[3];
    private boolean hasAccelerometerData = false;
    private boolean hasMagnetometerData = false;

    private boolean isCalibrating = false;
    private float calibSumX = 0f;
    private float calibSumY = 0f;
    private int calibCount = 0;
    private static final int CALIBRATION_SAMPLES = 30;
    private float initialAzimuth = 0f;
    private boolean calibrationDone = false;
    private int activeRotationSensorType = -1;

    public BattleSensorProvider(Context context, Handler imuHandler, OnSensorChangedListener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        this.gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        this.imuHandler = imuHandler;
        this.listener = listener;

        // Диагностика доступности датчиков
        Log.w(TAG, "=== SENSOR DIAGNOSTICS ===");
        Log.w(TAG, "Rotation Vector Sensor: " + (rotationVectorSensor != null ? rotationVectorSensor.getName() : "NULL!"));
        Log.w(TAG, "Gyroscope Sensor: " + (gyroscopeSensor != null ? gyroscopeSensor.getName() : "NULL!"));
        Log.w(TAG, "Accelerometer Sensor: " + (accelerometer != null ? accelerometer.getName() : "NULL!"));
        Log.w(TAG, "Magnetometer Sensor: " + (magnetometer != null ? magnetometer.getName() : "NULL!"));

        List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        Log.w(TAG, "Total sensors available: " + allSensors.size());
        for (Sensor s : allSensors) {
            if (s.getType() == Sensor.TYPE_ROTATION_VECTOR ||
                    s.getType() == Sensor.TYPE_GYROSCOPE ||
                    s.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR ||
                    s.getType() == Sensor.TYPE_ACCELEROMETER ||
                    s.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                Log.w(TAG, "Found: " + s.getName() + " (type=" + s.getType() + ")");
            }
        }
    }

    public void start() {
        Log.w(TAG, "=== STARTING SENSORS ===");

        // Приоритет 1: Rotation Vector (использует акселерометр + гироскоп + магнитометр)
        if (rotationVectorSensor != null) {
            isCalibrating = true;
            calibrationDone = false;
            calibSumX = 0f;
            calibSumY = 0f;
            calibCount = 0;
            activeRotationSensorType = Sensor.TYPE_ROTATION_VECTOR;

            boolean registered = sensorManager.registerListener(this, rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME, imuHandler);
            Log.w(TAG, "Rotation Vector registered: " + registered);
        }
        // Приоритет 2: Акселерометр + Магнитометр (альтернатива без магнитометра)
        else if (accelerometer != null && magnetometer != null) {
            isCalibrating = true;
            calibrationDone = false;
            calibSumX = 0f;
            calibSumY = 0f;
            calibCount = 0;
            activeRotationSensorType = -1; // Особый режим: Accel + Mag

            boolean regAccel = sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_GAME, imuHandler);
            boolean regMag = sensorManager.registerListener(this, magnetometer,
                    SensorManager.SENSOR_DELAY_GAME, imuHandler);
            Log.w(TAG, "Accelerometer registered: " + regAccel);
            Log.w(TAG, "Magnetometer registered: " + regMag);
            Log.w(TAG, "Using Accelerometer + Magnetometer fallback");
        }
        // Приоритет 3: Game Rotation Vector (без магнитометра)
        else {
            Log.e(TAG, "Rotation Vector Sensor is NULL! Trying GAME_ROTATION_VECTOR...");
            Sensor gameRotation = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (gameRotation != null) {
                activeRotationSensorType = Sensor.TYPE_GAME_ROTATION_VECTOR;
                boolean registered = sensorManager.registerListener(this, gameRotation,
                        SensorManager.SENSOR_DELAY_GAME, imuHandler);
                Log.w(TAG, "Game Rotation Vector registered: " + registered);
            } else {
                Log.e(TAG, "NO ROTATION SENSOR AVAILABLE!");
            }
        }

        if (gyroscopeSensor != null) {
            boolean registered = sensorManager.registerListener(this, gyroscopeSensor,
                    SensorManager.SENSOR_DELAY_GAME, imuHandler);
            Log.w(TAG, "Gyroscope registered: " + registered);
        } else {
            Log.e(TAG, "Gyroscope Sensor is NULL!");
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    public void triggerRecalibration() {
        Log.d(TAG, "Compass recalibration trigger");
        isCalibrating = true;
        calibrationDone = false;
        calibSumX = 0f;
        calibSumY = 0f;
        calibCount = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long wallClockNs = System.nanoTime();
        synchronized (imuSamples) {
            imuSamples.add(new ImuSample(event.sensor.getType(), event.timestamp, wallClockNs, event.values));
        }

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            listener.onGyroscopeUpdated(event.values[0], event.values[1], event.values[2], event.timestamp);
            return;
        }

        // Режим: Акселерометр + Магнитометр
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerValues, 0, 3);
            hasAccelerometerData = true;
            calculateOrientationFromAccelMag();
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerValues, 0, 3);
            hasMagnetometerData = true;
            calculateOrientationFromAccelMag();
            return;
        }

        // Режим: Rotation Vector или Game Rotation Vector
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR &&
                event.sensor.getType() != Sensor.TYPE_GAME_ROTATION_VECTOR) {
            return;
        }

        // Лог для отладки (раз в секунду)
        if (System.currentTimeMillis() % 1000 < 50) {
            Log.d(TAG, "Rotation event! Type: " + event.sensor.getType() +
                    ", values[0]=" + String.format("%.3f", event.values[0]));
        }

        System.arraycopy(event.values, 0, lastRotationVector, 0, Math.min(event.values.length, lastRotationVector.length));
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

        // Для ландшафтной ориентации (планшет горизонтально)
        SensorManager.remapCoordinateSystem(rotationMatrix,
                SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix);
        SensorManager.getOrientation(remappedMatrix, orientation);
        float azimuth = orientation[0];

        if (isCalibrating) {
            calibSumX += (float) Math.cos(azimuth);
            calibSumY += (float) Math.sin(azimuth);
            calibCount++;
            if (calibCount >= CALIBRATION_SAMPLES) {
                initialAzimuth = (float) Math.atan2(calibSumY / calibCount, calibSumX / calibCount);
                isCalibrating = false;
                calibrationDone = true;
                Log.d(TAG, "Compass calibrated: " + Math.toDegrees(initialAzimuth) + "°");
            }
        }

        listener.onRotationMatrixUpdated(rotationMatrix, orientation, initialAzimuth);
    }

    private void calculateOrientationFromAccelMag() {
        if (!hasAccelerometerData || !hasMagnetometerData) return;

        boolean success = SensorManager.getRotationMatrix(rotationMatrix, null,
                accelerometerValues, magnetometerValues);

        if (success) {
            SensorManager.remapCoordinateSystem(rotationMatrix,
                    SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix);
            SensorManager.getOrientation(remappedMatrix, orientation);

            float azimuth = orientation[0];

            // Лог для отладки (раз в секунду)
            if (System.currentTimeMillis() % 1000 < 50) {
                Log.d(TAG, String.format("Accel+Mag orientation -> yaw: %.1f°, pitch: %.1f°, roll: %.1f°",
                        Math.toDegrees(orientation[0]),
                        Math.toDegrees(orientation[1]),
                        Math.toDegrees(orientation[2])));
            }

            if (isCalibrating) {
                calibSumX += (float) Math.cos(azimuth);
                calibSumY += (float) Math.sin(azimuth);
                calibCount++;
                if (calibCount >= CALIBRATION_SAMPLES) {
                    initialAzimuth = (float) Math.atan2(calibSumY / calibCount, calibSumX / calibCount);
                    isCalibrating = false;
                    calibrationDone = true;
                    Log.d(TAG, "Compass calibrated (Accel+Mag): " + Math.toDegrees(initialAzimuth) + "°");
                }
            }

            listener.onRotationMatrixUpdated(rotationMatrix, orientation, initialAzimuth);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Accuracy changed for " + sensor.getName() + ": " + accuracy);
    }

    public void clearImuSamples() {
        synchronized (imuSamples) {
            imuSamples.clear();
        }
    }

    public List<ImuSample> getImuSamples() {
        synchronized (imuSamples) {
            return new ArrayList<>(imuSamples);
        }
    }

    public float[] getRotationMatrix() {
        return rotationMatrix;
    }

    public float[] getOrientation() {
        return orientation;
    }

    public float[] getLastRotationVector() {
        return lastRotationVector;
    }

    public void getLandscapeVectors(float[] fx, float[] fy, float[] fz, float[] ux, float[] uy, float[] uz) {
        float[] landscapeMatrix = new float[16];
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, landscapeMatrix);

        float fE = -landscapeMatrix[2];
        float fN = -landscapeMatrix[6];
        float fU = -landscapeMatrix[10];

        float uE = landscapeMatrix[1];
        float uN = landscapeMatrix[5];
        float uU = landscapeMatrix[9];

        fx[0] = fE; fy[0] = fU; fz[0] = -fN;
        ux[0] = uE; uy[0] = uU; uz[0] = -uN;

        float fl = (float) Math.sqrt(fx[0]*fx[0] + fy[0]*fy[0] + fz[0]*fz[0]);
        if (fl > 0) { fx[0] /= fl; fy[0] /= fl; fz[0] /= fl; }
        float ul = (float) Math.sqrt(ux[0]*ux[0] + uy[0]*uy[0] + uz[0]*uz[0]);
        if (ul > 0) { ux[0] /= ul; uy[0] /= ul; uz[0] /= ul; }
        float dot = fx[0]*ux[0] + fy[0]*uy[0] + fz[0]*uz[0];
        ux[0] -= dot * fx[0]; uy[0] -= dot * fy[0]; uz[0] -= dot * fz[0];
        ul = (float) Math.sqrt(ux[0]*ux[0] + uy[0]*uy[0] + uz[0]*uz[0]);
        if (ul > 0) { ux[0] /= ul; uy[0] /= ul; uz[0] /= ul; }
    }

    public float getInitialAzimuth() {
        return initialAzimuth;
    }

    public boolean isCalibrationDone() {
        return calibrationDone;
    }

    public boolean isCalibrating() {
        return isCalibrating;
    }

    public static class ImuSample {
        public final int sensorType;
        public final long timestampNs;
        public final long wallClockTimestampNs;
        public final float[] values;

        public ImuSample(int sensorType, long timestampNs, long wallClockTimestampNs, float[] values) {
            this.sensorType = sensorType;
            this.timestampNs = timestampNs;
            this.wallClockTimestampNs = wallClockTimestampNs;
            this.values = values != null ? values.clone() : new float[0];
        }
    }
}