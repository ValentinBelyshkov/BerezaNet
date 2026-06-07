package com.edgedetection.ui.planner;

public enum ToolMode {
    // --- Базовые ---
    TOOL_SELECT,           // Выбор объекта. Клик = открыть редактор в BottomSheet.
    TOOL_MOVE,             // Drag-and-drop waypoint/anchor по карте или в 3D.

    // --- Создание ---
    TOOL_ADD_WAYPOINT,     // Long-press/клик по карте = добавить waypoint.
    TOOL_ADD_ANCHOR,       // Long-press/клик = добавить 3D-якорь (груз/здание).
    TOOL_DUPLICATE,        // Клик по существующей точке = клонировать со смещением +1м.

    // --- Удаление ---
    TOOL_DELETE,           // Клик по waypoint/anchor = удалить без подтверждения (или с Toast).

    // --- Измерения ---
    TOOL_MEASURE_DISTANCE, // Линейка: клик-1 → клик-2, показывает горизонтальное расстояние и азимут.
    TOOL_MEASURE_TERRAIN,  // Клик по карте = показать высоту рельефа (DEM sample) в точке.
    TOOL_MEASURE_PROFILE,  // Построить профиль высот вдоль выбранного сегмента маршрута.

    // --- Геозона ---
    TOOL_DRAW_GEOFENCE,    // Рисование полигона: клики ставят вершины, double-tap = замкнуть.

    // --- Ориентация и траектория ---
    TOOL_SET_HEADING,      // Клик по waypoint + drag = задать yaw/heading (стрелка направления).
    TOOL_EDIT_PATH         // Редактирование типа траектории между точками: прямая / дуга / кривая Безье.
}