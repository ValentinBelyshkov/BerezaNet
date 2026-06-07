package com.edgedetection.ui.battle;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

public class BattleViewModel extends AndroidViewModel {
    private static final String TAG = "BattleVM";

    private Mat edges;

    private final MutableLiveData<Integer> lowerThreshold = new MutableLiveData<>(50);
    private final MutableLiveData<Integer> upperThreshold = new MutableLiveData<>(150);
    private final MutableLiveData<Integer> blurValue = new MutableLiveData<>(5);
    private final MutableLiveData<Double> fps = new MutableLiveData<>(0.0);

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