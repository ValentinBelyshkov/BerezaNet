package com.edgedetection.ui.planner;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * ViewModel только для UI-логики экрана планировщика.
 * Данные миссии (waypoints, anchors) — в MissionViewModel (shared, Activity).
 */
public class MissionPlannerViewModel extends ViewModel {

    private final MutableLiveData<PlannerUiState> uiState = new MutableLiveData<>(PlannerUiState.defaultState());

    public LiveData<PlannerUiState> getUiState() {
        return uiState;
    }

    // --- Режимы ---

    public void setEditorMode(EditorMode mode) {
        update(s -> s.withEditorMode(mode));
    }

    public void setToolMode(ToolMode mode) {
        update(s -> s.withToolMode(mode));
    }

    // --- Выбор ---

    public void selectWaypoint(String id) {
        update(s -> s.withSelection(id, null));
    }

    public void selectAnchor(String id) {
        update(s -> s.withSelection(null, id));
    }

    public void clearSelection() {
        update(s -> s.withSelection(null, null));
    }

    // --- Камера 3D ---

    public void moveCamera(Camera3dState camera) {
        update(s -> s.withCamera(camera));
    }

    public void orbitCamera(double yawDelta, double pitchDelta) {
        update(s -> {
            Camera3dState c = s.camera3d;
            double newYaw = (c.yawDegrees + yawDelta) % 360.0;
            double newPitch = Math.max(0, Math.min(89, c.pitchDegrees + pitchDelta));
            return s.withCamera(c.withOrbit(newYaw, newPitch));
        });
    }

    public void zoomCamera(double distanceDelta) {
        update(s -> {
            Camera3dState c = s.camera3d;
            double newDist = Math.max(10, c.distance + distanceDelta);
            return s.withCamera(c.withDistance(newDist));
        });
    }

    public void focusOn(double east, double north, double up) {
        update(s -> s.withCamera(s.camera3d.withTarget(east, north, up)));
    }

    // --- Видимость слоёв ---

    public void toggleTerrain() {
        update(s -> s.withVisibility(!s.showTerrain, s.showGeoFence, s.showWaypoints, s.showAnchors));
    }

    public void toggleGeoFence() {
        update(s -> s.withVisibility(s.showTerrain, !s.showGeoFence, s.showWaypoints, s.showAnchors));
    }

    public void toggleWaypoints() {
        update(s -> s.withVisibility(s.showTerrain, s.showGeoFence, !s.showWaypoints, s.showAnchors));
    }

    public void toggleAnchors() {
        update(s -> s.withVisibility(s.showTerrain, s.showGeoFence, s.showWaypoints, !s.showAnchors));
    }

    // --- Состояние ---

    public void setLoading(boolean loading) {
        update(s -> s.withLoading(loading));
    }

    public void setError(String error) {
        update(s -> s.withError(error));
    }

    public void clearError() {
        update(s -> s.withError(null));
    }

    // --- Utils ---

    private void update(StateTransform transform) {
        PlannerUiState current = uiState.getValue();
        if (current == null) return;
        uiState.setValue(transform.apply(current));
    }

    private interface StateTransform {
        PlannerUiState apply(PlannerUiState state);
    }
}