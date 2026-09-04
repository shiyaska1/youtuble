package com.mobicareapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-created named group (e.g. "Workout", "Kids") that saved items can be filed under. */
@Entity(tableName = "categories")
data class MediaCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)
