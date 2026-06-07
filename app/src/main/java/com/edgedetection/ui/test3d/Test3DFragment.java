package com.edgedetection.ui.test3d;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.edgedetection.R;
import com.edgedetection.opengl.Filament3DRenderer;

public class Test3DFragment extends Fragment implements SensorEventListener {
    private static final String TAG = "Test3D";

    private Filament3DRenderer renderer;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;

    private final float[] rotationMatrix = new float[16];
    private final float[] remappedMatrix = new float[16];
    private final float[] orientation = new float[3];

    // Калибровка сферы
    private boolean isCalibrating = false;
    private float calibSumX = 0f;
    private float calibSumY = 0f;
    private int calibCount = 0;
    private static final int CALIBRATION_SAMPLES = 30;

    private float initialAzimuth = 0f;
    private boolean calibrationDone = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_test_3d, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SurfaceView surface = view.findViewById(R.id.test3d_surface);
        renderer = new Filament3DRenderer(requireContext(), surface, false);

        // 1. Небо (GLB-сфера, пока без масштаба/поворота)
        renderer.loadSkyboxModel("models/skybox.glb");
        if (!renderer.isSkyboxLoaded()) {
            Toast.makeText(requireContext(), "Skybox failed!", Toast.LENGTH_LONG).show();
        }

        // 2. Дрон
        renderer.loadModel("models/drone.glb");
        if (!renderer.isModelLoaded()) {
            Toast.makeText(requireContext(), "Drone failed!", Toast.LENGTH_LONG).show();
        }

        // 3. Свет
        renderer.setupEnvironmentLighting();

        // 4. Сенсоры
        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationVectorSensor == null) {
            Toast.makeText(requireContext(), "No Rotation Vector sensor!", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (renderer != null) renderer.onResume();

        View v = getView();
        if (v != null) {
            SurfaceView sv = v.findViewById(R.id.test3d_surface);
            if (sv != null) sv.setVisibility(View.VISIBLE);
        }

        if (rotationVectorSensor != null) {
            // Стартуем калибровку
            isCalibrating = true;
            calibrationDone = false;
            calibSumX = 0f;
            calibSumY = 0f;
            calibCount = 0;
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (renderer != null) renderer.onPause();
        sensorManager.unregisterListener(this);

        View v = getView();
        if (v != null) {
            SurfaceView sv = v.findViewById(R.id.test3d_surface);
            if (sv != null) sv.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
        );
        SensorManager.getOrientation(remappedMatrix, orientation);

        float azimuth = orientation[0];   // -PI..PI, 0 = север
        float pitch   = orientation[1];   // -PI/2..PI/2

        // === ФАЗА 1: Калибровка сферы ===
        if (isCalibrating) {
            // Усреднение углов через векторы (избегаем проблемы -PI/PI)
            calibSumX += Math.cos(azimuth);
            calibSumY += Math.sin(azimuth);
            calibCount++;

            if (calibCount >= CALIBRATION_SAMPLES) {
                initialAzimuth = (float) Math.atan2(calibSumY / calibCount, calibSumX / calibCount);
                isCalibrating = false;
                calibrationDone = true;

                // Поворачиваем сферу: компенсируем смещение компаса
                renderer.calibrateSkybox(initialAzimuth);
                Log.d(TAG, "North calibrated: " + Math.toDegrees(initialAzimuth) + "°");
            }
            return;
        }

        if (!calibrationDone) return;

        // === ФАЗА 2: Камера следует за телефоном ===
        float relativeYaw = azimuth - initialAzimuth;
        // Нормализуем -PI..PI
        while (relativeYaw > Math.PI) relativeYaw -= 2 * Math.PI;
        while (relativeYaw < -Math.PI) relativeYaw += 2 * Math.PI;

        if (renderer != null && renderer.isModelLoaded()) {
            renderer.updateCameraSpringArm(relativeYaw, pitch);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}