package com.edgedetection.app;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.edgedetection.core.ScreenMode;

public class AppViewModel extends ViewModel {
    private final MutableLiveData<ScreenMode> screenMode =
            new MutableLiveData<>(ScreenMode.FULL_BATTLE);

    // Сюда потом можно добавить глобальные штуки:
    // состояние подключения к дрону, текущую миссию, GPS-координаты и т.д.
    private final MutableLiveData<Boolean> droneConnected = new MutableLiveData<>(false);

    public void setScreenMode(ScreenMode mode) {
        screenMode.setValue(mode);
    }

    public LiveData<ScreenMode> getScreenMode() {
        return screenMode;
    }

    public void setDroneConnected(boolean connected) {
        droneConnected.setValue(connected);
    }

    public LiveData<Boolean> isDroneConnected() {
        return droneConnected;
    }
}