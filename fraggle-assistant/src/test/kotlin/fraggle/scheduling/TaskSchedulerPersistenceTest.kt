package fraggle.scheduling

import fraggle.db.ExposedScheduledTaskStore
import fraggle.db.FraggleDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class TaskSchedulerPersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fraggleDb: FraggleDatabase
    private lateinit var store: ExposedScheduledTaskStore
    private lateinit var scheduler: TaskScheduler
    private val triggeredTasks = mutableListOf<ScheduledTask>()

    @BeforeEach
    fun setup() {
        val dbPath = tempDir.resolve("test.db")
        fraggleDb = FraggleDatabase(dbPath)
        fraggleDb.connect()
        store = ExposedScheduledTaskStore(fraggleDb)
        triggeredTasks.clear()
        scheduler = TaskScheduler(store = store) { task -> triggeredTasks.add(task) }
    }

    @AfterEach
    fun teardown() {
        scheduler.shutdown()
    }

    @Nested
    inner class PersistOnSchedule {

        @Test
        fun `schedule persists task to store`() {
            val task = scheduler.schedule("Persist Me", "action", "chat", Duration.parse("5m"))

            val persisted = store.getById(task.id)
            assertNotNull(persisted)
            assertEquals("Persist Me", persisted.name)
            assertEquals(TaskStatus.PENDING, persisted.status)
        }

        @Test
        fun `schedule persists recurring task`() {
            val task = scheduler.schedule(
                name = "Recurring",
                action = "repeat",
                chatId = "chat",
                delay = Duration.parse("5m"),
                repeatInterval = Duration.parse("10m"),
            )

            val persisted = store.getById(task.id)
            assertNotNull(persisted)
            assertEquals(Duration.parse("10m"), persisted.repeatInterval)
        }
    }

    @Nested
    inner class PersistOnStatusChange {

        @Test
        fun `cancel persists CANCELLED status`() {
            val task = scheduler.schedule("Cancel Me", "action", "chat", Duration.parse("5m"))
            scheduler.cancel(task.id)

            val persisted = store.getById(task.id)
            assertNotNull(persisted)
            assertEquals(TaskStatus.CANCELLED, persisted.status)
        }

        @Test
        fun `completed task persists COMPLETED status`() = runBlocking {
            val task = scheduler.schedule("Complete Me", "action", "chat", Duration.ZERO)
            delay(500)

            val persisted = store.getById(task.id)
            assertNotNull(persisted)
            assertEquals(TaskStatus.COMPLETED, persisted.status)
            assertEquals(1, persisted.runCount)
        }

        @Test
        fun `failed task persists FAILED status`() = runBlocking {
            val failScheduler = TaskScheduler(store = store) { throw RuntimeException("boom") }
            try {
                val task = failScheduler.schedule("Fail Me", "action", "chat", Duration.ZERO)
                delay(500)

                val persisted = store.getById(task.id)
                assertNotNull(persisted)
                assertEquals(TaskStatus.FAILED, persisted.status)
            } finally {
                failScheduler.shutdown()
            }
        }
    }

    @Nested
    inner class RestoreFromStore {

        @Test
        fun `restoreFromStore reschedules active tasks`() = runBlocking {
            // Create a task directly in the store (simulating prior run)
            val now = kotlin.time.Clock.System.now()
            val task = ScheduledTask(
                id = "task-42",
                name = "Restored Task",
                action = "restored action",
                chatId = "chat",
                createdAt = now,
                nextRunTime = now + Duration.parse("100ms"),
                repeatInterval = null,
                status = TaskStatus.PENDING,
                runCount = 0,
            )
            store.save(task)

            // Create a new scheduler and restore
            val newScheduler = TaskScheduler(store = store) { t -> triggeredTasks.add(t) }
            try {
                newScheduler.restoreFromStore()

                // The restored task should be in memory
                val restored = newScheduler.getTask("task-42")
                assertNotNull(restored)
                assertEquals("Restored Task", restored.name)

                // Wait for it to fire
                delay(500)
                assertTrue(triggeredTasks.isNotEmpty(), "Restored task should have fired")
                assertEquals("Restored Task", triggeredTasks.first().name)
            } finally {
                newScheduler.shutdown()
            }
        }

        @Test
        fun `restoreFromStore fires missed tasks immediately`() = runBlocking {
            // Create a task with fire time in the past
            val now = kotlin.time.Clock.System.now()
            val task = ScheduledTask(
                id = "task-99",
                name = "Missed Task",
                action = "missed action",
                chatId = "chat",
                createdAt = now - Duration.parse("1h"),
                nextRunTime = now - Duration.parse("30m"),
                repeatInterval = null,
                status = TaskStatus.PENDING,
                runCount = 0,
            )
            store.save(task)

            val newScheduler = TaskScheduler(store = store) { t -> triggeredTasks.add(t) }
            try {
                newScheduler.restoreFromStore()

                // Should fire immediately since nextRunTime is in the past
                delay(500)
                assertTrue(triggeredTasks.isNotEmpty(), "Missed task should have fired immediately")
            } finally {
                newScheduler.shutdown()
            }
        }

        @Test
        fun `restoreFromStore resets RUNNING tasks to PENDING`() {
            val now = kotlin.time.Clock.System.now()
            val task = ScheduledTask(
                id = "task-50",
                name = "Was Running",
                action = "action",
                chatId = "chat",
                createdAt = now,
                nextRunTime = now + Duration.parse("5m"),
                repeatInterval = null,
                status = TaskStatus.RUNNING,
                runCount = 1,
            )
            store.save(task)

            val newScheduler = TaskScheduler(store = store) { t -> triggeredTasks.add(t) }
            try {
                newScheduler.restoreFromStore()

                val restored = newScheduler.getTask("task-50")
                assertNotNull(restored)
                assertEquals(TaskStatus.PENDING, restored.status)
            } finally {
                newScheduler.shutdown()
            }
        }

        @Test
        fun `restoreFromStore advances ID counter past restored IDs`() {
            val now = kotlin.time.Clock.System.now()
            store.save(ScheduledTask(
                id = "task-100",
                name = "Old Task",
                action = "action",
                chatId = "chat",
                createdAt = now,
                nextRunTime = now + Duration.parse("5m"),
                repeatInterval = null,
                status = TaskStatus.PENDING,
                runCount = 0,
            ))

            val newScheduler = TaskScheduler(store = store) { t -> triggeredTasks.add(t) }
            try {
                newScheduler.restoreFromStore()

                // New tasks should have IDs > 100
                val newTask = newScheduler.schedule("New", "action", "chat", Duration.parse("5m"))
                assertTrue(newTask.id > "task-100", "New ID ${newTask.id} should be > task-100")
            } finally {
                newScheduler.shutdown()
            }
        }

        @Test
        fun `restoreFromStore skips completed and cancelled tasks`() {
            val now = kotlin.time.Clock.System.now()
            store.save(ScheduledTask(
                id = "task-1",
                name = "Completed",
                action = "action",
                chatId = "chat",
                createdAt = now,
                nextRunTime = now,
                repeatInterval = null,
                status = TaskStatus.COMPLETED,
                runCount = 1,
            ))
            store.save(ScheduledTask(
                id = "task-2",
                name = "Cancelled",
                action = "action",
                chatId = "chat",
                createdAt = now,
                nextRunTime = now,
                repeatInterval = null,
                status = TaskStatus.CANCELLED,
                runCount = 0,
            ))

            val newScheduler = TaskScheduler(store = store) { t -> triggeredTasks.add(t) }
            try {
                newScheduler.restoreFromStore()

                // Neither should be in memory
                assertEquals(0, newScheduler.listTasks().size)
            } finally {
                newScheduler.shutdown()
            }
        }
    }
}
