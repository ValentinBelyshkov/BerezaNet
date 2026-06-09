package com.edgedetection.ui.battle;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.edgedetection.domain.ballistics.CalibrationPoint;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

public class BattleViewModel extends AndroidViewModel {
    private static final String TAG = "BattleVM";

    private Mat edges;

    private final MutableLiveData<Integer> lowerThreshold = new MutableLiveData<>(50);
    private final MutableLiveData<Integer> upperThreshold = new MutableLiveData<>(150);
    private final MutableLiveData<Integer> blurValue = new MutableLiveData<>(5);
    private final MutableLiveData<Double> fps = new MutableLiveData<>(0.0);

    // --- Калибровка точки упреждения ---
    private final MutableLiveData<Boolean> calibrationActive = new MutableLiveData<>(false);
    private final MutableLiveData<CalibrationPoint> calibrationPoint = new MutableLiveData<>(null);

    public BattleViewModel(@NonNull Application application) {
        super(application);
    }

    public void initMats(int width, int height) {
        if (edges == null) {
            edges = new Mat(height, width, CvType.CV_8UC4);
            Log.i(TAG, "Edges Mat created: " + width + "x" + height);
        }
    }

    public Mat getEdges() {
        return edges;
    }

    public void releaseMats() {
        if (edges != null) {
            edges.release();
            edges = null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        releaseMats();
        Log.i(TAG, "BattleViewModel cleared, Mats released");
    }

    // ===================== Калибровка =====================

    /** Начать или отменить калибровку */
    public void startCalibration() {
        calibrationActive.setValue(true);
        calibrationPoint.setValue(null);
        Log.d(TAG, "Calibration started");
    }

    public void cancelCalibration() {
        calibrationActive.setValue(false);
        calibrationPoint.setValue(null);
        Log.d(TAG, "Calibration cancelled");
    }

    /** Установить позицию точки упреждения (нормализованные координаты 0..1) */
    public void setCalibrationPosition(float nx, float ny) {
        CalibrationPoint current = calibrationPoint.getValue();
        if (current != null && current.confirmed) return; // уже подтверждено, не меняем
        CalibrationPoint cp = new CalibrationPoint(nx, ny, false);
        calibrationPoint.setValue(cp);
        Log.d(TAG, "Calibration position set: (" + nx + ", " + ny + ")");
    }

    /** Подтвердить и зафиксировать калибровочную точку */
    public void confirmCalibration() {
        CalibrationPoint current = calibrationPoint.getValue();
        if (current == null) return;
        calibrationPoint.setValue(current.withConfirmed(true));
        calibrationActive.setValue(false);
        Log.d(TAG, "Calibration confirmed: (" + current.x + ", " + current.y + ")");
    }

    /** Публичный LiveData для доступа из других компонентов */
    public LiveData<Boolean> isCalibrationActive() {
        return calibrationActive;
    }

    /** Публичный LiveData с калибровочной точкой */
    public LiveData<CalibrationPoint> getCalibrationPoint() {
        return calibrationPoint;
    }

    // ===================== Edge Detection =====================

    public MutableLiveData<Integer> getLowerThreshold() {
        return lowerThreshold;
    }

    public MutableLiveData<Integer> getUpperThreshold() {
        return upperThreshold;
    }

    public MutableLiveData<Integer> getBlurValue() {
        return blurValue;
    }

    public MutableLiveData<Double> getFps() {
        return fps;
    }

    public void setFps(double value) {
        fps.postValue(value);
    }
}