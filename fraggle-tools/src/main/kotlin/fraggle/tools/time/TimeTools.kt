package fraggle.tools.time

import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.LLMDescription
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tool that returns the current date and time in a specified timezone.
 * If no timezone is provided, returns the host system's local time.
 * Supports all IANA timezone IDs (e.g., "America/New_York", "Europe/London", "Asia/Tokyo").
 */
class GetCurrentTimeTool : AgentToolDef<GetCurrentTimeTool.Args>(
    name = "get_current_time",
    description = """Get the current date and time in any timezone.
Use this to answer questions about what time it is in a specific location.
Accepts IANA timezone IDs like "America/New_York", "Europe/London", "Asia/Tokyo", etc.
If no timezone is specified, returns the host server's local time.""",
    argsSerializer = Args.serializer(),
) {
    @Serializable
    data class Args(
        @param:LLMDescription(
            "IANA timezone ID (e.g., \"America/New_York\", \"Europe/Berlin\", \"Asia/Tokyo\"). " +
                "Leave empty for the host server's local time.",
        )
        val timezone: String = "",
    )

    override suspend fun execute(args: Args): String {
        val now = Clock.System.now()

        val tz = if (args.timezone.isBlank()) {
            TimeZone.currentSystemDefault()
        } else {
            try {
                TimeZone.of(args.timezone)
            } catch (_: IllegalArgumentException) {
                return "Error: Unknown timezone '${args.timezone}'. " +
                    "Use IANA timezone IDs like \"America/New_York\", \"Europe/London\", \"Asia/Tokyo\"."
            }
        }

        return formatTimeResponse(now, tz)
    }

    companion object {
        fun formatTimeResponse(instant: Instant, tz: TimeZone): String {
            val local = instant.toLocalDateTime(tz)
            val offset = instant.offsetIn(tz)
            val formatted = local.format(LocalDateTime.Formats.ISO)
            val dayOfWeek = local.dayOfWeek.name.lowercase()
                .replaceFirstChar { it.uppercase() }
            return "$formatted$offset ($dayOfWeek) [$tz]"
        }
    }
}
