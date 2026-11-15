package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HierarchyNodeEntity::class,
        AgentScriptEntity::class,
        AgentExecutionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class XcraftDatabase : RoomDatabase() {
    abstract fun hierarchyNodeDao(): HierarchyNodeDao
    abstract fun agentScriptDao(): AgentScriptDao
    abstract fun agentExecutionLogDao(): AgentExecutionLogDao

    companion object {
        private const val DB_NAME = "xcraft_sovereign_workspace.db"

        @Volatile
        private var instance: XcraftDatabase? = null

        fun getInstance(context: Context): XcraftDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    XcraftDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }

        fun createInMemory(context: Context): XcraftDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                XcraftDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
