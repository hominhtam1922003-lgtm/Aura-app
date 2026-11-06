package com.example.nysa.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TitleEntity::class, UserStateEntity::class], version = 1, exportSchema = false)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun titleDao(): TitleDao
    abstract fun userStateDao(): UserStateDao
}
