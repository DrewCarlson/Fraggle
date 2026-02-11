package fraggle.tools.scheduling

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolArgs
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import fraggle.agent.ToolExecutionContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.*

@Suppress("ObjectPropertyName")
private val _fulleDateTimeFormat = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    day()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
}
@Suppress("UnusedReceiverParameter")
val LocalDateTime.Formats.fullDataTime: DateTimeFormat<LocalDateTime>
    get() = _fulleDateTimeFormat

class ScheduleTaskTool(private val scheduler: TaskScheduler) : SimpleTool<ScheduleTaskTool.Args>(
    argsSerializer = Args.serializer(),
    name = "schedule_task",
    description = """Schedule a task for later execution.
You can schedule one-time tasks or recurring tasks.
Tasks will execute the specified action when triggered.""",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("A descriptive name for the task")
        val name: String,
        @param:LLMDescription("The action/message to execute when the task runs")
        val action: String,
        @param:LLMDescription("Number of seconds to wait before first execution")
        val delay_seconds: Long,
        @param:LLMDescription("For recurring tasks, seconds between executions. 0 for one-time tasks.")
        val repeat_interval_seconds: Long = 0,
    )

    override suspend fun execute(args: Args): String {
        val delay = args.delay_seconds.toDuration(DurationUnit.SECONDS)
        val repeatInterval = args.repeat_interval_seconds.toDuration(DurationUnit.SECONDS)

        val chatId = ToolExecutionContext.current()?.chatId
            ?: return "Error: Cannot schedule task: missing chat context"

        if (delay < Duration.ZERO) {
            return "Error: delay_seconds must be non-negative"
        }

        val task = scheduler.schedule(
            name = args.name,
            action = args.action,
            chatId = chatId,
            delay = delay,
            repeatInterval = if (repeatInterval > Duration.ZERO) repeatInterval else null,
        )

        val nextRun = task.nextRunTime
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .format(LocalDateTime.Formats.fullDataTime)

        return buildString {
            appendLine("Task scheduled successfully!")
            appendLine("  ID: ${task.id}")
            appendLine("  Name: ${task.name}")
            appendLine("  Next run: $nextRun")
            if (task.repeatInterval != null) {
                appendLine("  Repeats every: ${task.repeatInterval} ")
            }
        }
    }
}

class ListTasksTool(private val scheduler: TaskScheduler) : SimpleTool<ToolArgs.Empty>(
    argsSerializer = ToolArgs.Empty.serializer(),
    name = "list_tasks",
    description = "List all scheduled tasks.",
) {
    override suspend fun execute(args: ToolArgs.Empty): String {
        val tasks = scheduler.listTasks()

        if (tasks.isEmpty()) {
            return "No tasks scheduled."
        }

        val listing = tasks.map { task ->
            val nextRun = task.nextRunTime
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .format(LocalDateTime.Formats.fullDataTime)

            buildString {
                append("- [${task.id}] ${task.name}")
                append(" (next: $nextRun)")
                if (task.repeatInterval != null) {
                    append(" [recurring: ${task.repeatInterval}]")
                }
                if (task.status != TaskStatus.PENDING) {
                    append(" [${task.status}]")
                }
            }
        }.joinToString("\n")

        return "Scheduled tasks:\n$listing"
    }
}

class CancelTaskTool(private val scheduler: TaskScheduler) : SimpleTool<CancelTaskTool.Args>(
    argsSerializer = Args.serializer(),
    name = "cancel_task",
    description = "Cancel a scheduled task by its ID.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("The ID of the task to cancel")
        val task_id: String,
    )

    override suspend fun execute(args: Args): String {
        return if (scheduler.cancel(args.task_id)) {
            "Task ${args.task_id} cancelled successfully."
        } else {
            "Error: Task ${args.task_id} not found or already completed."
        }
    }
}

class GetTaskTool(private val scheduler: TaskScheduler) : SimpleTool<GetTaskTool.Args>(
    argsSerializer = Args.serializer(),
    name = "get_task",
    description = "Get detailed information about a scheduled task.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("The ID of the task")
        val task_id: String,
    )

    override suspend fun execute(args: Args): String {
        val task = scheduler.getTask(args.task_id)
            ?: return "Error: Task ${args.task_id} not found."

        val nextRun = task.nextRunTime
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .format(LocalDateTime.Formats.fullDataTime)

        val created = task.createdAt
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .format(LocalDateTime.Formats.fullDataTime)

        return buildString {
            appendLine("Task Details:")
            appendLine("  ID: ${task.id}")
            appendLine("  Name: ${task.name}")
            appendLine("  Status: ${task.status}")
            appendLine("  Action: ${task.action}")
            appendLine("  Chat ID: ${task.chatId}")
            appendLine("  Created: $created")
            appendLine("  Next run: $nextRun")
            if (task.repeatInterval != null) {
                appendLine("  Repeat interval: ${task.repeatInterval}")
            }
            appendLine("  Run count: ${task.runCount}")
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
        delay: Duration,
        repeatInterval: Duration? = null,
    ): ScheduledTask {
        val id = "task-${idCounter.incrementAndGet()}"
        val now = Clock.System.now()

        val task = ScheduledTask(
            id = id,
            name = name,
            action = action,
            chatId = chatId,
            createdAt = now,
            nextRunTime = now + delay,
            repeatInterval = repeatInterval,
            status = TaskStatus.PENDING,
            runCount = 0,
        )

        tasks[id] = task

        // Schedule the job
        val job = scope.launch {
            delay(delay)

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
                if (repeatInterval != null && repeatInterval > Duration.ZERO) {
                    val updatedTask = tasks[id] ?: break
                    tasks[id] = updatedTask.copy(
                        status = TaskStatus.PENDING,
                        nextRunTime = Clock.System.now() + repeatInterval,
                    )
                    delay(repeatInterval)
                } else {
                    // One-time task, mark as completed
                    tasks[id] = tasks[id]?.copy(status = TaskStatus.COMPLETED) ?: break
                    break
                }
            }
        }

        jobs[id] = job
        logger.info("Scheduled task: $name (id=$id, delay=${delay})")

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
    val createdAt: Instant,
    val nextRunTime: Instant,
    val repeatInterval: Duration?,
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
