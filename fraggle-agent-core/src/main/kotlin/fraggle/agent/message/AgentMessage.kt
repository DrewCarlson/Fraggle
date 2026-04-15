package fraggle.agent.message

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Unified message type for the agent loop.
 * Carries both LLM-compatible messages and Fraggle-specific metadata.
 */
sealed class AgentMessage {
    abstract val timestamp: Instant

    data class User(
        val content: List<ContentPart>,
        override val timestamp: Instant = Clock.System.now(),
    ) : AgentMessage() {
        constructor(text: String, timestamp: Instant = Clock.System.now()) : this(
            content = listOf(ContentPart.Text(text)),
            timestamp = timestamp,
        )
    }

    data class Assistant(
        val content: List<ContentPart> = emptyList(),
        val toolCalls: List<ToolCall> = emptyList(),
        val stopReason: StopReason = StopReason.STOP,
        val errorMessage: String? = null,
        val usage: TokenUsage? = null,
        override val timestamp: Instant = Clock.System.now(),
    ) : AgentMessage() {
        /** Convenience: extract all text content joined. */
        val textContent: String
            get() = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    }

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: List<ContentPart>,
        val isError: Boolean = false,
        override val timestamp: Instant = Clock.System.now(),
    ) : AgentMessage() {
        constructor(
            toolCallId: String,
            toolName: String,
            text: String,
            isError: Boolean = false,
            timestamp: Instant = Clock.System.now(),
        ) : this(
            toolCallId = toolCallId,
            toolName = toolName,
            content = listOf(ContentPart.Text(text)),
            isError = isError,
            timestamp = timestamp,
        )

        /** Convenience: extract text content. */
        val textContent: String
            get() = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    }

    data class Platform(
        val platform: String,
        val data: Any,
        override val timestamp: Instant = Clock.System.now(),
    ) : AgentMessage()
}

enum class StopReason { STOP, ERROR, ABORTED }

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

sealed class ContentPart {
    data class Text(val text: String) : ContentPart()
    data class Image(
        val data: ByteArray,
        val mimeType: String,
        val fileName: String? = null,
    ) : ContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType && fileName == other.fileName
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + (fileName?.hashCode() ?: 0)
            return result
        }
    }
}

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)
