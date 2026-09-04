package com.ytsaver.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedDocumentDao {

    @Query("SELECT * FROM scanned_documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScannedDocument>>

    @Insert
    suspend fun insert(document: ScannedDocument): Long

    @Delete
    suspend fun delete(document: ScannedDocument)

    @Query("UPDATE scanned_documents SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)
}
