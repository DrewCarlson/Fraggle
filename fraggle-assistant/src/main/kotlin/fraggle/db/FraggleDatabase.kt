package fraggle.db

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories

/**
 * Manages the SQLite database connection and schema.
 *
 * Write operations go through [transact] on a single-threaded executor to prevent
 * SQLITE_BUSY. Read operations go through [query] on a shared thread pool, taking
 * advantage of WAL mode's support for concurrent readers.
 */
class FraggleDatabase(private val dbPath: Path) {
    private val logger = LoggerFactory.getLogger(FraggleDatabase::class.java)

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fraggle-db-write").apply { isDaemon = true }
    }

    private val readExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "fraggle-db-read").apply { isDaemon = true }
    }

    lateinit var database: Database
        private set

    fun connect() {
        dbPath.parent?.createDirectories()
        // Enable WAL mode and foreign keys via SQLite JDBC connection properties
        val url = "jdbc:sqlite:$dbPath?journal_mode=WAL&foreign_keys=ON&busy_timeout=5000"
        logger.info("Connecting to database: $url")

        database = Database.connect(
            url = url,
            driver = "org.sqlite.JDBC",
        )

        MigrationRunner(database).run()

        logger.info("Database initialized")
    }

    /**
     * Execute a read-only database transaction. Runs on a shared thread pool,
     * allowing concurrent reads under WAL mode.
     */
    fun <T> query(block: Transaction.() -> T): T {
        return readExecutor.submit<T> {
            transaction(database, statement = block)
        }.get()
    }

    /**
     * Execute a write database transaction on the serialized writer thread.
     * This prevents SQLITE_BUSY by ensuring only one writer is active at a time.
     */
    fun <T> transact(block: Transaction.() -> T): T {
        return writeExecutor.submit<T> {
            transaction(database, statement = block)
        }.get()
    }

    fun close() {
        TransactionManager.closeAndUnregister(database)
        writeExecutor.shutdown()
        readExecutor.shutdown()
        logger.info("Database closed")
    }
}
