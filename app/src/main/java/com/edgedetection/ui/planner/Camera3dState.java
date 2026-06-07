package com.edgedetection.ui.planner;

/**
 * Orbit-камера для 3D-вида планировщика. Хранится в ENU (метры).
 */
public final class Camera3dState {
    public final double targetEast;   // точка взгляда
    public final double targetNorth;
    public final double targetUp;
    public final double distance;     // метры от target
    public final double yawDegrees;   // 0 = север, 90 = восток
    public final double pitchDegrees; // 0 = горизонт, 90 = вертикально вниз

    public Camera3dState(double targetEast, double targetNorth, double targetUp,
                         double distance, double yawDegrees, double pitchDegrees) {
        this.targetEast = targetEast;
        this.targetNorth = targetNorth;
        this.targetUp = targetUp;
        this.distance = distance;
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
    }

    public Camera3dState withTarget(double e, double n, double u) {
        return new Camera3dState(e, n, u, distance, yawDegrees, pitchDegrees);
    }

    public Camera3dState withDistance(double distance) {
        return new Camera3dState(targetEast, targetNorth, targetUp, distance, yawDegrees, pitchDegrees);
    }

    public Camera3dState withOrbit(double yaw, double pitch) {
        return new Camera3dState(targetEast, targetNorth, targetUp, distance, yaw, pitch);
    }
}