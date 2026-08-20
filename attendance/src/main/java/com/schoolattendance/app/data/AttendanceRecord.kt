package com.schoolattendance.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AttendanceStatus { PRESENT, ABSENT, LATE }

/** [date] is the epoch day (days since 1970-01-01), so one row per student per calendar day. */
@Entity(
    tableName = "attendance_records",
    foreignKeys = [
        ForeignKey(
            entity = Student::class,
            parentColumns = ["id"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ClassSection::class,
            parentColumns = ["id"],
            childColumns = ["classId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["studentId", "date"], unique = true), Index("classId")]
)
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val classId: Long,
    val date: Long,
    val status: AttendanceStatus
)
