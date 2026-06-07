package com.edgedetection.ui.map3d;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.maplibre.android.camera.CameraPosition;

public class Map3DViewModel extends ViewModel {
    private final MutableLiveData<CameraPosition> cameraPosition = new MutableLiveData<>();

    public void setCameraPosition(CameraPosition pos) {
        cameraPosition.setValue(pos);
    }

    public LiveData<CameraPosition> getCameraPosition() {
        return cameraPosition;
    }
}