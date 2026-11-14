package dev.spendingtracker.server.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.postgresql.util.PGobject

/** A Postgres jsonb column, read and written as its JSON text. */
class JsonbColumnType : ColumnType<String>() {
    override fun sqlType() = "jsonb"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value.orEmpty()
        else -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any = PGobject().apply {
        type = "jsonb"
        this.value = value
    }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
