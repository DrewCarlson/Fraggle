package org.drewcarlson.fraggle.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Column type that stores [Instant] as epoch milliseconds (Long).
 */
class InstantColumnType : ColumnType<Instant>() {
    override fun sqlType(): String = currentDialect.dataTypeProvider.longType()

    override fun valueFromDB(value: Any): Instant = when (value) {
        is Long -> Instant.fromEpochMilliseconds(value)
        is Number -> Instant.fromEpochMilliseconds(value.toLong())
        else -> error("Unexpected value type for Instant column: ${value::class}")
    }

    override fun notNullValueToDB(value: Instant): Any =
        value.toEpochMilliseconds()

    override fun nonNullValueToString(value: Instant): String =
        value.toEpochMilliseconds().toString()
}

/**
 * Column type that stores [Duration] as milliseconds (Long).
 */
class DurationColumnType : ColumnType<Duration>() {
    override fun sqlType(): String = currentDialect.dataTypeProvider.longType()

    override fun valueFromDB(value: Any): Duration = when (value) {
        is Long -> value.milliseconds
        is Number -> value.toLong().milliseconds
        else -> error("Unexpected value type for Duration column: ${value::class}")
    }

    override fun notNullValueToDB(value: Duration): Any =
        value.inWholeMilliseconds

    override fun nonNullValueToString(value: Duration): String =
        value.inWholeMilliseconds.toString()
}

/**
 * Register a column that stores [Instant] as epoch milliseconds.
 */
fun Table.instant(name: String): Column<Instant> =
    registerColumn(name, InstantColumnType())

/**
 * Register a column that stores [Duration] as milliseconds.
 */
fun Table.duration(name: String): Column<Duration> =
    registerColumn(name, DurationColumnType())
