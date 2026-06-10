package com.edgedetection.ui.battle.components;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

public class BattleSensorProvider implements SensorEventListener {
    private static final String TAG = "BattleSensorProvider";
    
    public interface OnSensorChangedListener {
        void onRotationMatrixUpdated(float[] rotationMatrix, float[] orientation, float initialAzimuth);
        void onGyroscopeUpdated(float x, float y, float z, long timestampNs);
    }

    private final SensorManager sensorManager;
    private final Sensor rotationVectorSensor;
    private final Sensor gyroscopeSensor;
    private final Handler imuHandler;
    private final OnSensorChangedListener listener;

    private final float[] rotationMatrix = new float[16];
    private final float[] remappedMatrix = new float[16];
    private final float[] orientation = new float[3];
    private final float[] lastRotationVector = new float[5];
    
    private boolean isCalibrating = false;
    private float calibSumX = 0f;
    private float calibSumY = 0f;
    private int calibCount = 0;
    private static final int CALIBRATION_SAMPLES = 30;
    private float initialAzimuth = 0f;
    private boolean calibrationDone = false;

    public BattleSensorProvider(Context context, Handler imuHandler, OnSensorChangedListener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        this.gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.imuHandler = imuHandler;
        this.listener = listener;
    }

    public void start() {
        if (rotationVectorSensor != null) {
            isCalibrating = true;
            calibrationDone = false;
            calibSumX = 0f;
            calibSumY = 0f;
            calibCount = 0;
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME, imuHandler);
        }
        if (gyroscopeSensor != null) {
            sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME, imuHandler);
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
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            listener.onGyroscopeUpdated(event.values[0], event.values[1], event.values[2], event.timestamp);
            return;
        }

        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        
        System.arraycopy(event.values, 0, lastRotationVector, 0, Math.min(event.values.length, lastRotationVector.length));
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

        // Расчет азимута для калибровки
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix);
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

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

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
}
