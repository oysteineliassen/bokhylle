package no.oystein.bokhylle.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Book::class, Page::class, ApiUsage::class, ClaudeJob::class, Marking::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun pages(): PageDao
    abstract fun claude(): ClaudeDao
    abstract fun markings(): MarkingDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bokhylle.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** 1.0.109 → eget omslagsbilde per bok. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN omslagBilde TEXT")
            }
        }

        /** Lesepunkt midt på en side, bokmerke og markert tekst. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sistLestBlokk INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN sistLestTegn INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `markeringer` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` INTEGER NOT NULL, `type` TEXT NOT NULL, `sideId` INTEGER NOT NULL, `blokk` INTEGER NOT NULL, " +
                        "`tegn` INTEGER NOT NULL, `sluttSideId` INTEGER NOT NULL, `sluttBlokk` INTEGER NOT NULL, " +
                        "`sluttTegn` INTEGER NOT NULL, `tekst` TEXT NOT NULL, `opprettet` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_markeringer_bookId` ON `markeringer` (`bookId`)")
            }
        }
    }
}
