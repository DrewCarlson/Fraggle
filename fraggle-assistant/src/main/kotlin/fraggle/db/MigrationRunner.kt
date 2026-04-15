@file:OptIn(ExperimentalDatabaseMigrationApi::class)

package fraggle.db

import fraggle.db.migrations.M001_InitialSchema
import org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Two-phase migration runner:
 *
 * 1. **Numbered migrations** — run any un-applied [Migration] instances in version order.
 *    These handle data transforms, column renames, and drops that the diff tool can't infer.
 *
 * 2. **Auto-diff** — run [MigrationUtils.statementsRequiredForDatabaseMigration] to apply
 *    any remaining schema drift (new columns, new tables, new indexes) from table definition changes.
 */
class MigrationRunner(
    private val database: Database,
    private val migrations: List<Migration> = defaultMigrations,
    private val tables: Array<Table> = allTables,
) {
    private val logger = LoggerFactory.getLogger(MigrationRunner::class.java)

    fun run() {
        transaction(database) {
            // Bootstrap the version tracking table
            SchemaUtils.create(SchemaVersionTable)

            // Phase 1: run numbered migrations
            val applied = SchemaVersionTable
                .select(SchemaVersionTable.version)
                .map { it[SchemaVersionTable.version] }
                .toSet()

            for (migration in migrations.sortedBy { it.version }) {
                if (migration.version in applied) {
                    logger.debug("Skipping migration v{}: {} (already applied)", migration.version, migration.name)
                    continue
                }
                logger.info("Running migration v{}: {}", migration.version, migration.name)
                migration.migrate()
                SchemaVersionTable.insert {
                    it[version] = migration.version
                    it[name] = migration.name
                    it[appliedAt] = Clock.System.now().toEpochMilliseconds()
                }
            }

            // Phase 2: auto-diff for additive schema changes
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(*tables, withLogs = false)
            if (statements.isNotEmpty()) {
                logger.info("Applying {} auto-diff statement(s)", statements.size)
                for (statement in statements) {
                    logger.debug("Auto-diff: {}", statement)
                    exec(statement)
                }
            }
        }
    }

    companion object {
        val allTables: Array<Table> = arrayOf(ChatTable, MessageTable)

        val defaultMigrations: List<Migration> = listOf(
            M001_InitialSchema,
        )
    }
}
