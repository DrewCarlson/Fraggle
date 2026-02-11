package fraggle.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Tracks which numbered migrations have been applied to this database.
 */
object SchemaVersionTable : Table("schema_version") {
    val version = integer("version")
    val name = varchar("name", 255)
    val appliedAt = long("applied_at")

    override val primaryKey = PrimaryKey(version)
}
