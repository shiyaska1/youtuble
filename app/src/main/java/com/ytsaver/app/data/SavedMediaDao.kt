package com.ytsaver.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedMediaDao {

    @Query("SELECT * FROM saved_media ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SavedMedia>>

    @Query("SELECT * FROM saved_media WHERE sourceUrl = :sourceUrl AND type = :type LIMIT 1")
    suspend fun findBySourceUrlAndType(sourceUrl: String, type: MediaType): SavedMedia?

    @Insert
    suspend fun insert(item: SavedMedia): Long

    @Update
    suspend fun update(item: SavedMedia)

    @Delete
    suspend fun delete(item: SavedMedia)
}
