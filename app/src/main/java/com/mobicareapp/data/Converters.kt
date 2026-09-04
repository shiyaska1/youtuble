package com.mobicareapp.data

import androidx.room.TypeConverter

private const val PAGE_PATH_DELIMITER = "|||"

class Converters {
    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = MediaType.valueOf(value)

    // Our own generated file names/paths never contain "|||", so it's a safe join delimiter.
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(PAGE_PATH_DELIMITER)

    @TypeConverter
    fun toStringList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(PAGE_PATH_DELIMITER)
}
