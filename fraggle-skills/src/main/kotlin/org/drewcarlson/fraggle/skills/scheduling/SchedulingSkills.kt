package org.drewcarlson.fraggle.skills.scheduling

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.skill.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Task scheduling skills for deferred and recurring operations.
 */
object SchedulingSkills {
    private val logger = LoggerFactory.getLogger(SchedulingSkills::class.java)

    /**
     * Create all scheduling skills with the given scheduler.
     */
    fun create(scheduler: TaskScheduler): List<Skill> {
        return listOf(
            scheduleTask(scheduler),
            listTasks(scheduler),
            cancelTask(scheduler),
            getTask(scheduler),
        )
    }

    /**
     * Skill to schedule a new task.
     */
    fun scheduleTask(scheduler: TaskScheduler) = skill("schedule_task") {
        description = """Schedule a task for later execution.
            |You can schedule one-time tasks or recurring tasks.
            |Tasks will execute the specified action when triggered.""".trimMargin()

        parameter<String>("name") {
            description = "A descriptive name for the task"
            required = true
        }

        parameter<String>("action") {
            description = "The action/message to execute when the task runs"
            required = true
        }

        parameter<Long>("delay_seconds") {
            description = "Number of seconds to wait before first execution"
            required = true
        }

        parameter<Long>("repeat_interval_seconds") {
            description = "For recurring tasks, seconds between executions. 0 for one-time tasks."
            default = 0L
        }

        execute { params ->
            val name = params.get<String>("name")
            val action = params.get<String>("action")
            val delaySeconds = params.get<Long>("delay_seconds")
            val repeatInterval = params.getOrDefault("repeat_interval_seconds", 0L)

            // Get chatId from context - required for sending messages when task triggers
            val chatId = params.context?.chatId
                ?: return@execute SkillResult.Error("Cannot schedule task: missing chat context")

            if (delaySeconds < 0) {
                return@execute SkillResult.Error("delay_seconds must be non-negative")
            }

            val task = scheduler.schedule(
                name = name,
                action = action,
                chatId = chatId,
                delaySeconds = delaySeconds,
                repeatIntervalSeconds = if (repeatInterval > 0) repeatInterval else null,
            )

            val nextRun = Instant.ofEpochMilli(task.nextRunTime)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            val message = buildString {
                appendLine("Task scheduled successfully!")
                appendLine("  ID: ${task.id}")
                appendLine("  Name: ${task.name}")
                appendLine("  Next run: $nextRun")
                if (task.repeatIntervalSeconds != null) {
                    appendLine("  Repeats every: ${formatDuration(task.repeatIntervalSeconds)} ")
                }
            }

            SkillResult.Success(message)
        }
    }

    /**
     * Skill to list all scheduled tasks.
     */
    fun listTasks(scheduler: TaskScheduler) = skill("list_tasks") {
        description = "List all scheduled tasks."

        execute { _ ->
            val tasks = scheduler.listTasks()

            if (tasks.isEmpty()) {
                return@execute SkillResult.Success("No tasks scheduled.")
            }

            val listing = tasks.map { task ->
                val nextRun = Instant.ofEpochMilli(task.nextRunTime)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                buildString {
                    append("- [${task.id}] ${task.name}")
                    append(" (next: $nextRun)")
                    if (task.repeatIntervalSeconds != null) {
                        append(" [recurring: ${formatDuration(task.repeatIntervalSeconds)}]")
                    }
                    if (task.status != TaskStatus.PENDING) {
                        append(" [${task.status}]")
                    }
                }
            }.joinToString("\n")

            SkillResult.Success("Scheduled tasks:\n$listing")
        }
    }

    /**
     * Skill to cancel a scheduled task.
     */
    fun cancelTask(scheduler: TaskScheduler) = skill("cancel_task") {
        description = "Cancel a scheduled task by its ID."

        parameter<String>("task_id") {
            description = "The ID of the task to cancel"
            required = true
        }

        execute { params ->
            val taskId = params.get<String>("task_id")

            if (scheduler.cancel(taskId)) {
                SkillResult.Success("Task $taskId cancelled successfully.")
            } else {
                SkillResult.Error("Task $taskId not found or already completed.")
            }
        }
    }

