package com.edgedetection.ui.battle.components;

import android.hardware.SensorManager;
import android.util.Log;

import com.google.android.filament.Viewport;
import com.edgedetection.opengl.Filament3DRenderer;

import org.json.JSONArray;
import org.json.JSONObject;

public class BattleLogger {
    private static final String TAG = "BattleLogger";
    private static final float EYE_HEIGHT = 1.6f;

    // Вспомогательные методы для безопасной записи чисел
    private static double safeDouble(double value) {
        return (Double.isNaN(value) || Double.isInfinite(value)) ? 0.0 : value;
    }

    private static double safeDouble(float value) {
        return (Float.isNaN(value) || Float.isInfinite(value)) ? 0.0 : value;
    }

    private static float safeFloat(float value) {
        return (Float.isNaN(value) || Float.isInfinite(value)) ? 0.0f : value;
    }

    public static void logState(float fx, float fy, float fz, float ux, float uy, float uz,
                                double refLat, double refLon, double refAlt,
                                double[] enu, double distanceM,
                                float lastDroneX, float lastDroneY, float lastDroneZ,
                                double droneLat, double droneLon, double droneAlt,
                                float[] lastRotationVector, float[] rotationMatrix,
                                Filament3DRenderer arRenderer, boolean droneVisible,
                                float bestMissDistM) {
        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis() / 1000);
            root.put("distance_m", Math.round(safeDouble(distanceM)));
            if (bestMissDistM >= 0) root.put("best_miss_m", Math.round(safeDouble(bestMissDistM)));

            JSONObject user = new JSONObject();
            JSONObject userGps = new JSONObject();
            userGps.put("lat", safeDouble(refLat));
            userGps.put("lon", safeDouble(refLon));
            userGps.put("alt", safeDouble(refAlt));
            user.put("gps", userGps);

            JSONObject sensors = new JSONObject();
            JSONArray rv = new JSONArray();
            if (lastRotationVector != null) {
                for (float v : lastRotationVector) rv.put(safeDouble(v));
            }
            sensors.put("rotationVector", rv);

            float[] standardOrientation = new float[3];
            if (rotationMatrix != null) {
                SensorManager.getOrientation(rotationMatrix, standardOrientation);
            }
            sensors.put("azimuth", Math.toDegrees(safeDouble(standardOrientation[0])));
            sensors.put("pitch", Math.toDegrees(safeDouble(standardOrientation[1])));
            sensors.put("roll", Math.toDegrees(safeDouble(standardOrientation[2])));
            user.put("sensors", sensors);
            root.put("user", user);

            JSONObject drone = new JSONObject();
            JSONObject droneGps = new JSONObject();
            droneGps.put("lat", safeDouble(droneLat));
            droneGps.put("lon", safeDouble(droneLon));
            droneGps.put("alt", safeDouble(droneAlt));
            drone.put("gps", droneGps);
            root.put("drone", drone);

            JSONObject enuObj = new JSONObject();
            JSONObject origin = new JSONObject();
            origin.put("lat", safeDouble(refLat));
            origin.put("lon", safeDouble(refLon));
            enuObj.put("origin", origin);

            JSONObject droneEnu = new JSONObject();
            if (enu != null && enu.length >= 3) {
                droneEnu.put("E", safeDouble(enu[0]));
                droneEnu.put("N", safeDouble(enu[1]));
                droneEnu.put("U", safeDouble(enu[2]));
            }
            enuObj.put("drone", droneEnu);
            root.put("enu", enuObj);

            JSONObject engine = new JSONObject();
            engine.put("convention", "Y-up, right-handed");

            JSONObject dronePos = new JSONObject();
            dronePos.put("x", safeDouble(lastDroneX));
            dronePos.put("y", safeDouble(lastDroneY));
            dronePos.put("z", safeDouble(lastDroneZ));
            engine.put("dronePos", dronePos);

            JSONObject camera = new JSONObject();
            JSONObject camPos = new JSONObject();
            camPos.put("x", 0);
            camPos.put("y", EYE_HEIGHT);
            camPos.put("z", 0);
            camera.put("pos", camPos);

