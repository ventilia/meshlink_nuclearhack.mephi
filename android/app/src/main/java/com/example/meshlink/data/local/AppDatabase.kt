package com.example.meshlink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.meshlink.data.local.account.AccountDAO
import com.example.meshlink.data.local.account.AccountEntity
import com.example.meshlink.data.local.alias.AliasDAO
import com.example.meshlink.data.local.alias.AliasEntity
import com.example.meshlink.data.local.message.MessageDAO
import com.example.meshlink.data.local.message.MessageEntity
import com.example.meshlink.data.local.profile.ProfileDAO
import com.example.meshlink.data.local.profile.ProfileEntity

@Database(
    entities = [
        AccountEntity::class,
        ProfileEntity::class,
        MessageEntity::class,
        AliasEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDAO
    abstract fun profileDao(): ProfileDAO
    abstract fun messageDao(): MessageDAO
    abstract fun aliasDao(): AliasDAO

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Миграция 1→2: добавляем таблицу AliasEntity */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS AliasEntity (
                        peerId TEXT NOT NULL PRIMARY KEY,
                        alias TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "meshlink.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
