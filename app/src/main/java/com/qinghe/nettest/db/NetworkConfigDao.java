package com.qinghe.nettest.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.qinghe.nettest.model.NetworkConfig;

import java.util.List;

@Dao
public interface NetworkConfigDao {

    @Query("SELECT * FROM network_configs")
    List<NetworkConfig> getAll();

    @Query("SELECT * FROM network_configs WHERE id = :id")
    NetworkConfig getById(int id);

    @Insert
    long insert(NetworkConfig config);

    @Update
    void update(NetworkConfig config);

    @Delete
    void delete(NetworkConfig config);
}
