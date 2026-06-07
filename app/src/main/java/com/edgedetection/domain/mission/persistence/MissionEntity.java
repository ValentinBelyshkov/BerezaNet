package com.edgedetection.domain.mission.persistence;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "missions")
public class MissionEntity {
    @PrimaryKey
    @NonNull
    public String id;
    public String name;
    public String jsonData;

    public MissionEntity(@NonNull String id, String name, String jsonData) {
        this.id = id;
        this.name = name;
        this.jsonData = jsonData;
    }
}
