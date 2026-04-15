package fraggle.coding.session

import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.agent.message.TokenUsage
import fraggle.agent.message.ToolCall

/**
 * Replay a session tree's current branch as a sequence of `AgentMessage`s,
 * ready to be passed to [fraggle.coding.CodingAgentOptions.initialMessages].
 *
 * This is the inverse of `CodingAgent.persistNewMessages`: it walks the
 * same branch that [SessionTree.currentBranch] returns, skips the root and
 * any [SessionEntry.Payload.Meta] entries (they're metadata, not
 * conversation), and reconstructs `AgentMessage.User` /
 * `AgentMessage.Assistant` / `AgentMessage.ToolResult` records from the
 * stored snapshots.
 *
 * The round-trip is lossless for the fields the coding agent actually
 * cares about: user text, assistant text, tool calls (id/name/arguments),
 * tool results (call id, name, output, error flag), stop reason, usage.
 * Timestamps get refreshed to "now" because the stored timestamps are
 * wall-clock epoch millis and AgentMessage uses `kotlin.time.Instant`; a
 * resumed session has no meaningful "when did this happen" anyway — we
 * reconstruct the conversation state, not the chronology.
 */
fun SessionTree.replayCurrentBranch(): List<AgentMessage> {
    if (entries.isEmpty()) return emptyList()
    val branch = currentBranch()
    return branch.mapNotNull { entry ->
        when (val payload = entry.payload) {
            is SessionEntry.Payload.Root -> null
            is SessionEntry.Payload.Meta -> null  // metadata, not conversation
            is SessionEntry.Payload.User -> AgentMessage.User(
                content = listOf(ContentPart.Text(payload.text)),
            )
            is SessionEntry.Payload.Assistant -> AgentMessage.Assistant(
                content = if (payload.text.isEmpty()) emptyList() else listOf(ContentPart.Text(payload.text)),
                toolCalls = payload.toolCalls.map { snap ->
                    ToolCall(id = snap.id, name = snap.name, arguments = snap.argsJson)
                },
                stopReason = parseStopReason(payload.stopReason),
                errorMessage = payload.errorMessage,
                usage = payload.usage?.let { u ->
                    TokenUsage(
                        promptTokens = u.inputTokens,
                        completionTokens = u.outputTokens,
                        totalTokens = u.totalTokens,
                    )
                },
            )
            is SessionEntry.Payload.ToolResult -> AgentMessage.ToolResult(
                toolCallId = payload.callId,
                toolName = payload.toolName,
                text = payload.output,
                isError = payload.error != null,
            )
        }
    }
}

/** Tolerant parse: unknown stop reasons fall back to STOP. */
private fun parseStopReason(value: String): StopReason = try {
    StopReason.valueOf(value)
} catch (_: IllegalArgumentException) {
    StopReason.STOP
}
