package org.drewcarlson.fraggle.api

import kotlinx.coroutines.flow.SharedFlow
import org.drewcarlson.fraggle.agent.Conversation
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.ConfigResponse
import org.drewcarlson.fraggle.models.FraggleEvent
import org.drewcarlson.fraggle.models.ScheduledTaskInfo
import org.drewcarlson.fraggle.models.SystemStatus
import org.drewcarlson.fraggle.skill.SkillRegistry

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
     * Access to configuration.
     */
    val config: ConfigService

    /**
     * Access to bridge initialization.
     */
    val bridgeInit: BridgeInitService

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
 * Service for accessing configuration.
 */
interface ConfigService {
    /**
     * Get the current configuration.
     */
    fun getConfig(): ConfigResponse
}
