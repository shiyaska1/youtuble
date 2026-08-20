package com.schoolattendance.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(status: AttendanceStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): AttendanceStatus = AttendanceStatus.valueOf(value)
}
