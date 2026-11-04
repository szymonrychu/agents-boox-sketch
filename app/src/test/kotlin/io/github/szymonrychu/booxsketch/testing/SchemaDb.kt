package io.github.szymonrychu.booxsketch.testing

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Creates the database file [name] as it was at schema [version], from the exported `<version>.json`, and returns it open. */
fun createDbFromSchema(
    context: Context,
    name: String,
    version: Int,
): android.database.sqlite.SQLiteDatabase {
    val schema =
        Json
            .parseToJsonElement(File("schemas/io.github.szymonrychu.booxsketch.data.AppDatabase/$version.json").readText())
            .jsonObject["database"]!!
            .jsonObject
    val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
    for (entity in schema["entities"]!!.jsonArray) {
        val e = entity.jsonObject
        val table = e["tableName"]!!.jsonPrimitive.content
        db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        e["indices"]?.jsonArray?.forEach {
            db.execSQL(
                it.jsonObject["createSql"]!!
                    .jsonPrimitive.content
                    .replace("\${TABLE_NAME}", table),
            )
        }
    }
    schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
    db.version = version
    return db
}
