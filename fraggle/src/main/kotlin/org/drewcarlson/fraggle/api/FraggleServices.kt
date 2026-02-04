package org.drewcarlson.fraggle.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import org.drewcarlson.fraggle.agent.Conversation
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.skill.SkillRegistry
import kotlin.time.Duration

/**
 * Services exposed by Fraggle to the API backend.
 * This interface allows the backend module to access fraggle internals
 * without creating circular dependencies.
 */
interface FraggleServices {
    /**
     * Access to active and historical conversations.
     */
    val conversations: ConversationService

    /**
     * Access to the memory store.
     */
    val memory: MemoryStore

    /**
     * Access to the skill registry.
     */
    val skills: SkillRegistry

    /**
     * Access to chat bridge management.
     */
    val bridges: ChatBridgeManager

    /**
     * Access to the task scheduler.
     */
    val scheduler: SchedulerService

    /**
     * Real-time event stream for WebSocket clients.
     */
    val events: SharedFlow<FraggleEvent>

    /**
     * Get current system status.
     */
    suspend fun getStatus(): SystemStatus
}

/**
 * Service for managing conversations.
 */
interface ConversationService {
    /**
     * Get all active conversations.
     */
    fun getAll(): List<Conversation>

    /**
     * Get a specific conversation by ID.
     */
    fun get(id: String): Conversation?

    /**
     * Get conversations for a specific chat.
     */
    fun getByChat(chatId: String): Conversation?

    /**
     * Clear a conversation's history.
     */
    fun clear(id: String): Boolean
}

/**
 * Service for managing scheduled tasks.
 */
interface SchedulerService {
    /**
     * Get all scheduled tasks.
     */
    fun getTasks(): List<ScheduledTaskInfo>

    /**
     * Get a specific task.
     */
    fun getTask(id: String): ScheduledTaskInfo?

    /**
     * Cancel a scheduled task.
     */
    fun cancelTask(id: String): Boolean
}

/**
 * Information about a scheduled task.
 */
@Serializable
data class ScheduledTaskInfo(
    val id: String,
    val name: String,
    val chatId: String,
    val action: String,
    val schedule: String,
    val nextRun: Long?,
    val enabled: Boolean,
)

/**
 * System status information.
 */
@Serializable
data class SystemStatus(
    val uptime: Duration,
    val activeConversations: Int,
    val connectedBridges: Int,
    val availableSkills: Int,
    val scheduledTasks: Int,
    val memoryUsage: MemoryUsage,
)

/**
 * Memory usage information.
 */
@Serializable
data class MemoryUsage(
    val heapUsed: Long,
    val heapMax: Long,
)

/**
 * Events emitted for real-time WebSocket updates.
 */
@Serializable
sealed class FraggleEvent {
    abstract val timestamp: Long

    /**
     * A new message was received.
     */
    @Serializable
    data class MessageReceived(
        override val timestamp: Long,
        val chatId: String,
        val senderId: String,
        val senderName: String?,
        val content: String,
    ) : FraggleEvent()

    /**
     * A response was sent.
     */
    @Serializable
    data class ResponseSent(
        override val timestamp: Long,
        val chatId: String,
        val content: String,
    ) : FraggleEvent()

    /**
     * Bridge connection status changed.
     */
    @Serializable
    data class BridgeStatusChanged(
        override val timestamp: Long,
        val bridgeName: String,
        val connected: Boolean,
        val error: String? = null,
    ) : FraggleEvent()

    /**
     * A scheduled task was triggered.
     */
    @Serializable
    data class TaskTriggered(
        override val timestamp: Long,
        val taskId: String,
        val taskName: String,
        val chatId: String,
    ) : FraggleEvent()

    /**
     * An error occurred.
     */
    @Serializable
    data class Error(
        override val timestamp: Long,
        val source: String,
        val message: String,
    ) : FraggleEvent()
}
