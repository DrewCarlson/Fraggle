package fraggle.provider

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine

/**
 * Parse a plain OpenAI-style Server-Sent Events stream (no `event:` field,
 * just `data:` lines terminated by the sentinel `data: [DONE]`).
 *
 * For each `data:` payload, [onData] is invoked with the raw string between
 * the prefix and the end of line. The `[DONE]` sentinel is passed through
 * unchanged so the caller can terminate its own state machine.
 *
 * Keepalive comment lines (`:` prefix) and blank event boundaries are
 * skipped silently. Read errors propagate to the caller.
 */
internal suspend fun parseOpenAISse(
    channel: ByteReadChannel,
    onData: suspend (String) -> Unit,
) {
    while (!channel.isClosedForRead) {
        val line = channel.readLine() ?: break

        when {
            line.startsWith("data: ") -> {
                val data = line.removePrefix("data: ").trim()
                if (data.isNotEmpty()) onData(data)
            }
            line.isBlank() -> Unit // event boundary
            line.startsWith(":") -> Unit // keepalive comment
        }
    }
}
