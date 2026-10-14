package no.oystein.bokhylle.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Book::class, Page::class, ApiUsage::class, ClaudeJob::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun pages(): PageDao
    abstract fun claude(): ClaudeDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bokhylle.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** 1.0.109 → eget omslagsbilde per bok. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN omslagBilde TEXT")
            }
        }
    }
}