    /**
     * Skill to get details about a specific task.
     */
    fun getTask(scheduler: TaskScheduler) = skill("get_task") {
        description = "Get detailed information about a scheduled task."

        parameter<String>("task_id") {
            description = "The ID of the task"
            required = true
        }

        execute { params ->
            val taskId = params.get<String>("task_id")
            val task = scheduler.getTask(taskId)

            if (task == null) {
                return@execute SkillResult.Error("Task $taskId not found.")
            }

            val nextRun = Instant.ofEpochMilli(task.nextRunTime)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            val created = Instant.ofEpochMilli(task.createdAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            val message = buildString {
                appendLine("Task Details:")
                appendLine("  ID: ${task.id}")
                appendLine("  Name: ${task.name}")
                appendLine("  Status: ${task.status}")
                appendLine("  Action: ${task.action}")
                appendLine("  Chat ID: ${task.chatId}")
                appendLine("  Created: $created")
                appendLine("  Next run: $nextRun")
                if (task.repeatIntervalSeconds != null) {
                    appendLine("  Repeat interval: ${formatDuration(task.repeatIntervalSeconds)}")
                }
                appendLine("  Run count: ${task.runCount}")
            }

            SkillResult.Success(message)
        }
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86400 -> "${seconds / 3600}h"
            else -> "${seconds / 86400}d"
        }
    }
}

/**
 * Task scheduler for managing scheduled tasks.
 */
class TaskScheduler(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val onTaskTriggered: suspend (ScheduledTask) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(TaskScheduler::class.java)
    private val json = Json { prettyPrint = true }

    private val tasks = ConcurrentHashMap<String, ScheduledTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val idCounter = AtomicLong(0)

    /**
     * Schedule a new task.
     */
    fun schedule(
        name: String,
        action: String,
        chatId: String,
        delaySeconds: Long,
        repeatIntervalSeconds: Long? = null,
    ): ScheduledTask {
        val id = "task-${idCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()

        val task = ScheduledTask(
            id = id,
            name = name,
            action = action,
            chatId = chatId,
            createdAt = now,
            nextRunTime = now + (delaySeconds * 1000),
            repeatIntervalSeconds = repeatIntervalSeconds,
            status = TaskStatus.PENDING,
            runCount = 0,
        )

        tasks[id] = task

        // Schedule the job
        val job = scope.launch {
            delay(delaySeconds * 1000)

            while (isActive) {
                val currentTask = tasks[id] ?: break

                // Update status
                tasks[id] = currentTask.copy(
                    status = TaskStatus.RUNNING,
                    runCount = currentTask.runCount + 1,
                )

                try {
                    onTaskTriggered(currentTask)
                    logger.info("Task executed: ${currentTask.name}")
                } catch (e: Exception) {
                    logger.error("Task execution failed: ${e.message}")
                }

                // Check if recurring
                if (repeatIntervalSeconds != null && repeatIntervalSeconds > 0) {
                    val updatedTask = tasks[id] ?: break
                    tasks[id] = updatedTask.copy(
                        status = TaskStatus.PENDING,
                        nextRunTime = System.currentTimeMillis() + (repeatIntervalSeconds * 1000),
                    )
                    delay(repeatIntervalSeconds * 1000)
                } else {
                    // One-time task, mark as completed
                    tasks[id] = tasks[id]?.copy(status = TaskStatus.COMPLETED) ?: break
                    break
                }
            }
        }

        jobs[id] = job
        logger.info("Scheduled task: $name (id=$id, delay=${delaySeconds}s)")

        return task
    }

    /**
     * Cancel a task.
     */
    fun cancel(taskId: String): Boolean {
        val task = tasks[taskId] ?: return false
        val job = jobs.remove(taskId)

        job?.cancel()
        tasks[taskId] = task.copy(status = TaskStatus.CANCELLED)

        logger.info("Cancelled task: ${task.name}")
        return true
    }

    /**
     * Get a task by ID.
     */
    fun getTask(taskId: String): ScheduledTask? = tasks[taskId]

    /**
     * List all tasks.
     */
    fun listTasks(): List<ScheduledTask> = tasks.values.toList()
        .sortedBy { it.nextRunTime }

    /**
     * List pending tasks.
     */
    fun listPendingTasks(): List<ScheduledTask> = tasks.values
        .filter { it.status == TaskStatus.PENDING }
        .sortedBy { it.nextRunTime }

    /**
     * Shutdown the scheduler.
     */
    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        logger.info("Task scheduler shutdown")
    }
}

/**
 * A scheduled task.
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val action: String,
    val chatId: String,
    val createdAt: Long,
    val nextRunTime: Long,
    val repeatIntervalSeconds: Long?,
    val status: TaskStatus,
    val runCount: Int,
)

/**
 * Task status.
 */
@Serializable
enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
}
