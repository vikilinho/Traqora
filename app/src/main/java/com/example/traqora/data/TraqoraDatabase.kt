package com.example.traqora.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class,
        TelemetryEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TraqoraDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var instance: TraqoraDatabase? = null

        fun getInstance(context: Context): TraqoraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TraqoraDatabase::class.java,
                    "traqora.db"
                ).build().also { instance = it }
            }
        }
    }
}
