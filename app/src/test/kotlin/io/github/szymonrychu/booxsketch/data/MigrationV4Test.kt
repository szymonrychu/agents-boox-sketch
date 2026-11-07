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
class MigrationV4Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-v4-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        val db = createDbFromSchema(context, dbName, 3)
        db.execSQL("INSERT INTO conversations VALUES ('c1', 's1', 'T', 1, 2, 0)")
        db.execSQL("INSERT INTO specs VALUES ('s1', 1, '# hi', '2026-11-04T00:00:00Z', '[]')")
        db.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun layout_column_added_and_null() =
        runBlocking {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.MIGRATIONS).build()
            try {
                val old = db.specDao().latest("s1")!!
                assertThat(old.markdown).isEqualTo("# hi")
                assertThat(old.layout).isNull()
                db.specDao().upsert(SpecEntity("s1", 2, "# two", "2026-11-05T00:00:00Z", layout = "{\"lines\":[]}"))
                assertThat(db.specDao().get("s1", 2)!!.layout).isEqualTo("{\"lines\":[]}")
            } finally {
                db.close()
            }
        }
}
