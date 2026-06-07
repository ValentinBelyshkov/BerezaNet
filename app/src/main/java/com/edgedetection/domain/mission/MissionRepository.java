package com.edgedetection.domain.mission;

import androidx.lifecycle.LiveData;

public interface MissionRepository {
    LiveData<Mission> observeMission();
    Mission load(String missionId);
    void save(Mission mission);
}