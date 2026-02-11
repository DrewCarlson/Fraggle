package fraggle.db.migrations

import fraggle.db.ChatTable
import fraggle.db.MessageTable
import fraggle.db.Migration
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

/**
 * Creates the initial schema: [ChatTable] and [MessageTable].
 *
 * For fresh databases this creates the tables. For existing databases
 * that already have these tables, `SchemaUtils.create` is a no-op.
 */
object M001_InitialSchema : Migration {
    override val version = 1
    override val name = "Initial schema"

    override fun migrate() {
        SchemaUtils.create(ChatTable, MessageTable)
    }
}
