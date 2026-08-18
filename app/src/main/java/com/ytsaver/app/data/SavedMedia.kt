package com.ytsaver.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType { VIDEO, AUDIO }

/**
 * One row per file saved to phone storage. [filePath] points at the actual
 * media file under the app's private media directory; [caption] is the
 * user-visible name (defaults to the YouTube video title).
 */
@Entity(tableName = "saved_media")
data class SavedMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caption: String,
    val sourceUrl: String,
    val type: MediaType,
    val filePath: String,
    val thumbnailUrl: String?,
    val sizeBytes: Long,
    val durationSeconds: Long,
    val createdAt: Long
)
