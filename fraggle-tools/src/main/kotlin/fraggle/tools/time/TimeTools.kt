package fraggle.tools.time

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.char
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Suppress("ObjectPropertyName")
private val _iso8601Format = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char('T')
    hour(); char(':'); minute(); char(':'); second()
}

@Suppress("UnusedReceiverParameter")
val LocalDateTime.Formats.iso8601NoOffset: DateTimeFormat<LocalDateTime>
    get() = _iso8601Format

/**
 * Tool that returns the current date and time in a specified timezone.
 * If no timezone is provided, returns the host system's local time.
 * Supports all IANA timezone IDs (e.g., "America/New_York", "Europe/London", "Asia/Tokyo").
 */
class GetCurrentTimeTool : SimpleTool<GetCurrentTimeTool.Args>(
    argsSerializer = Args.serializer(),
    name = "get_current_time",
    description = """Get the current date and time in any timezone.
Use this to answer questions about what time it is in a specific location.
Accepts IANA timezone IDs like "America/New_York", "Europe/London", "Asia/Tokyo", etc.
If no timezone is specified, returns the host server's local time.""",
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
            val formatted = local.format(LocalDateTime.Formats.iso8601NoOffset)
            val dayOfWeek = local.dayOfWeek.name.lowercase()
                .replaceFirstChar { it.uppercase() }
            return "$formatted$offset ($dayOfWeek) [$tz]"
        }
    }
}
