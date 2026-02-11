package fraggle.chat

import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * Processes slash commands from chat bridges and manages pending permission requests.
 *
 * Tracks [FraggleEvent.ToolPermissionRequest] events per chat and resolves them
 * when users send `/approve` or `/deny` commands.
 */
class ChatCommandProcessor(
    private val eventBus: EventBus,
) {
    private val pendingRequests = ConcurrentHashMap<String, String>()

    /**
     * Check if a message text is a slash command.
     */
    fun isCommand(text: String): Boolean = text.startsWith("/")

    /**
     * Track a permission request for a chat, so `/approve` or `/deny` can resolve it.
     */
    fun trackPermissionRequest(chatId: String, requestId: String) {
        pendingRequests[chatId] = requestId
    }

    /**
     * Clear a tracked permission request by its request ID.
     */
    fun clearPermissionRequest(requestId: String) {
        pendingRequests.entries.removeIf { it.value == requestId }
    }

    /**
     * Handle a slash command from a chat message.
     *
     * @return the result indicating what action was taken
     */
    suspend fun handleCommand(chatId: String, text: String): CommandResult {
        val command = text.trim().substringBefore(' ').lowercase()
        return when (command) {
            "/approve" -> resolvePermission(chatId, approved = true)
            "/deny" -> resolvePermission(chatId, approved = false)
            else -> CommandResult.Unknown(command)
        }
    }

    private suspend fun resolvePermission(chatId: String, approved: Boolean): CommandResult {
        val requestId = pendingRequests.remove(chatId)
            ?: return CommandResult.NoPermissionPending
        eventBus.emit(
            FraggleEvent.ToolPermissionGranted(
                timestamp = Clock.System.now(),
                requestId = requestId,
                approved = approved,
            )
        )
        return if (approved) CommandResult.Approved else CommandResult.Denied
    }
}

/**
 * Result of processing a slash command.
 */
sealed class CommandResult {
    data object Approved : CommandResult()
    data object Denied : CommandResult()
    data object NoPermissionPending : CommandResult()
    data class Unknown(val command: String) : CommandResult()
}
