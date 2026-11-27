package app.dshmobile.agency.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An empty database at an older schema version (REVIEW-logic I4), for bringing an older backup forward: its tables and
 * indices come from Room's exported schema of that version (`schemas/…/N.json`, packaged as assets), in memory only (never a
 * file: the rows are his). Null when that version is not packaged.
 */
object OlderSchema {
    const val DIR = "app.dshmobile.agency.data.AgencyDatabase"

    /** The create statements of [version] from its schema JSON text. */
    fun statements(json: String): List<String> {
        val db = Json.parseToJsonElement(json) as JsonObject
        val d = db["database"] as JsonObject
        return (d["entities"] as JsonArray).flatMap { e ->
            val o = e as JsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            listOf(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) +
                (o["indices"] as? JsonArray).orEmpty().map { (it as JsonObject)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table) }
        }
    }

    fun open(context: Context, version: Int): SupportSQLiteDatabase? {
        val json = runCatching { context.assets.open("$DIR/$version.json").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return null
        val create = statements(json)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = create.forEach { db.execSQL(it) }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }
}
