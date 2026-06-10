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

    public static void logState(float fx, float fy, float fz, float ux, float uy, float uz,
                         double refLat, double refLon, double refAlt,
                         double[] enu, float lastDroneX, float lastDroneY, float lastDroneZ,
                         double droneLat, double droneLon, double droneAlt,
                         float[] lastRotationVector, float[] rotationMatrix,
                         Filament3DRenderer arRenderer, boolean droneVisible) {
        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis() / 1000);

            JSONObject user = new JSONObject();
            JSONObject userGps = new JSONObject();
            userGps.put("lat", refLat);
            userGps.put("lon", refLon);
            userGps.put("alt", refAlt);
            user.put("gps", userGps);

            JSONObject sensors = new JSONObject();
            JSONArray rv = new JSONArray();
            for (float v : lastRotationVector) rv.put(v);
            sensors.put("rotationVector", rv);
            
            float[] standardOrientation = new float[3];
            SensorManager.getOrientation(rotationMatrix, standardOrientation);
            sensors.put("azimuth", Math.toDegrees(standardOrientation[0]));
            sensors.put("pitch", Math.toDegrees(standardOrientation[1]));
            sensors.put("roll", Math.toDegrees(standardOrientation[2]));
            user.put("sensors", sensors);
            root.put("user", user);

            JSONObject drone = new JSONObject();
            JSONObject droneGps = new JSONObject();
            droneGps.put("lat", droneLat);
            droneGps.put("lon", droneLon);
            droneGps.put("alt", droneAlt);
            drone.put("gps", droneGps);
            root.put("drone", drone);

            JSONObject enuObj = new JSONObject();
            JSONObject origin = new JSONObject();
            origin.put("lat", refLat);
            origin.put("lon", refLon);
            enuObj.put("origin", origin);
            JSONObject droneEnu = new JSONObject();
            droneEnu.put("E", enu[0]);
            droneEnu.put("N", enu[1]);
            droneEnu.put("U", enu[2]);
            enuObj.put("drone", droneEnu);
            root.put("enu", enuObj);

            JSONObject engine = new JSONObject();
            engine.put("convention", "Y-up, right-handed");
            JSONObject dronePos = new JSONObject();
            dronePos.put("x", lastDroneX);
            dronePos.put("y", lastDroneY);
            dronePos.put("z", lastDroneZ);
            engine.put("dronePos", dronePos);

            JSONObject camera = new JSONObject();
            JSONObject camPos = new JSONObject();
            camPos.put("x", 0);
            camPos.put("y", EYE_HEIGHT);
            camPos.put("z", 0);
            camera.put("pos", camPos);

            JSONObject forward = new JSONObject();
            forward.put("x", fx);
            forward.put("y", fy);
            forward.put("z", fz);
            camera.put("forward", forward);

            JSONObject up = new JSONObject();
            up.put("x", ux);
            up.put("y", uy);
            up.put("z", uz);
            camera.put("up", up);

            // Right vector: forward x up
            float rx = fy * uz - fz * uy;
            float ry = fz * ux - fx * uz;
            float rz = fx * uy - fy * ux;
            JSONObject right = new JSONObject();
            right.put("x", rx);
            right.put("y", ry);
            right.put("z", rz);
            camera.put("right", right);
            engine.put("camera", camera);

            double[] viewMat = new double[16];
            double[] projMat = new double[16];
            arRenderer.getCamera().getViewMatrix(viewMat);
            arRenderer.getCamera().getProjectionMatrix(projMat);
            JSONArray viewMatArr = new JSONArray();
            for (double v : viewMat) viewMatArr.put(v);
            engine.put("viewMatrix", viewMatArr);
            JSONArray projMatArr = new JSONArray();
            for (double v : projMat) projMatArr.put(v);
            engine.put("projectionMatrix", projMatArr);

            float[] modelMat = arRenderer.getDroneModelMatrix();
            JSONArray modelMatArr = new JSONArray();
            for (float v : modelMat) modelMatArr.put(v);
            engine.put("modelMatrixDrone", modelMatArr);
            root.put("engine", engine);

            JSONObject screen = new JSONObject();
            // Calculate NDC
            float[] v_world = {lastDroneX, lastDroneY, lastDroneZ, 1.0f};
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
            if (v_clip[3] != 0) {
                ndc.put("x", v_clip[0] / v_clip[3]);
                ndc.put("y", v_clip[1] / v_clip[3]);
                ndc.put("z", v_clip[2] / v_clip[3]);
            } else {
                ndc.put("x", 0); ndc.put("y", 0); ndc.put("z", 0);
            }
            screen.put("droneNDC", ndc);

            Viewport vp = arRenderer.getViewport();
            JSONObject pixel = new JSONObject();
            if (v_clip[3] != 0) {
                float nx = v_clip[0] / v_clip[3];
                float ny = v_clip[1] / v_clip[3];
                pixel.put("x", (nx * 0.5f + 0.5f) * vp.width);
                pixel.put("y", (1.0f - (ny * 0.5f + 0.5f)) * vp.height);
            } else {
                pixel.put("x", 0); pixel.put("y", 0);
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
