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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationV3Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-v3-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        val db = createDbFromSchema(context, dbName, 2)
        db.execSQL("INSERT INTO conversations VALUES ('c1', 's1', 'T', 1, 2, 0)")
        db.execSQL("INSERT INTO specs VALUES ('s1', 1, '# hi', '2026-11-04T00:00:00Z', '[]')")
        db.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_2_3_adds_feedback_and_keeps_rows() =
        runBlocking {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.MIGRATIONS).build()
            try {
                assertThat(db.specDao().latest("s1")!!.markdown).isEqualTo("# hi")
                assertThat(db.feedbackDao().maxId("s1")).isNull()
                db.feedbackDao().upsert(FeedbackEntity("s1", 3, "question", "Which box?", null, 1, "2026-11-04T00:00:00Z"))
                assertThat(db.feedbackDao().maxId("s1")).isEqualTo(3)
            } finally {
                db.close()
            }
        }
}
