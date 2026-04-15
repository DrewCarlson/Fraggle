package fraggle.coding.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart

/**
 * Renders the conversation history in the center panel.
 *
 * Layout: each message is rendered as a small block with a leading role
 * marker. Tool calls inside an assistant message appear as indented
 * bullets. Tool results (separate `ToolResult` messages) render after their
 * parent assistant, so the visual order matches the call→result flow.
 *
 * [streamingMessage] is the in-flight assistant message (non-null while the
 * LLM is still producing tokens). It renders AFTER the completed messages
 * to preserve the linear order of the conversation.
 */
@Composable
fun MessageList(
    messages: List<AgentMessage>,
    streamingMessage: AgentMessage.Assistant?,
) {
    Column {
        // Skip any Platform messages — coding agent doesn't produce them.
        for (msg in messages) {
            when (msg) {
                is AgentMessage.User -> UserRow(msg)
                is AgentMessage.Assistant -> AssistantRow(msg, streaming = false)
                is AgentMessage.ToolResult -> ToolResultRow(msg)
                is AgentMessage.Platform -> Unit
            }
        }
        if (streamingMessage != null) {
            AssistantRow(streamingMessage, streaming = true)
        }
    }
}

@Composable
private fun UserRow(msg: AgentMessage.User) {
    val text = msg.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    Column {
        Row {
            Text("» ", color = Theme.userText)
            Text(text, color = Theme.foreground)
        }
        Text("", color = Theme.foreground)
    }
}

@Composable
private fun AssistantRow(msg: AgentMessage.Assistant, streaming: Boolean) {
    val text = msg.textContent
    Column {
        Row {
            Text("◆ ", color = Theme.accent)
            // Blank text during streaming with no content yet keeps the row height stable
            Text(text.ifEmpty { if (streaming) "…" else "" }, color = Theme.assistantText)
        }
        // Tool calls render as indented bullets beneath the assistant's text
        for (call in msg.toolCalls) {
            Row {
                Text("  └─ ", color = Theme.veryDim)
                Text(call.name, color = Theme.toolCall)
                if (call.arguments.isNotBlank() && call.arguments != "{}") {
                    Text(" ", color = Theme.veryDim)
                    Text(compactJson(call.arguments), color = Theme.dim)
                }
            }
        }
        if (msg.errorMessage != null) {
            Row {
                Text("  ! ", color = Theme.error)
                Text(msg.errorMessage!!, color = Theme.error)
            }
        }
        Text("", color = Theme.foreground)
    }
}

@Composable
private fun ToolResultRow(msg: AgentMessage.ToolResult) {
    val roleColor = if (msg.isError) Theme.toolError else Theme.toolResult
    val text = msg.textContent
    Column {
        Row {
            Text("     ", color = Theme.veryDim)
            Text(if (msg.isError) "✗ " else "← ", color = roleColor)
            Text(msg.toolName, color = Theme.toolCall)
            Text(": ", color = Theme.veryDim)
            Text(truncate(text, max = 200), color = roleColor)
        }
        Text("", color = Theme.foreground)
    }
}

/**
 * Compact a JSON argument blob for inline display in a tool-call row. We
 * don't parse — we just trim whitespace and truncate. Structured pretty-printing
 * and collapse/expand is a future-work item.
 */
private fun compactJson(json: String): String {
    val compact = json.replace(Regex("\\s+"), " ").trim()
    return truncate(compact, max = 80)
}

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.substring(0, max - 1) + "…"
