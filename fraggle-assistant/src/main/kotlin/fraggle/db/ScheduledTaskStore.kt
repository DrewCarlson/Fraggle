package fraggle.db

import fraggle.scheduling.ScheduledTask
import fraggle.scheduling.TaskStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Persistence layer for scheduled tasks.
 */
interface ScheduledTaskStore {
    fun save(task: ScheduledTask)
    fun update(task: ScheduledTask)
    fun getById(id: String): ScheduledTask?
    fun listAll(): List<ScheduledTask>
    fun listActive(): List<ScheduledTask>
    fun delete(id: String)
}

/**
 * SQLite-backed implementation using Exposed.
 */
class ExposedScheduledTaskStore(
    private val db: FraggleDatabase,
) : ScheduledTaskStore {

    override fun save(task: ScheduledTask): Unit = db.transact {
        ScheduledTaskTable.insert {
            it[id] = task.id
            it[name] = task.name
            it[action] = task.action
            it[chatId] = task.chatId
            it[createdAt] = task.createdAt
            it[nextRunTime] = task.nextRunTime
            it[repeatInterval] = task.repeatInterval
            it[status] = task.status
            it[runCount] = task.runCount
        }
    }

    override fun update(task: ScheduledTask): Unit = db.transact {
        ScheduledTaskTable.update({ ScheduledTaskTable.id eq task.id }) {
            it[nextRunTime] = task.nextRunTime
            it[repeatInterval] = task.repeatInterval
            it[status] = task.status
            it[runCount] = task.runCount
        }
    }

    override fun getById(id: String): ScheduledTask? = db.query {
        ScheduledTaskTable.selectAll()
            .where { ScheduledTaskTable.id eq id }
            .singleOrNull()
            ?.toScheduledTask()
    }

    override fun listAll(): List<ScheduledTask> = db.query {
        ScheduledTaskTable.selectAll()
            .map { it.toScheduledTask() }
    }

    override fun listActive(): List<ScheduledTask> = db.query {
        ScheduledTaskTable.selectAll()
            .where {
                (ScheduledTaskTable.status eq TaskStatus.PENDING) or
                    (ScheduledTaskTable.status eq TaskStatus.RUNNING)
            }
            .map { it.toScheduledTask() }
    }

    override fun delete(id: String) = db.transact {
        ScheduledTaskTable.deleteWhere { ScheduledTaskTable.id eq id }
        Unit
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toScheduledTask() = ScheduledTask(
        id = this[ScheduledTaskTable.id],
        name = this[ScheduledTaskTable.name],
        action = this[ScheduledTaskTable.action],
        chatId = this[ScheduledTaskTable.chatId],
        createdAt = this[ScheduledTaskTable.createdAt],
        nextRunTime = this[ScheduledTaskTable.nextRunTime],
        repeatInterval = this[ScheduledTaskTable.repeatInterval],
        status = this[ScheduledTaskTable.status],
        runCount = this[ScheduledTaskTable.runCount],
    )
}
