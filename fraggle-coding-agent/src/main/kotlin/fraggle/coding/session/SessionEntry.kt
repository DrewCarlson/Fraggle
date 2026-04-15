package fraggle.coding.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One record in a coding-agent session JSONL file.
 *
 * Every entry has a UUID [id] and an optional [parentId] pointing at the
 * previous entry on the same branch. A branch is any linear walk from a leaf
 * back to the root entry (the one with `parentId = null`). Multiple branches
 * can share a prefix — that's how `/tree` navigation works without creating
 * new session files.
 *
 * [timestampMs] is wall-clock epoch millis from `System.currentTimeMillis()`.
 * [schemaVersion] lets us refuse-to-load future-versioned files cleanly.
 */
@Serializable
data class SessionEntry(
    val id: String,
    val parentId: String?,
    val timestampMs: Long,
    val payload: Payload,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    @Serializable
    sealed class Payload {
        /** First record in a brand new session file — carries metadata about the session itself. */
        @Serializable
        @SerialName("root")
        data class Root(
            val sessionId: String,
            val projectRoot: String,
            val model: String,
            val createdAtMs: Long,
        ) : Payload()

        /** A user turn. */
        @Serializable
        @SerialName("user")
        data class User(
            val text: String,
            /** File paths referenced via `@file` in the editor. Empty if none. */
            val attachments: List<String> = emptyList(),
        ) : Payload()

        /**
         * An assistant turn, flattened into a session-file-native shape.
         *
         * We intentionally do NOT store a serialized `AgentMessage.Assistant`
         * directly — the in-memory model in `fraggle-agent-core` is not
         * `@Serializable`, and coupling the file format to the in-memory
         * class would mean every change to `AgentMessage` is a breaking
         * schema change. Session loaders reconstruct an `AgentMessage.Assistant`
         * from this snapshot at resume time.
         */
        @Serializable
        @SerialName("assistant")
        data class Assistant(
            /** Plain-text response (concatenation of ContentPart.Text parts). */
            val text: String,
            /** Tool calls the model requested in this turn, in order. */
            val toolCalls: List<ToolCallSnapshot> = emptyList(),
            /** One of the [fraggle.agent.message.StopReason] values, as a string. */
            val stopReason: String = "STOP",
            /** Optional error message if the turn ended in an error. */
            val errorMessage: String? = null,
            /** Usage metadata if the provider reported it. */
            val usage: UsageSnapshot? = null,
        ) : Payload()

        /**
         * A tool-call result produced during an assistant turn. Stored as its
         * own entry (rather than embedded in the Assistant payload) so
         * `/tree` navigation can display tool calls as distinct nodes and
         * `Ctrl+O` can collapse them.
         */
        @Serializable
        @SerialName("tool_result")
        data class ToolResult(
            val callId: String,
            val toolName: String,
            val output: String,
            val error: String? = null,
            val durationMs: Long = 0,
        ) : Payload()

        /**
         * Metadata entry — not a conversation turn. Used by `/name`, `/label`,
         * and compaction to attach summaries or bookmarks to a branch point.
         */
        @Serializable
        @SerialName("meta")
        data class Meta(
            val label: String? = null,
            val summary: String? = null,
        ) : Payload()
    }

    /**
     * Serializable mirror of [fraggle.agent.message.ToolCall]. [argsJson] is
     * the raw JSON string of the tool arguments so we don't need a JsonElement
     * serializer at the session-format level.
     */
    @Serializable
    data class ToolCallSnapshot(
        val id: String,
        val name: String,
        val argsJson: String,
    )

    /** Serializable mirror of [fraggle.agent.message.TokenUsage]. */
    @Serializable
    data class UsageSnapshot(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val totalTokens: Int = 0,
    )

    companion object {
        /**
         * Current schema version. Bump on any breaking change to the entry
         * shape (add/rename/remove fields, change payload kinds). Readers
         * refuse to load future versions and warn on older versions.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
