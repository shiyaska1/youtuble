package com.schoolattendance.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "class_sections")
data class ClassSection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
