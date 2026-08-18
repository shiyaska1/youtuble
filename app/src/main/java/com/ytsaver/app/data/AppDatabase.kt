package com.ytsaver.app.data

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
        db.execSQL("ALTER TABLE saved_media ADD COLUMN categoryId INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_media_categoryId ON saved_media(categoryId)")
    }
}

@Database(entities = [SavedMedia::class, MediaCategory::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun savedMediaDao(): SavedMediaDao
    abstract fun mediaCategoryDao(): MediaCategoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ytsaver.db"
            )
                .addMigrations(MIGRATION_2_3)
                // Covers anyone still on the pre-migration schema (version 1) — everyone on
                // version 2+ goes through the real migration above and keeps their library.
                .fallbackToDestructiveMigration()
                .build()
    }
}
