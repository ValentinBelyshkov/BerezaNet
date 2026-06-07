package com.edgedetection.domain.mission;

import androidx.lifecycle.LiveData;

import java.util.List;

public interface MissionRepository {
    LiveData<Mission> observeMission();
    Mission load(String missionId);
    void save(Mission mission);
    List<Mission> getAllMissions();
    void delete(String missionId);
}