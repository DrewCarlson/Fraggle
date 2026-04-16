package fraggle.scheduling

import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.LLMDescription
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import fraggle.agent.ToolExecutionContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.*
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes

@Suppress("ObjectPropertyName")
private val _fullDateTimeFormat = LocalDateTime.Format {
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
    get() = _fullDateTimeFormat

private val MIN_REPEAT_INTERVAL = 1.minutes

class ScheduleTaskTool(private val scheduler: TaskScheduler) : AgentToolDef<ScheduleTaskTool.Args>(
    name = "schedule_task",
    description = """Schedule a task for later execution.
You can schedule one-time tasks or recurring tasks.
Tasks will execute the specified action when triggered.
Each task name must be unique per chat — duplicate names return the existing task.
Only call this tool ONCE per task you want to create.""",
    argsSerializer = Args.serializer(),
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

        if (repeatInterval > Duration.ZERO && repeatInterval < MIN_REPEAT_INTERVAL) {
            return "Error: repeat_interval_seconds must be at least ${MIN_REPEAT_INTERVAL.inWholeSeconds} seconds"
        }

        val task = scheduler.schedule(
            name = args.name,
            action = args.action,
            chatId = chatId,
            delay = delay,
            repeatInterval = repeatInterval.takeIf { it > ZERO },
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

class ListTasksTool(private val scheduler: TaskScheduler) : AgentToolDef<ListTasksTool.Args>(
    name = "list_tasks",
    description = "List scheduled tasks for the current chat.",
    argsSerializer = Args.serializer(),
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String {
        val chatId = ToolExecutionContext.current()?.chatId
        val tasks = if (chatId == null) {
            scheduler.listTasks()
        } else {
            scheduler.listTasksForChat(chatId)
        }

        if (tasks.isEmpty()) {
            return "No tasks scheduled."
        }

        val listing = tasks.map { task ->
            val isActive = task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING

            buildString {
                append("- [${task.id}] ${task.name}")
                if (isActive) {
                    val nextRun = task.nextRunTime
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .format(LocalDateTime.Formats.fullDataTime)
                    append(" (next: $nextRun)")
                }
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

class CancelTaskTool(private val scheduler: TaskScheduler) : AgentToolDef<CancelTaskTool.Args>(
    name = "cancel_task",
    description = "Cancel a scheduled task by its ID.",
    argsSerializer = Args.serializer(),
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

class GetTaskTool(private val scheduler: TaskScheduler) : AgentToolDef<GetTaskTool.Args>(
    name = "get_task",
    description = "Get detailed information about a scheduled task.",
    argsSerializer = Args.serializer(),
) {
    @Serializable
    data class Args(
        @param:LLMDescription("The ID of the task")
        val task_id: String,
    )

    override suspend fun execute(args: Args): String {
        val task = scheduler.getTask(args.task_id)
            ?: return "Error: Task ${args.task_id} not found."

        val created = task.createdAt
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .format(LocalDateTime.Formats.fullDataTime)

        val isActive = task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING

        return buildString {
            appendLine("Task Details:")
            appendLine("  ID: ${task.id}")
            appendLine("  Name: ${task.name}")
            appendLine("  Status: ${task.status}")
            appendLine("  Action: ${task.action}")
            appendLine("  Chat ID: ${task.chatId}")
            appendLine("  Created: $created")
            if (isActive) {
                val nextRun = task.nextRunTime
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .format(LocalDateTime.Formats.fullDataTime)
                appendLine("  Next run: $nextRun")
            }
            if (task.repeatInterval != null) {
                appendLine("  Repeat interval: ${task.repeatInterval}")
            }
            appendLine("  Run count: ${task.runCount}")
        }
    }
}

/**
 * Task scheduler for managing scheduled tasks.
 *
 * @param store Optional persistence store. When provided, tasks survive restarts.
 *   Call [restoreFromStore] after construction to reload and reschedule active tasks.
 */
class TaskScheduler(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val store: fraggle.db.ScheduledTaskStore? = null,
    private val onTaskTriggered: suspend (ScheduledTask) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(TaskScheduler::class.java)

    private val tasks = ConcurrentHashMap<String, ScheduledTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val idCounter = AtomicLong(0)

    /**
     * Restore active tasks from the persistent store and reschedule them.
     * Tasks whose fire time has already passed are executed immediately.
     */
    fun restoreFromStore() {
        val taskStore = store ?: return
        val activeTasks = taskStore.listActive()
        if (activeTasks.isEmpty()) return

        logger.info("Restoring {} active task(s) from store", activeTasks.size)

        // Advance the ID counter past any restored IDs
        for (task in activeTasks) {
            val num = task.id.removePrefix("task-").toLongOrNull() ?: continue
            idCounter.updateAndGet { maxOf(it, num) }
        }

        val now = Clock.System.now()
        for (task in activeTasks) {
            // Reset any RUNNING tasks back to PENDING (they were interrupted)
            val restored = if (task.status == TaskStatus.RUNNING) {
                task.copy(status = TaskStatus.PENDING)
            } else {
                task
            }
            tasks[restored.id] = restored

            val remainingDelay = (restored.nextRunTime - now).coerceAtLeast(Duration.ZERO)
            startJob(restored.id, remainingDelay, restored.repeatInterval)
            logger.info("Restored task: {} (id={}, fires in {})", restored.name, restored.id, remainingDelay)
        }
    }

    /**
     * Schedule a new task. Returns an existing active task if one with the same name
     * already exists for the given chat.
     */
    fun schedule(
        name: String,
        action: String,
        chatId: String,
        delay: Duration,
        repeatInterval: Duration? = null,
    ): ScheduledTask {
        // Deduplicate: if an active task with the same name exists for this chat, return it
        val existing = tasks.values.find {
            it.chatId == chatId &&
                it.name == name &&
                (it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING)
        }
        if (existing != null) {
            logger.info("Task already exists: ${existing.name} (id=${existing.id}), skipping duplicate")
            return existing
        }

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
        persistSave(task)
        startJob(id, delay, repeatInterval)

        logger.info("Scheduled task: $name (id=$id, delay=${delay})")
        return task
    }

    private fun startJob(id: String, initialDelay: Duration, repeatInterval: Duration?) {
        val job = scope.launch {
            delay(initialDelay)

            while (isActive) {
                val currentTask = tasks[id] ?: break

                // Update status
                updateTask(id) {
                    it.copy(status = TaskStatus.RUNNING, runCount = it.runCount + 1)
                }

                val success = try {
                    onTaskTriggered(currentTask)
                    logger.info("Task executed: ${currentTask.name}")
                    true
                } catch (e: Exception) {
                    logger.error("Task execution failed: ${e.message}", e)
                    updateTask(id) { it.copy(status = TaskStatus.FAILED) }
                    false
                }

                if (!success && (repeatInterval == null || repeatInterval <= Duration.ZERO)) {
                    break
                }

                // Check if recurring
                if (success && repeatInterval != null && repeatInterval > Duration.ZERO) {
                    updateTask(id) {
                        it.copy(
                            status = TaskStatus.PENDING,
                            nextRunTime = Clock.System.now() + repeatInterval,
                        )
                    }
                    delay(repeatInterval)
                } else if (success) {
                    // One-time task, mark as completed
                    updateTask(id) { it.copy(status = TaskStatus.COMPLETED) }
                    break
                } else {
                    // Recurring task failed, retry after interval
                    updateTask(id) {
                        it.copy(
                            status = TaskStatus.PENDING,
                            nextRunTime = Clock.System.now() + repeatInterval!!,
                        )
                    }
                    delay(repeatInterval!!)
                }
            }
        }

        jobs[id] = job
    }

    /**
     * Update an in-memory task and persist the change.
     */
    private fun updateTask(id: String, transform: (ScheduledTask) -> ScheduledTask) {
        val current = tasks[id] ?: return
        val updated = transform(current)
        tasks[id] = updated
        persistUpdate(updated)
    }

    /**
     * Cancel a task.
     */
    fun cancel(taskId: String): Boolean {
        val task = tasks[taskId] ?: return false
        val job = jobs.remove(taskId)

        job?.cancel()
        tasks[taskId] = task.copy(status = TaskStatus.CANCELLED)
        persistUpdate(tasks[taskId]!!)

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
     * List tasks for a specific chat.
     */
    fun listTasksForChat(chatId: String): List<ScheduledTask> = tasks.values
        .filter { it.chatId == chatId }
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

    private fun persistSave(task: ScheduledTask) {
        try {
            store?.save(task)
        } catch (e: Exception) {
            logger.error("Failed to persist task {}: {}", task.id, e.message)
        }
    }

    private fun persistUpdate(task: ScheduledTask) {
        try {
            store?.update(task)
        } catch (e: Exception) {
            logger.error("Failed to update persisted task {}: {}", task.id, e.message)
        }
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
