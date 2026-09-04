package com.ytsaver.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scanned_documents")
data class ScannedDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pageCount: Int,
    val filePath: String,
    val thumbnailPath: String?,
    val createdAt: Long,
    /** Each page's own standalone JPG, in order, so a single page can be shared without the whole PDF. */
    val pagePaths: List<String> = emptyList()
)
