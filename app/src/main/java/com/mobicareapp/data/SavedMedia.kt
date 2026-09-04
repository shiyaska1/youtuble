package com.mobicareapp.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType { VIDEO, AUDIO }

/**
 * One row per file saved to phone storage. [filePath] points at the actual
 * media file under the app's private media directory; [caption] is the
 * user-visible name (defaults to the YouTube video title).
 */
@Entity(
    tableName = "saved_media",
    foreignKeys = [
        ForeignKey(
            entity = MediaCategory::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("categoryId")]
)
data class SavedMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caption: String,
    val sourceUrl: String,
    val type: MediaType,
    /** Either a plain file path (legacy/API<29) or a content:// MediaStore URI string. */
    val filePath: String,
    val fileName: String,
    val thumbnailUrl: String?,
    val sizeBytes: Long,
    val durationSeconds: Long,
    val createdAt: Long,
    val categoryId: Long? = null
)
