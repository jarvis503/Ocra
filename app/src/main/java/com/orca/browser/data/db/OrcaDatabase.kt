package com.orca.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.orca.browser.data.model.HistoryEntry
import com.orca.browser.data.model.Tab

@Database(
    entities = [
        Tab::class,
        HistoryEntry::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class OrcaDatabase : RoomDatabase() {

    abstract fun tabDao(): TabDao

    abstract fun historyDao(): HistoryDao

    companion object {
        private const val DATABASE_NAME = "orca_browser.db"

        @Volatile
        private var INSTANCE: OrcaDatabase? = null

        fun getInstance(context: Context): OrcaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrcaDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
