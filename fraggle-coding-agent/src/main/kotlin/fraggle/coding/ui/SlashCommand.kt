package fraggle.coding.ui

/**
 * Registry of slash commands available in the coding-agent editor.
 *
 * When the user types `/name` and presses Enter, the TUI dispatches to the
 * registered [SlashCommand.handler] instead of sending the text as a prompt
 * to the agent. Unknown commands surface as an error message and the text
 * stays in the editor so the user can fix the typo.
 *
 * The registry is built once at startup from [builtIn] plus any commands
 * the caller registers. For MVP we ship the small set listed below; later
 * phases can add `/tree`, `/login`, `/settings`, etc. without touching this
 * type.
 *
 * This is a straight port of [fraggle.coding.tui.SlashCommandRegistry] into
 * the new `fraggle.coding.ui` package. The parsing rules are identical; only
 * the package path has changed.
 */
class SlashCommandRegistry(commands: List<SlashCommand>) {
    private val byName: Map<String, SlashCommand> = commands.associateBy { it.name }

    /** Every registered command, sorted alphabetically for display. */
    val commands: List<SlashCommand> = commands.sortedBy { it.name }

    /**
     * Parse [input] as a slash command. Returns `null` if [input] doesn't
     * start with `/` — the caller should treat it as a regular prompt.
     * Returns [SlashCommandParse.Unknown] if the input starts with `/` but
     * the name isn't registered.
     */
    fun parse(input: String): SlashCommandParse? {
        if (!input.startsWith("/")) return null
        // Everything after the first whitespace is the argument string.
        val trimmed = input.substring(1).trimStart()
        if (trimmed.isEmpty()) return SlashCommandParse.Unknown("")
        val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
        val (name, args) = if (firstSpace < 0) {
            trimmed to ""
        } else {
            trimmed.substring(0, firstSpace) to trimmed.substring(firstSpace + 1).trim()
        }
        val command = byName[name.lowercase()]
            ?: return SlashCommandParse.Unknown(name)
        return SlashCommandParse.Matched(command, args)
    }

    companion object {
        /**
         * The built-in command set. Handlers are plugged in by the TUI
         * orchestrator when the registry is constructed — we declare the
         * *names* and *descriptions* here so they're visible to `/hotkeys`
         * and can be injected into the system prompt as available
         * templates without actually wiring the behaviour up yet.
         */
        fun builtIn(
            onNewSession: () -> Unit,
            onQuit: () -> Unit,
            onHotkeys: () -> Unit,
            onSessionInfo: () -> Unit,
        ): SlashCommandRegistry = SlashCommandRegistry(
            listOf(
                SlashCommand(
                    name = "new",
                    description = "Start a new session",
                    handler = { _ -> onNewSession() },
                ),
                SlashCommand(
                    name = "quit",
                    description = "Quit the coding agent",
                    handler = { _ -> onQuit() },
                ),
                SlashCommand(
                    name = "hotkeys",
                    description = "Show keyboard shortcuts",
                    handler = { _ -> onHotkeys() },
                ),
                SlashCommand(
                    name = "session",
                    description = "Show session info (id, file path, token count)",
                    handler = { _ -> onSessionInfo() },
                ),
            ),
        )
    }
}

/**
 * A single slash command. [name] is matched case-insensitively. [handler]
 * is invoked with the argument substring (everything after `/name `) — it
 * may be empty if the user just typed the bare command.
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val handler: (String) -> Unit,
)

/**
 * Result of parsing editor input that begins with `/`.
 */
sealed class SlashCommandParse {
    /** Input started with `/` and matched a registered command. */
    data class Matched(val command: SlashCommand, val args: String) : SlashCommandParse()

    /** Input started with `/` but the name didn't match any registered command. */
    data class Unknown(val name: String) : SlashCommandParse()
}
