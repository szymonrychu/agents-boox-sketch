package io.github.szymonrychu.booxsketch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        OutboxEntity::class,
        ConversationEntity::class,
        ThreadItemEntity::class,
        SpecEntity::class,
        CursorEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    abstract fun conversationDao(): ConversationDao

    abstract fun threadDao(): ThreadDao

    abstract fun specDao(): SpecDao

    abstract fun cursorDao(): CursorDao

    companion object {
        /** Every migration, in order. There is no destructive fallback: a missing step must fail loudly. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun open(context: Context): AppDatabase =
            Room
                .databaseBuilder(context, AppDatabase::class.java, "agents-boox-sketch.db")
                .apply { MIGRATIONS.forEach(::addMigrations) }
                .build()
    }
}

/** Adds the spec's source sketch ids and the thread item's connector sketch id. */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE specs ADD COLUMN sketchIds TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE thread_items ADD COLUMN serverId TEXT")
        }
    }
