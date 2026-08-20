package com.schoolattendance.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Query("SELECT * FROM students WHERE classId = :classId ORDER BY name ASC")
    fun observeForClass(classId: Long): Flow<List<Student>>

    @Insert
    suspend fun insert(student: Student): Long

    @Delete
    suspend fun delete(student: Student)
}
