package com.mobicareapp.data

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

    @Query("UPDATE saved_media SET caption = :caption WHERE id = :id")
    suspend fun updateCaption(id: Long, caption: String)

    @Query("UPDATE saved_media SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun updateCategory(ids: List<Long>, categoryId: Long?)

    @Insert
    suspend fun insert(item: SavedMedia): Long

    @Update
    suspend fun update(item: SavedMedia)

    @Delete
    suspend fun delete(item: SavedMedia)
}
