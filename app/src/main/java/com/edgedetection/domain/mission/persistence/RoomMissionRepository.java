package com.edgedetection.domain.mission.persistence;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.edgedetection.domain.mission.Mission;
import com.edgedetection.domain.mission.MissionRepository;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

public class RoomMissionRepository implements MissionRepository {
    private final MissionDao missionDao;
    private final Gson gson;
    private final MutableLiveData<Mission> activeMission = new MutableLiveData<>();

    public RoomMissionRepository(MissionDao missionDao) {
        this.missionDao = missionDao;
        this.gson = new Gson();
    }

    @Override
    public LiveData<Mission> observeMission() {
        return activeMission;
    }

    @Override
    public Mission load(String missionId) {
        for (MissionEntity entity : missionDao.getAll()) {
            if (entity.id.equals(missionId)) {
                return gson.fromJson(entity.jsonData, Mission.class);
            }
        }
        return null;
    }

    @Override
    public void save(Mission mission) {
        String json = gson.toJson(mission);
        missionDao.insert(new MissionEntity(mission.id, mission.name, json));
        activeMission.postValue(mission);
    }

    @Override
    public List<Mission> getAllMissions() {
        List<Mission> missions = new ArrayList<>();
        for (MissionEntity entity : missionDao.getAll()) {
            missions.add(gson.fromJson(entity.jsonData, Mission.class));
        }
        return missions;
    }

    @Override
    public void delete(String missionId) {
        missionDao.deleteById(missionId);
    }
}
