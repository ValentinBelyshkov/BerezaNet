package com.edgedetection.domain.ballistics;

/**
 * Публичная модель калибровочной точки упреждения.
 * Хранит нормализованные координаты (0..1) на экране, куда
 * пользователь поместил точку упреждения.
 */
public class CalibrationPoint {
    /** Нормализованная X (0 = лево, 1 = право) */
    public final float x;
    /** Нормализованная Y (0 = верх, 1 = низ) */
    public final float y;
    /** Зафиксирована ли точка (после подтверждения) */
    public final boolean confirmed;

    public CalibrationPoint(float x, float y, boolean confirmed) {
        this.x = x;
        this.y = y;
        this.confirmed = confirmed;
    }

    public CalibrationPoint withPosition(float x, float y) {
        return new CalibrationPoint(x, y, false);
    }

    public CalibrationPoint withConfirmed(boolean confirmed) {
        return new CalibrationPoint(x, y, confirmed);
    }
}