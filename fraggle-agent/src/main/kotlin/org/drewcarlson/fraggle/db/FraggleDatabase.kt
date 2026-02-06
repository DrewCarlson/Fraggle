package org.drewcarlson.fraggle.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Manages the SQLite database connection and schema.
 */
class FraggleDatabase(private val dbPath: Path) {
    private val logger = LoggerFactory.getLogger(FraggleDatabase::class.java)

    lateinit var database: Database
        private set

    fun connect() {
        dbPath.parent?.createDirectories()
        // Enable WAL mode and foreign keys via SQLite JDBC connection properties
        val url = "jdbc:sqlite:$dbPath?journal_mode=WAL&foreign_keys=ON"
        logger.info("Connecting to database: $url")

        database = Database.connect(
            url = url,
            driver = "org.sqlite.JDBC",
        )

        MigrationRunner(database).run()

        logger.info("Database initialized")
    }

    fun close() {
        TransactionManager.closeAndUnregister(database)
        logger.info("Database closed")
    }
}
