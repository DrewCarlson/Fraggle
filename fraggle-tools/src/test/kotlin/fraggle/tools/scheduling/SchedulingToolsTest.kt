package fraggle.tools.scheduling

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import fraggle.agent.ToolExecutionContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class SchedulingToolsTest {

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
    inner class ScheduleTaskToolTests {

        @Test
        fun `schedule_task creates task successfully`() = runTest {
            val tool = ScheduleTaskTool(scheduler)
            val ctx = ToolExecutionContext(chatId = "test-chat", userId = "user1")

            val result = withContext(ToolExecutionContext.asContextElement(ctx)) {
                tool.execute(ScheduleTaskTool.Args(
                    name = "Test Task",
                    action = "Do something",
                    delay_seconds = 60,
                ))
            }

            assertTrue(result.contains("Task scheduled successfully"))
            assertTrue(result.contains("Test Task"))
        }

        @Test
        fun `schedule_task with repeat interval creates recurring task`() = runTest {
            val tool = ScheduleTaskTool(scheduler)
            val ctx = ToolExecutionContext(chatId = "test-chat", userId = "user1")

            val result = withContext(ToolExecutionContext.asContextElement(ctx)) {
                tool.execute(ScheduleTaskTool.Args(
                    name = "Recurring Task",
                    action = "Repeat action",
                    delay_seconds = 60,
                    repeat_interval_seconds = 300,
                ))
            }

            assertTrue(result.contains("Repeats every"))
        }

        @Test
        fun `schedule_task fails without context`() = runTest {
            val tool = ScheduleTaskTool(scheduler)

            // Execute without ToolExecutionContext
            val result = tool.execute(ScheduleTaskTool.Args(
                name = "Task",
                action = "Action",
                delay_seconds = 60,
            ))

            assertTrue(result.contains("Error:"))
            assertTrue(result.contains("missing chat context"))
        }

        @Test
        fun `schedule_task fails with negative delay`() = runTest {
            val tool = ScheduleTaskTool(scheduler)
            val ctx = ToolExecutionContext(chatId = "chat", userId = "user")

            val result = withContext(ToolExecutionContext.asContextElement(ctx)) {
                tool.execute(ScheduleTaskTool.Args(
                    name = "Task",
                    action = "Action",
                    delay_seconds = -10,
                ))
            }

            assertTrue(result.contains("Error:"))
            assertTrue(result.contains("non-negative"))
        }
    }

    @Nested
    inner class ListTasksToolTests {

        @Test
        fun `list_tasks returns empty message when no tasks`() = runTest {
            val tool = ListTasksTool(scheduler)
            val result = tool.execute(ListTasksTool.Args())

            assertTrue(result.contains("No tasks scheduled"))
        }

        @Test
        fun `list_tasks lists scheduled tasks`() = runTest {
            scheduler.schedule("Task A", "action", "chat", kotlin.time.Duration.parse("1m"))
            scheduler.schedule("Task B", "action", "chat", kotlin.time.Duration.parse("2m"))

            val tool = ListTasksTool(scheduler)
            val result = tool.execute(ListTasksTool.Args())

            assertTrue(result.contains("Task A"))
            assertTrue(result.contains("Task B"))
            assertTrue(result.contains("Scheduled tasks:"))
        }

        @Test
        fun `list_tasks shows recurring indicator`() = runTest {
            scheduler.schedule(
                name = "Recurring",
                action = "repeat",
                chatId = "chat",
                delay = kotlin.time.Duration.parse("1m"),
                repeatInterval = kotlin.time.Duration.parse("5m"),
            )

            val tool = ListTasksTool(scheduler)
            val result = tool.execute(ListTasksTool.Args())

            assertTrue(result.contains("recurring"))
        }
    }

    @Nested
    inner class CancelTaskToolTests {

        @Test
        fun `cancel_task succeeds for existing task`() = runTest {
            val task = scheduler.schedule("To Cancel", "action", "chat", kotlin.time.Duration.parse("1m"))

            val tool = CancelTaskTool(scheduler)
            val result = tool.execute(CancelTaskTool.Args(task_id = task.id))

            assertTrue(result.contains("cancelled successfully"))
        }

        @Test
        fun `cancel_task fails for unknown task`() = runTest {
            val tool = CancelTaskTool(scheduler)
            val result = tool.execute(CancelTaskTool.Args(task_id = "nonexistent-id"))

            assertTrue(result.contains("Error:"))
            assertTrue(result.contains("not found"))
        }
    }

    @Nested
    inner class GetTaskToolTests {

        @Test
        fun `get_task returns task details`() = runTest {
            val task = scheduler.schedule(
                name = "Detailed Task",
                action = "Do detailed things",
                chatId = "chat123",
                delay = kotlin.time.Duration.parse("5m"),
            )

            val tool = GetTaskTool(scheduler)
            val result = tool.execute(GetTaskTool.Args(task_id = task.id))

            assertTrue(result.contains("Detailed Task"))
            assertTrue(result.contains("Do detailed things"))
            assertTrue(result.contains("chat123"))
            assertTrue(result.contains("Task Details:"))
        }

        @Test
        fun `get_task fails for unknown task`() = runTest {
            val tool = GetTaskTool(scheduler)
            val result = tool.execute(GetTaskTool.Args(task_id = "unknown-id"))

            assertTrue(result.contains("Error:"))
            assertTrue(result.contains("not found"))
        }

        @Test
        fun `get_task shows repeat interval for recurring task`() = runTest {
            val task = scheduler.schedule(
                name = "Recurring Details",
                action = "repeat",
                chatId = "chat",
                delay = kotlin.time.Duration.parse("1m"),
                repeatInterval = kotlin.time.Duration.parse("10m"),
            )

            val tool = GetTaskTool(scheduler)
            val result = tool.execute(GetTaskTool.Args(task_id = task.id))

            assertTrue(result.contains("Repeat interval"))
        }
    }
}
