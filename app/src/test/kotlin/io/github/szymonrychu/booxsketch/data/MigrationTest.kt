package io.github.szymonrychu.booxsketch.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.testing.createDbFromSchema
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        createV1()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Builds a version 1 database file from the exported 1.json schema. */
    private fun createV1() {
        val db = createDbFromSchema(context, dbName, 1)
        db.execSQL("INSERT INTO conversations VALUES ('c1', 's1', 'T', 1, 2, 0)")
        db.execSQL("INSERT INTO specs VALUES ('s1', 1, '# hi', '2026-11-04T00:00:00Z')")
        db.execSQL("INSERT INTO thread_items VALUES ('i1', 'c1', 'SKETCH', 'cid-1', NULL, 1, NULL, 'queued', 5)")
        db.version = 1
        db.close()
    }

    @Test
    fun migration_1_2_adds_columns_and_keeps_rows() =
        runBlocking {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.MIGRATIONS).build()
            try {
                val spec = db.specDao().latest("s1")!!
                assertThat(spec.markdown).isEqualTo("# hi")
                assertThat(spec.sketchIds).isEqualTo("[]")
                val item = db.threadDao().list("c1").single()
                assertThat(item.clientId).isEqualTo("cid-1")
                assertThat(item.serverId).isNull()
            } finally {
                db.close()
            }
        }

    @Test
    fun open_has_no_destructive_fallback() {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        try {
            val result = runCatching { runBlocking { db.specDao().latest("s1") } }
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        } finally {
            db.close()
        }
        assertThat(File(context.getDatabasePath(dbName).path).exists()).isTrue()
    }

    @Test
    fun setServerId_and_byServerId_roundtrip() =
        runBlocking {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.MIGRATIONS).build()
            try {
                db.threadDao().setServerId("cid-1", "sk-9")
                assertThat(db.threadDao().byServerId("sk-9")!!.id).isEqualTo("i1")
                assertThat(db.threadDao().byServerId("nope")).isNull()
            } finally {
                db.close()
            }
        }
}
