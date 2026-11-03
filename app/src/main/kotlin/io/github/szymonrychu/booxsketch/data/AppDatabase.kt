package io.github.szymonrychu.booxsketch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        OutboxEntity::class,
        ConversationEntity::class,
        ThreadItemEntity::class,
        SpecEntity::class,
        CursorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    abstract fun conversationDao(): ConversationDao

    abstract fun threadDao(): ThreadDao

    abstract fun specDao(): SpecDao

    abstract fun cursorDao(): CursorDao

    companion object {
        fun open(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "agents-boox-sketch.db").build()
    }
}
