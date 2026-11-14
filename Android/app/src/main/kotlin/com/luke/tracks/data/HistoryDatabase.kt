package com.luke.tracks.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PlayEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun plays(): PlayDao

    companion object {
        fun open(context: Context, name: String): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
