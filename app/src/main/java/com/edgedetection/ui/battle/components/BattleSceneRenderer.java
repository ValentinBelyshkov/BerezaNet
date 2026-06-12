package com.edgedetection.ui.battle.components;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.edgedetection.domain.geo.GeoUtils;
import com.edgedetection.opengl.Filament3DRenderer;
import com.google.android.filament.Viewport;

import org.json.JSONArray;
import org.json.JSONObject;

public class BattleSceneRenderer {
    private static final String TAG = "BattleSceneRenderer";
    private static final float EYE_HEIGHT = 1.6f;
    private static final float TAN_HALF_FOV_Y = (float) Math.tan(Math.toRadians(22.5));

    private final Context context;
    private Filament3DRenderer arRenderer;
    private final SurfaceView arSurface;
    private final ImageView offscreenIndicator;
    private final TextView gpsWarning;

    private float lastDroneX, lastDroneY, lastDroneZ;
    private boolean hasRelativePosition = false;

    public BattleSceneRenderer(Context context, SurfaceView arSurface, ImageView offscreenIndicator, TextView gpsWarning) {
        this.context = context;
        this.arSurface = arSurface;
        this.offscreenIndicator = offscreenIndicator;
        this.gpsWarning = gpsWarning;
        initRenderer();
    }

    private void initRenderer() {
        arRenderer = new Filament3DRenderer(context, arSurface, true);
        arRenderer.setFarPlane(2000.0);
        arRenderer.loadModel("models/drone.glb");
        if (!arRenderer.isModelLoaded()) {
            Toast.makeText(context, "drone.glb failed", Toast.LENGTH_LONG).show();
        } else {
            arRenderer.setModelVisible(false);
        }
        arRenderer.loadCardinalCubes("models/cardinal_cubes.glb");
        arRenderer.setupEnvironmentLighting();
    }

    public void onResume() {
        if (arRenderer != null) arRenderer.onResume();
    }

    public void onPause() {
        if (arRenderer != null) arRenderer.onPause();
    }

    public void destroy() {
        if (arRenderer != null) {
            arRenderer.destroy();
            arRenderer = null;
        }
    }

    public void updateCamera(float fx, float fy, float fz, float ux, float uy, float uz) {
        if (arRenderer != null) {
            arRenderer.updateCameraAR(EYE_HEIGHT, fx, fy, fz, ux, uy, uz);
        }
    }

    public void updateDronePosition(double refLat, double refLon, double refAlt, 
                                   double droneLat, double droneLon, double droneAlt, 
                                   float droneHeading, boolean simulationActive, boolean hasDronePosition) {
        if (arRenderer == null || !arRenderer.isModelLoaded()) return;

        if (simulationActive && hasDronePosition) {
            double[] enu = GeoUtils.ecefToEnu(refLat, refLon, refAlt, droneLat, droneLon, droneAlt);
            float[] pos = GeoUtils.enuToFilament(enu);
            lastDroneX = pos[0];
            lastDroneY = pos[1];
            lastDroneZ = pos[2];
            hasRelativePosition = true;

            arRenderer.setModelVisible(true);
            arRenderer.setDronePosition(lastDroneX, lastDroneY, lastDroneZ,
                    (float) Math.toRadians(droneHeading) + (float) Math.PI);
        } else if (hasRelativePosition) {
            arRenderer.setModelVisible(true);
            arRenderer.setDronePosition(lastDroneX, lastDroneY, lastDroneZ,
                    (float) Math.toRadians(droneHeading) + (float) Math.PI);
        } else {
            arRenderer.setModelVisible(false);
        }
    }

