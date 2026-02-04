package org.drewcarlson.fraggle.skills.scheduling

import kotlinx.coroutines.test.runTest
import org.drewcarlson.fraggle.skill.SkillContext
import org.drewcarlson.fraggle.skill.SkillParameters
import org.drewcarlson.fraggle.skill.SkillResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchedulingSkillsTest {

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
    inner class CreateSkillsTests {

        @Test
        fun `create returns four skills`() {
            val skills = SchedulingSkills.create(scheduler)

            assertEquals(4, skills.size)
            assertTrue(skills.any { it.name == "schedule_task" })
            assertTrue(skills.any { it.name == "list_tasks" })
            assertTrue(skills.any { it.name == "cancel_task" })
            assertTrue(skills.any { it.name == "get_task" })
        }
    }

    @Nested
    inner class ScheduleTaskSkillTests {

        @Test
        fun `schedule_task creates task successfully`() = runTest {
            val skill = SchedulingSkills.scheduleTask(scheduler)
            val context = SkillContext(chatId = "test-chat", userId = "user1")
            val params = SkillParameters(
                mapOf(
                    "name" to "Test Task",
                    "action" to "Do something",
                    "delay_seconds" to 60L,
                ),
                context
            )

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("Task scheduled successfully"))
            assertTrue(result.output.contains("Test Task"))
        }

        @Test
        fun `schedule_task with repeat interval creates recurring task`() = runTest {
            val skill = SchedulingSkills.scheduleTask(scheduler)
            val context = SkillContext(chatId = "test-chat", userId = "user1")
            val params = SkillParameters(
                mapOf(
                    "name" to "Recurring Task",
                    "action" to "Repeat action",
                    "delay_seconds" to 60L,
                    "repeat_interval_seconds" to 300L,
                ),
                context
            )

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("Repeats every"))
        }

        @Test
        fun `schedule_task fails without context`() = runTest {
            val skill = SchedulingSkills.scheduleTask(scheduler)
            val params = SkillParameters(
                mapOf(
                    "name" to "Task",
                    "action" to "Action",
                    "delay_seconds" to 60L,
                ),
                null // No context
            )

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("missing chat context"))
        }

        @Test
        fun `schedule_task fails with negative delay`() = runTest {
            val skill = SchedulingSkills.scheduleTask(scheduler)
            val context = SkillContext(chatId = "chat", userId = "user")
            val params = SkillParameters(
                mapOf(
                    "name" to "Task",
                    "action" to "Action",
                    "delay_seconds" to -10L,
                ),
                context
            )

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("non-negative"))
        }
    }

    @Nested
    inner class ListTasksSkillTests {

        @Test
        fun `list_tasks returns empty message when no tasks`() = runTest {
            val skill = SchedulingSkills.listTasks(scheduler)
            val params = SkillParameters(emptyMap())

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("No tasks scheduled"))
        }

        @Test
        fun `list_tasks lists scheduled tasks`() = runTest {
            // Schedule some tasks first
            scheduler.schedule("Task A", "action", "chat", kotlin.time.Duration.parse("1m"))
            scheduler.schedule("Task B", "action", "chat", kotlin.time.Duration.parse("2m"))

            val skill = SchedulingSkills.listTasks(scheduler)
            val params = SkillParameters(emptyMap())

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("Task A"))
            assertTrue(result.output.contains("Task B"))
            assertTrue(result.output.contains("Scheduled tasks:"))
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

            val skill = SchedulingSkills.listTasks(scheduler)
            val result = skill.execute(SkillParameters(emptyMap()))

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("recurring"))
        }
    }

    @Nested
    inner class CancelTaskSkillTests {

        @Test
        fun `cancel_task succeeds for existing task`() = runTest {
            val task = scheduler.schedule("To Cancel", "action", "chat", kotlin.time.Duration.parse("1m"))

            val skill = SchedulingSkills.cancelTask(scheduler)
            val params = SkillParameters(mapOf("task_id" to task.id))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("cancelled successfully"))
        }

        @Test
        fun `cancel_task fails for unknown task`() = runTest {
            val skill = SchedulingSkills.cancelTask(scheduler)
            val params = SkillParameters(mapOf("task_id" to "nonexistent-id"))

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("not found"))
        }
    }

    @Nested
    inner class GetTaskSkillTests {

        @Test
        fun `get_task returns task details`() = runTest {
            val task = scheduler.schedule(
                name = "Detailed Task",
                action = "Do detailed things",
                chatId = "chat123",
                delay = kotlin.time.Duration.parse("5m"),
            )

            val skill = SchedulingSkills.getTask(scheduler)
            val params = SkillParameters(mapOf("task_id" to task.id))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("Detailed Task"))
            assertTrue(result.output.contains("Do detailed things"))
            assertTrue(result.output.contains("chat123"))
            assertTrue(result.output.contains("Task Details:"))
        }

        @Test
        fun `get_task fails for unknown task`() = runTest {
            val skill = SchedulingSkills.getTask(scheduler)
            val params = SkillParameters(mapOf("task_id" to "unknown-id"))

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("not found"))
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

            val skill = SchedulingSkills.getTask(scheduler)
            val params = SkillParameters(mapOf("task_id" to task.id))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("Repeat interval"))
        }
    }
}
