package no.oystein.bokhylle.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Book::class, Page::class, ApiUsage::class, ClaudeJob::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun pages(): PageDao
    abstract fun claude(): ClaudeDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bokhylle.db").build()
    }
}
