package com.edgedetection.domain.mission;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class InMemoryMissionRepository implements MissionRepository {
    private final MutableLiveData<Mission> missionData;

    public InMemoryMissionRepository(Mission initial) {
        if (initial == null) {
            initial = new Mission(UUID.randomUUID().toString(), "New Mission",
                    Collections.emptyList(), Collections.emptyList(),
                    null, null, null, null);
        }
        this.missionData = new MutableLiveData<>(initial);
    }

    @Override
    public LiveData<Mission> observeMission() {
        return missionData;
    }

    @Override
    public Mission load(String missionId) {
        Mission m = missionData.getValue();
        return (m != null && m.id.equals(missionId)) ? m : null;
    }

    @Override
    public List<Mission> getAllMissions() {
        Mission m = missionData.getValue();
        return m != null ? Collections.singletonList(m) : Collections.emptyList();
    }

    @Override
    public void save(Mission mission) {
        missionData.setValue(mission);
    }

    @Override
    public void delete(String missionId) {
        // Not implemented for in-memory
    }
}