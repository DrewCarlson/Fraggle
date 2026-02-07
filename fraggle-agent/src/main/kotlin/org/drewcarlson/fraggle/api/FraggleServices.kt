package org.drewcarlson.fraggle.api

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.flow.SharedFlow
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.ChatDetail
import org.drewcarlson.fraggle.models.ChatMessageRecord
import org.drewcarlson.fraggle.models.ChatSummary
import org.drewcarlson.fraggle.models.ConfigResponse
import org.drewcarlson.fraggle.models.FraggleEvent
import org.drewcarlson.fraggle.models.ScheduledTaskInfo
import org.drewcarlson.fraggle.models.SystemStatus

/**
 * Services exposed by Fraggle to the API backend.
 * This interface allows the backend module to access fraggle internals
 * without creating circular dependencies.
 */
interface FraggleServices {
    /**
     * Access to the memory store.
     */
    val memory: MemoryStore

    /**
     * Access to the Koog tool registry.
     */
    val toolRegistry: ToolRegistry

    /**
     * Access to chat bridge management.
     */
    val bridges: ChatBridgeManager

    /**
     * Access to the task scheduler.
     */
    val scheduler: SchedulerService

    /**
     * Access to persisted chat history.
     */
    val chatHistory: ChatHistoryService

    /**
     * Access to configuration.
     */
    val config: ConfigService

    /**
     * Access to bridge initialization.
     */
    val bridgeInit: BridgeInitService

    /**
     * Access to Discord OAuth operations.
     */
    val discordOAuth: DiscordOAuthService?

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
 * Service for accessing persisted chat history.
 */
interface ChatHistoryService {
    /**
     * List all chats with message counts.
     */
    fun listChats(limit: Int = 50, offset: Long = 0): List<ChatSummary>

    /**
     * Get a specific chat with statistics.
     */
    fun getChat(id: Long): ChatDetail?

    /**
     * Get messages for a chat.
     */
    fun getMessages(chatId: Long, limit: Int = 50, offset: Long = 0): List<ChatMessageRecord>

    /**
     * Total number of chats.
     */
    fun countChats(): Long
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
