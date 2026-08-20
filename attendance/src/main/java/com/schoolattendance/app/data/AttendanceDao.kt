package com.schoolattendance.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_records WHERE classId = :classId AND date = :date")
    fun observeForClassAndDate(classId: Long, date: Long): Flow<List<AttendanceRecord>>

    /** Every record ever taken for a class — used to compute per-student attendance percentages. */
    @Query("SELECT * FROM attendance_records WHERE classId = :classId ORDER BY date ASC")
    fun observeForClass(classId: Long): Flow<List<AttendanceRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AttendanceRecord)
}
