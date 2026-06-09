package com.edgedetection.core.camera;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Manager class to handle switching between different camera sources.
 */
public class CameraManager {
    private final MutableLiveData<CameraSource> currentSource = new MutableLiveData<>();
    private final CameraSource internalSource;
    private final CameraSource externalSource;

    public CameraManager(CameraSource internalSource, CameraSource externalSource) {
        this.internalSource = internalSource;
        this.externalSource = externalSource;
        this.currentSource.setValue(internalSource);
    }

    public LiveData<CameraSource> getCurrentSource() {
        return currentSource;
    }

    public void switchToInternal() {
        if (currentSource.getValue() != internalSource) {
            stopCurrent();
            currentSource.setValue(internalSource);
        }
    }

    public void switchToExternal() {
        if (currentSource.getValue() != externalSource) {
            stopCurrent();
            currentSource.setValue(externalSource);
        }
    }

    public void toggleSource() {
        if (currentSource.getValue() == internalSource) {
            switchToExternal();
        } else {
            switchToInternal();
        }
    }

    private void stopCurrent() {
        CameraSource source = currentSource.getValue();
        if (source != null && source.isRunning()) {
            source.stop();
        }
    }
}
