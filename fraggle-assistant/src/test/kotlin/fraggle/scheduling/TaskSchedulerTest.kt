package fraggle.scheduling

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class TaskSchedulerTest {

    private lateinit var scheduler: TaskScheduler
    private val triggeredTasks = mutableListOf<ScheduledTask>()

    @BeforeEach
    fun setup() {
        triggeredTasks.clear()
        scheduler = TaskScheduler { task -> triggeredTasks.add(task) }
    }

    @AfterEach
    fun teardown() {
        scheduler.shutdown()
    }

    @Nested
    inner class ScheduleTests {

        @Test
        fun `schedule creates task with correct fields`() {
            val task = scheduler.schedule(
                name = "Test",
                action = "do something",
                chatId = "chat-1",
                delay = Duration.parse("5m"),
            )

            assertEquals("Test", task.name)
            assertEquals("do something", task.action)
            assertEquals("chat-1", task.chatId)
            assertEquals(TaskStatus.PENDING, task.status)
            assertEquals(0, task.runCount)
            assertNull(task.repeatInterval)
        }

        @Test
        fun `schedule assigns unique sequential IDs`() {
            val task1 = scheduler.schedule("A", "a", "chat", Duration.parse("1m"))
            val task2 = scheduler.schedule("B", "b", "chat", Duration.parse("1m"))
            val task3 = scheduler.schedule("C", "c", "chat", Duration.parse("1m"))

            assertTrue(task1.id != task2.id)
            assertTrue(task2.id != task3.id)
            assertTrue(task1.id != task3.id)
        }

        @Test
        fun `schedule with repeat interval stores interval`() {
            val task = scheduler.schedule(
                name = "Recurring",
                action = "repeat",
                chatId = "chat",
                delay = Duration.parse("1m"),
                repeatInterval = Duration.parse("10m"),
            )

            assertEquals(Duration.parse("10m"), task.repeatInterval)
        }

        @Test
        fun `scheduled task is retrievable`() {
            val task = scheduler.schedule("Test", "action", "chat", Duration.parse("1m"))

            val retrieved = scheduler.getTask(task.id)
            assertNotNull(retrieved)
            assertEquals(task.id, retrieved.id)
            assertEquals("Test", retrieved.name)
        }
    }

    @Nested
    inner class ExecutionTests {

        @Test
        fun `one-time task executes and completes`() = runBlocking {
            val task = scheduler.schedule(
                name = "Quick",
                action = "run once",
                chatId = "chat",
                delay = Duration.ZERO,
            )

            delay(500)

            val completed = scheduler.getTask(task.id)
            assertNotNull(completed)
            assertEquals(TaskStatus.COMPLETED, completed.status)
            assertEquals(1, completed.runCount)
            assertEquals(1, triggeredTasks.size)
        }

        @Test
        fun `recurring task executes multiple times`() = runBlocking {
            scheduler.schedule(
                name = "Repeater",
                action = "repeat",
                chatId = "chat",
                delay = Duration.ZERO,
                repeatInterval = Duration.parse("200ms"),
            )

            delay(700)

            assertTrue(triggeredTasks.size >= 2, "Expected at least 2 executions, got ${triggeredTasks.size}")
        }

        @Test
        fun `failed one-time task gets FAILED status`() = runBlocking {
            val failingScheduler = TaskScheduler { throw RuntimeException("boom") }
            try {
                val task = failingScheduler.schedule(
                    name = "Will Fail",
                    action = "fail",
                    chatId = "chat",
                    delay = Duration.ZERO,
                )

                delay(500)

                val failed = failingScheduler.getTask(task.id)
                assertNotNull(failed)
                assertEquals(TaskStatus.FAILED, failed.status)
            } finally {
                failingScheduler.shutdown()
            }
        }

        @Test
        fun `failed recurring task retries after interval`() = runBlocking {
            var callCount = 0
            val retryScheduler = TaskScheduler { task ->
                callCount++
                if (callCount == 1) throw RuntimeException("first call fails")
            }
            try {
                retryScheduler.schedule(
                    name = "Retry Me",
                    action = "retry",
                    chatId = "chat",
                    delay = Duration.ZERO,
                    repeatInterval = Duration.parse("200ms"),
                )

                delay(700)

                // Should have retried after the failure
                assertTrue(callCount >= 2, "Expected at least 2 calls (1 failure + 1 retry), got $callCount")
            } finally {
                retryScheduler.shutdown()
            }
        }

        @Test
        fun `task runCount increments on each execution`() = runBlocking {
            val task = scheduler.schedule(
                name = "Counter",
                action = "count",
                chatId = "chat",
                delay = Duration.ZERO,
                repeatInterval = Duration.parse("200ms"),
            )

            delay(700)

            val current = scheduler.getTask(task.id)
            assertNotNull(current)
            assertTrue(current.runCount >= 2, "Expected runCount >= 2, got ${current.runCount}")
        }
    }

    @Nested
    inner class CancelTests {

        @Test
        fun `cancel pending task returns true`() {
            val task = scheduler.schedule("To Cancel", "action", "chat", Duration.parse("1m"))

            assertTrue(scheduler.cancel(task.id))
        }

        @Test
        fun `cancel sets status to CANCELLED`() {
            val task = scheduler.schedule("To Cancel", "action", "chat", Duration.parse("1m"))
            scheduler.cancel(task.id)

            val cancelled = scheduler.getTask(task.id)
            assertNotNull(cancelled)
            assertEquals(TaskStatus.CANCELLED, cancelled.status)
        }

        @Test
        fun `cancel unknown task returns false`() {
            assertTrue(!scheduler.cancel("nonexistent"))
        }

        @Test
        fun `cancelled task does not execute`() = runBlocking {
            val task = scheduler.schedule(
                name = "Cancelled",
                action = "should not run",
                chatId = "chat",
                delay = Duration.parse("500ms"),
            )
            scheduler.cancel(task.id)

            delay(700)

            assertTrue(triggeredTasks.isEmpty(), "Cancelled task should not have triggered")
        }

        @Test
        fun `cancel stops recurring task`() = runBlocking {
            val task = scheduler.schedule(
                name = "Recurring Cancel",
                action = "repeat",
                chatId = "chat",
                delay = Duration.ZERO,
                repeatInterval = Duration.parse("200ms"),
            )

            // Let it execute once
            delay(100)
            scheduler.cancel(task.id)
            val countAtCancel = triggeredTasks.size

            // Wait to confirm no more executions
            delay(600)

            assertEquals(countAtCancel, triggeredTasks.size, "No more executions after cancel")
        }
    }

    @Nested
    inner class ListTests {

        @Test
        fun `listTasks returns all tasks`() {
            scheduler.schedule("A", "a", "chat-1", Duration.parse("1m"))
            scheduler.schedule("B", "b", "chat-2", Duration.parse("2m"))

            val tasks = scheduler.listTasks()
            assertEquals(2, tasks.size)
        }

        @Test
        fun `listTasks returns tasks sorted by nextRunTime`() {
            scheduler.schedule("Later", "b", "chat", Duration.parse("10m"))
            scheduler.schedule("Sooner", "a", "chat", Duration.parse("1m"))

            val tasks = scheduler.listTasks()
            assertEquals("Sooner", tasks[0].name)
            assertEquals("Later", tasks[1].name)
        }

        @Test
        fun `listTasksForChat filters by chat ID`() {
            scheduler.schedule("Chat A", "a", "chat-a", Duration.parse("1m"))
            scheduler.schedule("Chat B", "b", "chat-b", Duration.parse("1m"))
            scheduler.schedule("Chat A2", "a2", "chat-a", Duration.parse("2m"))

            val chatATasks = scheduler.listTasksForChat("chat-a")
            assertEquals(2, chatATasks.size)
            assertTrue(chatATasks.all { it.chatId == "chat-a" })

            val chatBTasks = scheduler.listTasksForChat("chat-b")
            assertEquals(1, chatBTasks.size)
            assertEquals("Chat B", chatBTasks[0].name)
        }

        @Test
        fun `listTasksForChat returns empty for unknown chat`() {
            scheduler.schedule("Task", "action", "chat-a", Duration.parse("1m"))

            val tasks = scheduler.listTasksForChat("chat-unknown")
            assertTrue(tasks.isEmpty())
        }

        @Test
        fun `listPendingTasks excludes completed and cancelled`() = runBlocking {
            scheduler.schedule("Pending", "a", "chat", Duration.parse("1m"))
            val toCancel = scheduler.schedule("Cancel Me", "b", "chat", Duration.parse("2m"))
            scheduler.schedule("Instant", "c", "chat", Duration.ZERO)

            scheduler.cancel(toCancel.id)
            delay(500) // let the instant task complete

            val pending = scheduler.listPendingTasks()
            assertEquals(1, pending.size)
            assertEquals("Pending", pending[0].name)
        }
    }

    @Nested
    inner class ShutdownTests {

        @Test
        fun `shutdown cancels all running jobs`() = runBlocking {
            scheduler.schedule("A", "a", "chat", Duration.parse("200ms"), Duration.parse("200ms"))
            scheduler.schedule("B", "b", "chat", Duration.parse("200ms"), Duration.parse("200ms"))

            delay(100)
            scheduler.shutdown()
            val countAtShutdown = triggeredTasks.size

            delay(700)

            assertEquals(countAtShutdown, triggeredTasks.size, "No executions after shutdown")
        }
    }

    @Nested
    inner class GetTaskTests {

        @Test
        fun `getTask returns null for unknown ID`() {
            assertNull(scheduler.getTask("nonexistent"))
        }

        @Test
        fun `getTask returns task after cancel`() {
            val task = scheduler.schedule("Test", "action", "chat", Duration.parse("1m"))
            scheduler.cancel(task.id)

            val retrieved = scheduler.getTask(task.id)
            assertNotNull(retrieved)
            assertEquals(TaskStatus.CANCELLED, retrieved.status)
        }

        @Test
        fun `getTask returns task after completion`() = runBlocking {
            val task = scheduler.schedule("Test", "action", "chat", Duration.ZERO)
            delay(500)

            val retrieved = scheduler.getTask(task.id)
            assertNotNull(retrieved)
            assertEquals(TaskStatus.COMPLETED, retrieved.status)
        }
    }
}
