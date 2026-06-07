package com.edgedetection.ui.planner;

/**
 * Immutable UI-стейт планировщика. Не содержит данных миссии — только то,
 * как эти данные отображаются и что сейчас редактируется.
 */
public final class PlannerUiState {
    public final EditorMode editorMode;
    public final ToolMode toolMode;
    public final String selectedWaypointId;   // null = ничего не выбрано
    public final String selectedAnchorId;
    public final Camera3dState camera3d;
    public final boolean showTerrain;
    public final boolean showGeoFence;
    public final boolean showWaypoints;
    public final boolean showAnchors;
    public final boolean isLoading;
    public final String errorMessage;

    public PlannerUiState(EditorMode editorMode, ToolMode toolMode,
                          String selectedWaypointId, String selectedAnchorId,
                          Camera3dState camera3d,
                          boolean showTerrain, boolean showGeoFence,
                          boolean showWaypoints, boolean showAnchors,
                          boolean isLoading, String errorMessage) {
        this.editorMode = editorMode;
        this.toolMode = toolMode;
        this.selectedWaypointId = selectedWaypointId;
        this.selectedAnchorId = selectedAnchorId;
        this.camera3d = camera3d;
        this.showTerrain = showTerrain;
        this.showGeoFence = showGeoFence;
        this.showWaypoints = showWaypoints;
        this.showAnchors = showAnchors;
        this.isLoading = isLoading;
        this.errorMessage = errorMessage;
    }

    // Фабрика дефолтного состояния
    public static PlannerUiState defaultState() {
        return new PlannerUiState(
                EditorMode.MODE_2D,
                ToolMode.TOOL_SELECT,
                null, null,
                new Camera3dState(0, 0, 0, 500, 0, 60),
                true, true, true, true,
                false, null
        );
    }

    // with-методы для копирования
    public PlannerUiState withEditorMode(EditorMode mode) {
        return new PlannerUiState(mode, toolMode, selectedWaypointId, selectedAnchorId,
                camera3d, showTerrain, showGeoFence, showWaypoints, showAnchors, isLoading, errorMessage);
    }

    public PlannerUiState withToolMode(ToolMode mode) {
        return new PlannerUiState(editorMode, mode, selectedWaypointId, selectedAnchorId,
                camera3d, showTerrain, showGeoFence, showWaypoints, showAnchors, isLoading, errorMessage);
    }

    public PlannerUiState withSelection(String waypointId, String anchorId) {
        return new PlannerUiState(editorMode, toolMode, waypointId, anchorId,
                camera3d, showTerrain, showGeoFence, showWaypoints, showAnchors, isLoading, errorMessage);
    }

    public PlannerUiState withCamera(Camera3dState camera) {
        return new PlannerUiState(editorMode, toolMode, selectedWaypointId, selectedAnchorId,
                camera, showTerrain, showGeoFence, showWaypoints, showAnchors, isLoading, errorMessage);
    }

    public PlannerUiState withVisibility(boolean terrain, boolean geoFence, boolean waypoints, boolean anchors) {
        return new PlannerUiState(editorMode, toolMode, selectedWaypointId, selectedAnchorId,
                camera3d, terrain, geoFence, waypoints, anchors, isLoading, errorMessage);
    }

    public PlannerUiState withLoading(boolean loading) {
        return new PlannerUiState(editorMode, toolMode, selectedWaypointId, selectedAnchorId,
                camera3d, showTerrain, showGeoFence, showWaypoints, showAnchors, loading, errorMessage);
    }

    public PlannerUiState withError(String error) {
        return new PlannerUiState(editorMode, toolMode, selectedWaypointId, selectedAnchorId,
                camera3d, showTerrain, showGeoFence, showWaypoints, showAnchors, isLoading, error);
    }

    public boolean hasSelection() {
        return selectedWaypointId != null || selectedAnchorId != null;
    }
}