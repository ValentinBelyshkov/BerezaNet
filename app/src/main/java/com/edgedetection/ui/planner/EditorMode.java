package com.edgedetection.ui.planner;

public enum EditorMode {
    MODE_2D,      // Только MapLibre
    MODE_3D,      // Только 3D-рендерер (OpenGL/Filament)
    MODE_SPLIT    // Верх — 2D, низ — 3D (или наоборот)
}