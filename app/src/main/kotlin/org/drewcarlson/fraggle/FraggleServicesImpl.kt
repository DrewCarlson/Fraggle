package org.drewcarlson.fraggle

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drewcarlson.fraggle.agent.Conversation
import org.drewcarlson.fraggle.api.ConversationService
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.api.SchedulerService
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.FraggleEvent
import org.drewcarlson.fraggle.models.MemoryUsage
import org.drewcarlson.fraggle.models.ScheduledTaskInfo
import org.drewcarlson.fraggle.models.SystemStatus
import org.drewcarlson.fraggle.skill.SkillRegistry
import org.drewcarlson.fraggle.skills.scheduling.ScheduledTask
import org.drewcarlson.fraggle.skills.scheduling.TaskScheduler
import org.drewcarlson.fraggle.skills.scheduling.TaskStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Implementation of FraggleServices for the API backend.
 */
class FraggleServicesImpl(
    override val memory: MemoryStore,
    override val skills: SkillRegistry,
    override val bridges: ChatBridgeManager,
    private val taskScheduler: TaskScheduler,
    private val conversationMap: ConcurrentHashMap<String, Conversation>,
    private val startTime: Instant = Clock.System.now(),
) : FraggleServices {

    private val _events = MutableSharedFlow<FraggleEvent>(replay = 1, extraBufferCapacity = 100)
    override val events: SharedFlow<FraggleEvent> = _events.asSharedFlow()

    override val conversations: ConversationService = ConversationServiceImpl()

    override val scheduler: SchedulerService = SchedulerServiceImpl()

    override suspend fun getStatus(): SystemStatus {
        val runtime = Runtime.getRuntime()
        return SystemStatus(
            uptime = startTime - Clock.System.now(),
            activeConversations = conversationMap.size,
            connectedBridges = bridges.registeredBridges().count { bridges.isConnected(it) },
            availableSkills = skills.all().size,
            scheduledTasks = taskScheduler.listPendingTasks().size,
            memoryUsage = MemoryUsage(
                heapUsed = runtime.totalMemory() - runtime.freeMemory(),
                heapMax = runtime.maxMemory(),
            ),
        )
    }

    /**
     * Emit an event to WebSocket clients.
     */
    suspend fun emitEvent(event: FraggleEvent) {
        _events.emit(event)
    }

    private inner class ConversationServiceImpl : ConversationService {
        override fun getAll(): List<Conversation> = conversationMap.values.toList()

        override fun get(id: String): Conversation? = conversationMap[id]

        override fun getByChat(chatId: String): Conversation? =
            conversationMap.values.find { it.chatId == chatId }

        override fun clear(id: String): Boolean {
            val conversation = conversationMap[id] ?: return false
            conversationMap[id] = conversation.copy(messages = emptyList())
            return true
        }
    }

    private inner class SchedulerServiceImpl : SchedulerService {
        override fun getTasks(): List<ScheduledTaskInfo> =
            taskScheduler.listTasks().map { it.toInfo() }

        override fun getTask(id: String): ScheduledTaskInfo? =
            taskScheduler.getTask(id)?.toInfo()

        override fun cancelTask(id: String): Boolean =
            taskScheduler.cancel(id)

        private fun ScheduledTask.toInfo(): ScheduledTaskInfo {
            val interval = repeatIntervalSeconds
            return ScheduledTaskInfo(
                id = id,
                name = name,
                chatId = chatId,
                action = action,
                schedule = if (interval != null) "every ${formatDuration(interval)}" else "once",
                nextRun = if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) nextRunTime else null,
                enabled = status == TaskStatus.PENDING || status == TaskStatus.RUNNING,
            )
        }

        private fun formatDuration(seconds: Long): String = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86400 -> "${seconds / 3600}h"
            else -> "${seconds / 86400}d"
        }
    }
}