    public void updateOffscreenIndicator(float fx, float fy, float fz, float ux, float uy, float uz, View rootView) {
        if (!hasRelativePosition || offscreenIndicator == null || arRenderer == null) {
            if (offscreenIndicator != null) offscreenIndicator.setVisibility(View.GONE);
            return;
        }

        float rx = fy * uz - fz * uy;
        float ry = fz * ux - fx * uz;
        float rz = fx * uy - fy * ux;

        float dx = lastDroneX;
        float dy = lastDroneY - EYE_HEIGHT;
        float dz = lastDroneZ;

        float zCam = dx*fx + dy*fy + dz*fz;
        float xCam = dx*rx + dy*ry + dz*rz;
        float yCam = dx*ux + dy*uy + dz*uz;

        float aspect = arRenderer.getAspectRatio();
        float tanX = aspect * TAN_HALF_FOV_Y;

        boolean visible = zCam > 0.1f &&
                Math.abs(xCam) < zCam * tanX &&
                Math.abs(yCam) < zCam * TAN_HALF_FOV_Y;

        if (visible) {
            offscreenIndicator.setVisibility(View.GONE);
            return;
        }

        offscreenIndicator.setVisibility(View.VISIBLE);

        float ndcX, ndcY;
        if (zCam > 0.1f) {
            ndcX = xCam / (zCam * tanX);
            ndcY = yCam / (zCam * TAN_HALF_FOV_Y);
        } else {
            ndcX = -xCam;
            ndcY = -yCam;
            float m = Math.max(Math.abs(ndcX), Math.abs(ndcY));
            if (m > 0) { ndcX /= m; ndcY /= m; }
        }

        if (Math.abs(ndcX) > 1f || Math.abs(ndcY) > 1f) {
            float s = Math.min(1f / Math.abs(ndcX), 1f / Math.abs(ndcY));
            ndcX *= s;
            ndcY *= s;
        }

        int w = rootView != null ? rootView.getWidth() : 0;
        int h = rootView != null ? rootView.getHeight() : 0;
        if (w == 0 || h == 0) return;

        float sx = w * (0.5f + 0.5f * ndcX);
        float sy = h * (0.5f - 0.5f * ndcY);

        int iw = offscreenIndicator.getWidth();
        int ih = offscreenIndicator.getHeight();
        if (iw == 0) iw = 48;
        if (ih == 0) ih = 48;

        offscreenIndicator.setX(sx - iw / 2f);
        offscreenIndicator.setY(sy - ih / 2f);

        float angle = (float) Math.toDegrees(Math.atan2(-ndcY, ndcX));
        offscreenIndicator.setRotation(angle);
    }

    public boolean isDroneVisible(float fx, float fy, float fz, float ux, float uy, float uz) {
        if (!hasRelativePosition || arRenderer == null) return false;

        float rx = fy * uz - fz * uy;
        float ry = fz * ux - fx * uz;
        float rz = fx * uy - fy * ux;

        float dx = lastDroneX;
        float dy = lastDroneY - EYE_HEIGHT;
        float dz = lastDroneZ;

        float zCam = dx*fx + dy*fy + dz*fz;
        float xCam = dx*rx + dy*ry + dz*rz;
        float yCam = dx*ux + dy*uy + dz*uz;

        float aspect = arRenderer.getAspectRatio();
        float tanX = aspect * TAN_HALF_FOV_Y;

        return zCam > 0.1f &&
                Math.abs(xCam) < zCam * tanX &&
                Math.abs(yCam) < zCam * TAN_HALF_FOV_Y;
    }

    public Filament3DRenderer getArRenderer() {
        return arRenderer;
    }

    public void setCardinalCubesVisible(boolean visible) {
        if (arRenderer != null) {
            arRenderer.setCardinalCubesVisible(visible);
        }
    }

    public boolean isCardinalCubesLoaded() {
        return arRenderer != null && arRenderer.isCardinalCubesLoaded();
    }

    public void updateCardinalCubes(float yawRadians) {
        if (arRenderer != null) {
            arRenderer.updateCardinalCubes(yawRadians);
        }
    }

    public float getLastDroneX() { return lastDroneX; }
    public float getLastDroneY() { return lastDroneY; }
    public float getLastDroneZ() { return lastDroneZ; }
    public boolean hasRelativePosition() { return hasRelativePosition; }
    
    public void setHasRelativePosition(boolean hasRelativePosition) {
        this.hasRelativePosition = hasRelativePosition;
    }

    public float getModelRadius() {
        return arRenderer != null ? arRenderer.getModelRadius() : 0.5f;
    }
}