            JSONObject forward = new JSONObject();
            forward.put("x", safeDouble(fx));
            forward.put("y", safeDouble(fy));
            forward.put("z", safeDouble(fz));
            camera.put("forward", forward);

            JSONObject up = new JSONObject();
            up.put("x", safeDouble(ux));
            up.put("y", safeDouble(uy));
            up.put("z", safeDouble(uz));
            camera.put("up", up);

            // Right vector: forward x up
            float rx = fy * uz - fz * uy;
            float ry = fz * ux - fx * uz;
            float rz = fx * uy - fy * ux;
            JSONObject right = new JSONObject();
            right.put("x", safeDouble(rx));
            right.put("y", safeDouble(ry));
            right.put("z", safeDouble(rz));
            camera.put("right", right);
            engine.put("camera", camera);

            double[] viewMat = new double[16];
            double[] projMat = new double[16];
            if (arRenderer != null && arRenderer.getCamera() != null) {
                arRenderer.getCamera().getViewMatrix(viewMat);
                arRenderer.getCamera().getProjectionMatrix(projMat);
            }

            JSONArray viewMatArr = new JSONArray();
            for (double v : viewMat) viewMatArr.put(safeDouble(v));
            engine.put("viewMatrix", viewMatArr);

            JSONArray projMatArr = new JSONArray();
            for (double v : projMat) projMatArr.put(safeDouble(v));
            engine.put("projectionMatrix", projMatArr);

            float[] modelMat = arRenderer != null ? arRenderer.getDroneModelMatrix() : null;
            JSONArray modelMatArr = new JSONArray();
            if (modelMat != null) {
                for (float v : modelMat) modelMatArr.put(safeDouble(v));
            }
            engine.put("modelMatrixDrone", modelMatArr);
            root.put("engine", engine);

            JSONObject screen = new JSONObject();
            // Calculate NDC
            float[] v_world = {safeFloat(lastDroneX), safeFloat(lastDroneY), safeFloat(lastDroneZ), 1.0f};
            float[] v_view = new float[4];
            for (int i = 0; i < 4; i++) {
                v_view[i] = 0;
                for (int j = 0; j < 4; j++) {
                    v_view[i] += (float)viewMat[j * 4 + i] * v_world[j];
                }
            }

            float[] v_clip = new float[4];
            for (int i = 0; i < 4; i++) {
                v_clip[i] = 0;
                for (int j = 0; j < 4; j++) {
                    v_clip[i] += (float)projMat[j * 4 + i] * v_view[j];
                }
            }

            JSONObject ndc = new JSONObject();
            if (v_clip[3] != 0 && !Float.isNaN(v_clip[3])) {
                ndc.put("x", safeDouble(v_clip[0] / v_clip[3]));
                ndc.put("y", safeDouble(v_clip[1] / v_clip[3]));
                ndc.put("z", safeDouble(v_clip[2] / v_clip[3]));
            } else {
                ndc.put("x", 0);
                ndc.put("y", 0);
                ndc.put("z", 0);
            }
            screen.put("droneNDC", ndc);

            Viewport vp = arRenderer != null ? arRenderer.getViewport() : null;
            JSONObject pixel = new JSONObject();
            if (vp != null && v_clip[3] != 0 && !Float.isNaN(v_clip[3])) {
                float nx = v_clip[0] / v_clip[3];
                float ny = v_clip[1] / v_clip[3];
                pixel.put("x", safeDouble((nx * 0.5f + 0.5f) * vp.width));
                pixel.put("y", safeDouble((1.0f - (ny * 0.5f + 0.5f)) * vp.height));
            } else {
                pixel.put("x", 0);
                pixel.put("y", 0);
            }
            screen.put("dronePixel", pixel);
            screen.put("visible", droneVisible);
            root.put("screen", screen);

            Log.i("DRONE_STATE_JSON", root.toString(2));

        } catch (Exception e) {
            Log.e(TAG, "Error logging state", e);
        }
    }
}