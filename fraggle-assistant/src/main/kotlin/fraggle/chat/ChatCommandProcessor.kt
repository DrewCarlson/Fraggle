package fraggle.chat

import fraggle.agent.loop.ThinkingController
import fraggle.agent.skill.SkillCommandExpander
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import fraggle.provider.thinkingLevelFromUserInput
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * Processes slash commands from chat bridges and manages pending permission requests.
 *
 * Tracks [FraggleEvent.ToolPermissionRequest] events per chat and resolves them
 * when users send `/approve` or `/deny` commands. Also dispatches `/skill:name`
 * commands through [SkillCommandExpander], returning a rewritten message body that
 * the caller is expected to feed back into the agent as if the user had typed it.
 */
class ChatCommandProcessor(
    private val eventBus: EventBus,
    private val skillExpander: SkillCommandExpander? = null,
    private val skillCommandsEnabled: Boolean = true,
    /**
     * Session-scoped reasoning-level override. When non-null, `/think <level>`
     * is recognised and mutates this controller; the LLM bridge reads it on
     * the next agent call. `null` disables the `/think` command entirely.
     * In-memory only — nothing is persisted.
     */
    private val thinkingController: ThinkingController? = null,
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
        val trimmed = text.trimStart()
        if (skillCommandsEnabled && skillExpander != null && trimmed.startsWith(SkillCommandExpander.PREFIX)) {
            return when (val r = skillExpander.tryExpand(trimmed)) {
                is SkillCommandExpander.Result.Expanded -> CommandResult.Expanded(r.skill.name, r.text)
                is SkillCommandExpander.Result.UnknownSkill -> CommandResult.UnknownSkill(r.name)
                is SkillCommandExpander.Result.MalformedCommand -> CommandResult.MalformedSkill(r.reason)
                is SkillCommandExpander.Result.ReadError -> CommandResult.SkillReadError(r.name, r.reason)
                SkillCommandExpander.Result.NotASkillCommand -> CommandResult.Unknown(trimmed.substringBefore(' '))
            }
        }

        val command = trimmed.substringBefore(' ').lowercase()
        return when (command) {
            "/approve" -> resolvePermission(chatId, approved = true)
            "/deny" -> resolvePermission(chatId, approved = false)
            "/think" -> handleThink(trimmed)
            else -> CommandResult.Unknown(command)
        }
    }

    /**
     * `/think <level>` — mutate the shared [ThinkingController] for subsequent
     * LLM calls. Returns [CommandResult.ThinkingSet] on success or
     * [CommandResult.ThinkingInvalid] on unknown arguments. If no controller
     * is installed on this processor, returns [CommandResult.Unknown] as if
     * the command didn't exist.
     */
    private fun handleThink(trimmed: String): CommandResult {
        val controller = thinkingController ?: return CommandResult.Unknown("/think")
        // Trimmed starts with "/think" (case-insensitive); strip that prefix
        // and any trailing whitespace to get just the argument.
        val args = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
        val level = try {
            thinkingLevelFromUserInput(args)
        } catch (e: IllegalStateException) {
            return CommandResult.ThinkingInvalid(args.ifBlank { "(no value)" })
        }
        controller.level = level
        return CommandResult.ThinkingSet(level?.name?.lowercase() ?: "default")
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

    /**
     * `/skill:name` successfully expanded. [newText] should replace the user's message
     * text and be passed through to the agent as a normal message.
     */
    data class Expanded(val skillName: String, val newText: String) : CommandResult()

    /** `/skill:name` referenced a skill that is not in the registry. */
    data class UnknownSkill(val name: String) : CommandResult()

    /** `/skill:` syntax error (e.g. bare `/skill:` with no name). */
    data class MalformedSkill(val reason: String) : CommandResult()

    /** Failed to read the SKILL.md file for a valid skill name. */
    data class SkillReadError(val name: String, val reason: String) : CommandResult()

    /**
     * `/think <level>` accepted — the session reasoning-level override is now
     * [level] (lowercase name, or `"default"` when the user asked to clear).
     */
    data class ThinkingSet(val level: String) : CommandResult()

    /** `/think` with an argument that didn't parse as a level. */
    data class ThinkingInvalid(val raw: String) : CommandResult()
}
