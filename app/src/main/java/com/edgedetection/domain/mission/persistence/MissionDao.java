package com.edgedetection.domain.mission.persistence;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MissionDao {
    @Query("SELECT * FROM missions")
    List<MissionEntity> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(MissionEntity mission);

    @Query("DELETE FROM missions WHERE id = :id")
    void deleteById(String id);
}
