package fraggle.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class MigrationRunnerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Database

    @BeforeEach
    fun setup() {
        val dbPath = tempDir.resolve("test.db")
        database = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
    }

    @Nested
    inner class FreshDatabase {

        @Test
        fun `runs migrations in version order on fresh database`() {
            val order = mutableListOf<Int>()
            val migrations = listOf(
                testMigration(2, "Second") { order.add(2) },
                testMigration(1, "First") { order.add(1) },
                testMigration(3, "Third") { order.add(3) },
            )

            MigrationRunner(database, migrations, MigrationRunner.allTables).run()

            assertEquals(listOf(1, 2, 3), order)
        }

        @Test
        fun `records applied migrations in schema_version table`() {
            MigrationRunner(database).run()

            transaction(database) {
                val versions = SchemaVersionTable.selectAll()
                    .map { it[SchemaVersionTable.version] to it[SchemaVersionTable.name] }
                assertEquals(listOf(1 to "Initial schema"), versions)
            }
        }

        @Test
        fun `creates tables via M001`() {
            MigrationRunner(database).run()

            // Verify tables exist by performing queries
            transaction(database) {
                val chatCount = ChatTable.selectAll().count()
                assertEquals(0, chatCount)
                val messageCount = MessageTable.selectAll().count()
                assertEquals(0, messageCount)
            }
        }
    }

    @Nested
    inner class ExistingDatabase {

        @Test
        fun `skips already-applied migrations`() {
            var runCount = 0
            val migrations = listOf(
                testMigration(1, "First") { runCount++ },
            )

            // Run once
            MigrationRunner(database, migrations, MigrationRunner.allTables).run()
            assertEquals(1, runCount)

            // Run again — migration should be skipped
            MigrationRunner(database, migrations, MigrationRunner.allTables).run()
            assertEquals(1, runCount)
        }

        @Test
        fun `runs only new migrations on existing database`() {
            val executed = mutableListOf<Int>()
            val v1 = testMigration(1, "First") { executed.add(1) }

            // Run with only v1
            MigrationRunner(database, listOf(v1), MigrationRunner.allTables).run()
            assertEquals(listOf(1), executed)

            // Add v2 and run again
            val v2 = testMigration(2, "Second") { executed.add(2) }
            MigrationRunner(database, listOf(v1, v2), MigrationRunner.allTables).run()
            assertEquals(listOf(1, 2), executed)
        }
    }

    @Nested
    inner class AutoDiff {

        @Test
        fun `auto-diff picks up new columns added to table definitions`() {
            // First, create only the base ChatTable
            MigrationRunner(database).run()

            // Define an extended table with an extra column
            val extendedChat = object : Table("chats") {
                val id = long("id").autoIncrement()
                val platform = varchar("platform", 50)
                val externalId = varchar("external_id", 255).uniqueIndex()
                val name = varchar("name", 255).nullable()
                val isGroup = bool("is_group").default(false)
                val createdAt = instant("created_at")
                val lastActiveAt = instant("last_active_at")
                val description = varchar("description", 500).nullable()

                override val primaryKey = PrimaryKey(id)
            }

            // Run migration runner with extended table — auto-diff should add the column
            MigrationRunner(database, MigrationRunner.defaultMigrations, arrayOf(extendedChat, MessageTable)).run()

            // Verify the column exists by inserting a row with it
            transaction(database) {
                exec("INSERT INTO chats (platform, external_id, is_group, created_at, last_active_at, description) VALUES ('test', 'test:1', 0, 0, 0, 'test desc')")
                val result = exec("SELECT description FROM chats WHERE external_id = 'test:1'") { rs ->
                    rs.next()
                    rs.getString("description")
                }
                assertEquals("test desc", result)
            }
        }
    }

    @Nested
    inner class Idempotency {

        @Test
        fun `running twice produces no errors`() {
            MigrationRunner(database).run()
            MigrationRunner(database).run()

            // Verify state is consistent
            transaction(database) {
                val versions = SchemaVersionTable.selectAll()
                    .map { it[SchemaVersionTable.version] }
                assertEquals(listOf(1), versions)
            }
        }
    }

    private fun testMigration(version: Int, name: String, block: () -> Unit) = object : Migration {
        override val version = version
        override val name = name
        override fun migrate() = block()
    }
}
