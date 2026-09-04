package com.mobicareapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")

        // SQLite can't add a foreign key constraint via plain ALTER TABLE, and Room's
        // migration validator requires the resulting schema to match the @ForeignKey on
        // SavedMedia exactly — so the table has to be rebuilt with the constraint present,
        // not just have the column added.
        db.execSQL(
            """
            CREATE TABLE saved_media_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                caption TEXT NOT NULL,
                sourceUrl TEXT NOT NULL,
                type TEXT NOT NULL,
                filePath TEXT NOT NULL,
                fileName TEXT NOT NULL,
                thumbnailUrl TEXT,
                sizeBytes INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                categoryId INTEGER,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO saved_media_new
                (id, caption, sourceUrl, type, filePath, fileName, thumbnailUrl, sizeBytes, durationSeconds, createdAt, categoryId)
            SELECT id, caption, sourceUrl, type, filePath, fileName, thumbnailUrl, sizeBytes, durationSeconds, createdAt, NULL
            FROM saved_media
            """.trimIndent()
        )
        db.execSQL("DROP TABLE saved_media")
        db.execSQL("ALTER TABLE saved_media_new RENAME TO saved_media")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_media_categoryId ON saved_media(categoryId)")
    }
}

/**
 * Recovers any device that already hit the earlier broken MIGRATION_2_3 (which added
 * categoryId without the foreign key Room's schema validator requires, crash-looping on
 * open). Whether a given device is stuck at version 2 or already limped to a broken
 * version 3, unconditionally rebuilding the table here lands everyone on a schema Room
 * accepts. Any category assignment made during that broken window can't be recovered and
 * is dropped; captions, files, sizes, and dates are not affected.
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE saved_media_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                caption TEXT NOT NULL,
                sourceUrl TEXT NOT NULL,
                type TEXT NOT NULL,
                filePath TEXT NOT NULL,
                fileName TEXT NOT NULL,
                thumbnailUrl TEXT,
                sizeBytes INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                categoryId INTEGER,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO saved_media_new
                (id, caption, sourceUrl, type, filePath, fileName, thumbnailUrl, sizeBytes, durationSeconds, createdAt, categoryId)
            SELECT id, caption, sourceUrl, type, filePath, fileName, thumbnailUrl, sizeBytes, durationSeconds, createdAt, NULL
            FROM saved_media
            """.trimIndent()
        )
        db.execSQL("DROP TABLE saved_media")
        db.execSQL("ALTER TABLE saved_media_new RENAME TO saved_media")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_media_categoryId ON saved_media(categoryId)")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scanned_documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                pageCount INTEGER NOT NULL,
                filePath TEXT NOT NULL,
                thumbnailPath TEXT,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scanned_documents ADD COLUMN pagePaths TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [SavedMedia::class, MediaCategory::class, ScannedDocument::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun savedMediaDao(): SavedMediaDao
    abstract fun mediaCategoryDao(): MediaCategoryDao
    abstract fun scannedDocumentDao(): ScannedDocumentDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ytsaver.db"
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // Covers anyone still on the pre-migration schema (version 1) — everyone on
                // version 2+ goes through the real migration above and keeps their library.
                .fallbackToDestructiveMigration()
                .build()
    }
}
