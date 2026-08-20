package com.schoolattendance.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassDao {
    @Query("SELECT * FROM class_sections ORDER BY name ASC")
    fun observeAll(): Flow<List<ClassSection>>

    @Insert
    suspend fun insert(classSection: ClassSection): Long

    @Delete
    suspend fun delete(classSection: ClassSection)
}
