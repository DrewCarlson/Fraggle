package fraggle.db

import fraggle.scheduling.ScheduledTask
import fraggle.scheduling.TaskStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExposedScheduledTaskStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fraggleDb: FraggleDatabase
    private lateinit var store: ExposedScheduledTaskStore

    @BeforeEach
    fun setup() {
        val dbPath = tempDir.resolve("test.db")
        fraggleDb = FraggleDatabase(dbPath)
        fraggleDb.connect()
        store = ExposedScheduledTaskStore(fraggleDb)
    }

    private fun createTask(
        id: String = "task-1",
        name: String = "Test Task",
        action: String = "Do something",
        chatId: String = "signal:+1234567890",
        status: TaskStatus = TaskStatus.PENDING,
        repeatInterval: kotlin.time.Duration? = null,
        runCount: Int = 0,
    ): ScheduledTask {
        val now = Clock.System.now()
        return ScheduledTask(
            id = id,
            name = name,
            action = action,
            chatId = chatId,
            createdAt = now,
            nextRunTime = now + 5.minutes,
            repeatInterval = repeatInterval,
            status = status,
            runCount = runCount,
        )
    }

    @Nested
    inner class SaveAndRetrieve {

        @Test
        fun `save and getById returns task`() {
            val task = createTask()
            store.save(task)

            val retrieved = store.getById(task.id)
            assertNotNull(retrieved)
            assertEquals(task.id, retrieved.id)
            assertEquals(task.name, retrieved.name)
            assertEquals(task.action, retrieved.action)
            assertEquals(task.chatId, retrieved.chatId)
            assertEquals(task.status, retrieved.status)
            assertEquals(task.runCount, retrieved.runCount)
        }

        @Test
        fun `getById returns null for unknown id`() {
            assertNull(store.getById("nonexistent"))
        }

        @Test
        fun `save persists repeat interval`() {
            val task = createTask(repeatInterval = 10.minutes)
            store.save(task)

            val retrieved = store.getById(task.id)
            assertNotNull(retrieved)
            assertEquals(10.minutes, retrieved.repeatInterval)
        }

        @Test
        fun `save persists null repeat interval`() {
            val task = createTask(repeatInterval = null)
            store.save(task)

            val retrieved = store.getById(task.id)
            assertNotNull(retrieved)
            assertNull(retrieved.repeatInterval)
        }
    }

    @Nested
    inner class Update {

        @Test
        fun `update changes status`() {
            val task = createTask()
            store.save(task)

            store.update(task.copy(status = TaskStatus.COMPLETED))

            val retrieved = store.getById(task.id)
            assertNotNull(retrieved)
            assertEquals(TaskStatus.COMPLETED, retrieved.status)
        }

        @Test
        fun `update changes runCount`() {
            val task = createTask()
            store.save(task)

            store.update(task.copy(runCount = 5))

            val retrieved = store.getById(task.id)
            assertNotNull(retrieved)
            assertEquals(5, retrieved.runCount)
        }

        @Test
        fun `update changes nextRunTime`() {
            val task = createTask()
            store.save(task)

            val newNextRun = Clock.System.now() + 30.minutes
            store.update(task.copy(nextRunTime = newNextRun))

            val retrieved = store.getById(task.id)
            assertNotNull(retrieved)
            // Compare with millisecond precision (SQLite stores millis)
            assertEquals(
                newNextRun.toEpochMilliseconds(),
                retrieved.nextRunTime.toEpochMilliseconds(),
            )
        }
    }

    @Nested
    inner class Listing {

        @Test
        fun `listAll returns all tasks`() {
            store.save(createTask(id = "task-1", name = "A"))
            store.save(createTask(id = "task-2", name = "B"))
            store.save(createTask(id = "task-3", name = "C", status = TaskStatus.COMPLETED))

            val all = store.listAll()
            assertEquals(3, all.size)
        }

        @Test
        fun `listActive returns only PENDING and RUNNING`() {
            store.save(createTask(id = "task-1", status = TaskStatus.PENDING))
            store.save(createTask(id = "task-2", status = TaskStatus.RUNNING))
            store.save(createTask(id = "task-3", status = TaskStatus.COMPLETED))
            store.save(createTask(id = "task-4", status = TaskStatus.CANCELLED))
            store.save(createTask(id = "task-5", status = TaskStatus.FAILED))

            val active = store.listActive()
            assertEquals(2, active.size)
            assertTrue(active.all { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING })
        }

        @Test
        fun `listAll returns empty when no tasks`() {
            assertTrue(store.listAll().isEmpty())
        }

        @Test
        fun `listActive returns empty when no active tasks`() {
            store.save(createTask(id = "task-1", status = TaskStatus.COMPLETED))
            assertTrue(store.listActive().isEmpty())
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun `delete removes task`() {
            val task = createTask()
            store.save(task)

            store.delete(task.id)

            assertNull(store.getById(task.id))
        }

        @Test
        fun `delete nonexistent id does not throw`() {
            store.delete("nonexistent")
        }
    }
}
