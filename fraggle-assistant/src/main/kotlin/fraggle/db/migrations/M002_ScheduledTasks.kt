package fraggle.db.migrations

import fraggle.db.Migration
import fraggle.db.ScheduledTaskTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

/**
 * Creates the [ScheduledTaskTable] for persisting scheduled tasks across restarts.
 */
object M002_ScheduledTasks : Migration {
    override val version = 2
    override val name = "Scheduled tasks table"

    override fun migrate() {
        SchemaUtils.create(ScheduledTaskTable)
    }
}
