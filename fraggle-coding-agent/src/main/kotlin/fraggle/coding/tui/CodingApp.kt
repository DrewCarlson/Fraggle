package fraggle.coding.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import fraggle.agent.compaction.ContextUsage
import fraggle.agent.event.AgentEvent
import fraggle.agent.message.AgentMessage
import fraggle.coding.CodingAgent
import fraggle.coding.CodingAgentOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Root composable for the coding-agent TUI.
 *
 * Wires everything together:
 *  - Subscribes to the [CodingAgent]'s event stream and projects events
 *    into Compose state (message list, streaming message, busy flag, error).
 *  - Collects [TuiToolPermissionHandler.pending] so an approval overlay
 *    appears whenever a tool call is awaiting user consent.
 *  - Owns the single top-level `Modifier.onKeyEvent` so all input routes
 *    through one handler. Behaviour branches on UI state (overlay visible /
 *    buffer empty / agent busy / etc).
 *
 * Designed to be run via:
 * ```
 * runMosaicBlocking { CodingApp(agent, options, header, footer, onExit, permissionHandler) }
 * ```
 * by the CLI command. The app owns no side-effects outside the agent and
 * the permission handler; it has no knowledge of stdout, stdin, or the
 * filesystem.
 */
@Composable
fun CodingApp(
    agent: CodingAgent,
    options: CodingAgentOptions,
    header: HeaderInfo,
    supervisionLabel: String,
    onExitRequest: () -> Unit,
    permissionHandler: TuiToolPermissionHandler? = null,
) {
    val scope = rememberCoroutineScope()

    // Mirror of the agent's message history. Seeded from agent.state and
    // updated as events arrive.
    val messages = remember { mutableStateListOf<AgentMessage>() }
    var streamingMessage by remember { mutableStateOf<AgentMessage.Assistant?>(null) }

    // Editor state (multi-line buffer + cursor).
    var editor by remember { mutableStateOf(EditorState()) }

    // Lifecycle flags.
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    // Auto-clear the confirmExit warning 1s after it's armed. If the user
    // presses the quit key again before then we've already exited; if they
    // don't, the warning quietly disappears.
    LaunchedEffect(confirmExit) {
        if (confirmExit) {
            kotlinx.coroutines.delay(1000)
            confirmExit = false
        }
    }

    // Pending tool-call approval, projected from the handler's state flow.
    var pendingApproval by remember { mutableStateOf<fraggle.coding.tui.PendingApproval?>(null) }

    // Multi-line informational notice (e.g. /hotkeys, /session output). Shown
    // above the editor, cleared on the next Enter submit.
    var notice by remember { mutableStateOf<List<String>?>(null) }

    val slashCommands = remember(agent) {
        SlashCommandRegistry.builtIn(
            onNewSession = {
                notice = listOf(
                    "/new: not available from inside a running session.",
                    "Exit (Esc Esc / Ctrl+C) and relaunch `fraggle code` for a new session.",
                )
            },
            onQuit = { onExitRequest() },
            onHotkeys = { notice = HotkeysHelp },
            onSessionInfo = {
                notice = listOf(
                    "session id: ${agent.sessionId}",
                    "file:       ${agent.sessionFile}",
                )
            },
        )
    }

    // Subscribe to agent events once. The lambda runs on whichever coroutine
    // the agent loop is dispatching from; Compose state writes from other
    // threads are safe via Snapshot.
    LaunchedEffect(agent) {
        // Seed initial messages from the agent's state (covers resumed sessions).
        messages.clear()
        messages.addAll(agent.state.messages)

        agent.events().collect { event ->
            when (event) {
                is AgentEvent.MessageStart -> {
                    (event.message as? AgentMessage.Assistant)?.let {
                        streamingMessage = it
                    }
                }
                is AgentEvent.MessageUpdate -> {
                    streamingMessage = event.message
                }
                is AgentEvent.MessageEnd -> {
                    streamingMessage = null
                    messages += event.message
                }
                is AgentEvent.TurnEnd -> {
                    val msg = event.message
                    if (msg is AgentMessage.Assistant && msg.errorMessage != null) {
                        errorMessage = msg.errorMessage
                    }
                }
                else -> Unit
            }
        }
    }

    // Collect pending approval state. Only runs when a handler is provided
    // (NONE supervision mode skips this).
    if (permissionHandler != null) {
        LaunchedEffect(permissionHandler) {
            permissionHandler.pending.collectLatest { approval ->
                pendingApproval = approval
            }
        }
    }

    val status = when {
        errorMessage != null -> FooterStatus.ERROR
        pendingApproval != null -> FooterStatus.AWAITING_APPROVAL
        busy -> FooterStatus.BUSY
        else -> FooterStatus.IDLE
    }

    val usage = ContextUsage.fromMessages(messages.toList(), options.contextWindowTokens)

    Column(
        modifier = Modifier.onKeyEvent { event ->
            handleKey(
                event = event,
                editor = editor,
                busy = busy,
                pending = pendingApproval,
                confirmExit = confirmExit,
                onEditorChange = { editor = it },
                onSubmit = submit@{
                    val text = editor.text
                    if (text.isBlank()) return@submit
                    when (val parsed = slashCommands.parse(text)) {
                        is SlashCommandParse.Matched -> {
                            editor = EditorState()
                            errorMessage = null
                            confirmExit = false
                            parsed.command.handler(parsed.args)
                            return@submit
                        }
                        is SlashCommandParse.Unknown -> {
                            editor = EditorState()
                            confirmExit = false
                            notice = null
                            errorMessage = "unknown slash command: /${parsed.name}"
                            return@submit
                        }
                        null -> Unit
                    }
                    editor = EditorState()
                    errorMessage = null
                    notice = null
                    confirmExit = false
                    scope.launch {
                        busy = true
                        try {
                            agent.prompt(text)
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // User hit escape — treat as a normal early
                            // return, not an error. Swallow silently; the
                            // partial session was already persisted by
                            // CodingAgent.prompt's finally block.
                        } catch (e: Exception) {
                            errorMessage = e.message ?: e::class.simpleName ?: "error"
                        } finally {
                            busy = false
                        }
                    }
                },
                onAbort = {
                    if (busy) agent.abort()
                },
                onExit = { onExitRequest() },
                onRequestExitConfirm = { confirmExit = true },
                onCancelExitConfirm = { if (confirmExit) confirmExit = false },
                onClearError = { errorMessage = null },
                onApprove = {
                    permissionHandler?.approve()
                    pendingApproval = null
                },
                onDeny = {
                    permissionHandler?.deny()
                    pendingApproval = null
                },
            )
        },
    ) {
        Header(header)
        MessageList(messages = messages.toList(), streamingMessage = streamingMessage)

        pendingApproval?.let { approval ->
            ApprovalOverlay(approval)
        }

        Editor(state = editor, enabled = !busy && pendingApproval == null)

        errorMessage?.let { msg ->
            Row {
                Text("  ! error: ", color = Theme.error)
                Text(msg, color = Theme.error)
            }
        }

        notice?.let { lines ->
            Column {
                for (line in lines) {
                    Row { Text("  $line", color = Theme.accent) }
                }
            }
        }

        Footer(
            FooterInfo(
                cwd = options.workDir,
                sessionId = agent.state.messages.firstOrNull()?.let { "active" } ?: "new",
                usedTokens = usage.usedTokens,
                contextRatio = usage.ratio,
                status = status,
                supervisionLabel = supervisionLabel,
                confirmExit = confirmExit,
            ),
        )
    }
}

/**
 * The top-level key router. Dispatches on UI state: approval overlay takes
 * precedence (only Y/N active), then editor input, then exit/cancel.
 *
 * Returns `true` when the event was consumed and `false` when it should
 * propagate — though in practice the whole TUI is a single `onKeyEvent`
 * surface so propagation has nowhere to go; we return `false` only for
 * truly unhandled events (F-keys, etc.) so Mosaic doesn't think we're
 * eating everything.
 */
@Suppress("LongParameterList")
private fun handleKey(
    event: KeyEvent,
    editor: EditorState,
    busy: Boolean,
    pending: fraggle.coding.tui.PendingApproval?,
    confirmExit: Boolean,
    onEditorChange: (EditorState) -> Unit,
    onSubmit: () -> Unit,
    onAbort: () -> Unit,
    onExit: () -> Unit,
    onRequestExitConfirm: () -> Unit,
    onCancelExitConfirm: () -> Unit,
    onClearError: () -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
): Boolean {
    // 1. Approval overlay intercepts everything — user must answer Y/N,
    //    Escape, or Ctrl+C (all three cancel the pending tool call).
    if (pending != null) {
        return when (event) {
            KeyEvent("y"), KeyEvent("Y") -> { onApprove(); true }
            KeyEvent("n"), KeyEvent("N") -> { onDeny(); true }
            KeyEvent("Escape"), KeyEvent("c", ctrl = true) -> { onDeny(); true }
            else -> true // eat the key so it doesn't bleed into the editor
        }
    }

    // 2. Exit handling: both Ctrl+C and Escape are layered — they abort a
    //    running turn first, and otherwise require a double-press to quit.
    when (event) {
        KeyEvent("c", ctrl = true) -> {
            when {
                busy -> { onAbort(); return true }
                !editor.isEmpty -> { onEditorChange(EditorState()); return true }
                confirmExit -> { onExit(); return true }
                else -> { onRequestExitConfirm(); return true }
            }
        }
        KeyEvent("Escape") -> {
            when {
                busy -> { onAbort(); return true }
                confirmExit -> { onExit(); return true }
                else -> { onRequestExitConfirm(); return true }
            }
        }
        else -> Unit
    }

    // Any key other than Esc/Ctrl+C cancels a pending exit confirmation.
    onCancelExitConfirm()

    // 3. While busy, eat all keys except the ones handled above. This
    //    prevents the user from typing into an editor that would otherwise
    //    be locked behind a running agent turn — pi allows editing while
    //    busy and queues the message; that's future work.
    if (busy) return true

    // 4. Editor input.
    return when (event) {
        KeyEvent("Enter") -> {
            onClearError()
            onSubmit()
            true
        }
        KeyEvent("Enter", shift = true) -> {
            onEditorChange(editor.newline())
            true
        }
        KeyEvent("Backspace") -> { onEditorChange(editor.backspace()); true }
        KeyEvent("Delete") -> { onEditorChange(editor.delete()); true }
        KeyEvent("ArrowLeft") -> { onEditorChange(editor.moveLeft()); true }
        KeyEvent("ArrowRight") -> { onEditorChange(editor.moveRight()); true }
        KeyEvent("ArrowUp") -> { onEditorChange(editor.moveUp()); true }
        KeyEvent("ArrowDown") -> { onEditorChange(editor.moveDown()); true }
        KeyEvent("Home") -> { onEditorChange(editor.moveHome()); true }
        KeyEvent("End") -> { onEditorChange(editor.moveEnd()); true }
        else -> {
            // Single-character printable keys (no modifiers).
            if (event.key.length == 1 && !event.ctrl && !event.alt) {
                onEditorChange(editor.type(event.key[0]))
                true
            } else {
                false
            }
        }
    }
}

private val HotkeysHelp: List<String> = listOf(
    "keys:",
    "  Enter         send message / run slash command",
    "  Shift+Enter   newline in editor",
    "  Esc           abort running turn or confirm exit",
    "  Ctrl+C        abort running turn, clear editor, deny pending tool call, or confirm exit",
    "  ←/→/↑/↓       move cursor   •   Home/End   line start/end",
    "  Backspace/Delete  edit buffer",
    "  Y / N         approve / deny pending tool call",
    "",
    "slash commands:  /hotkeys  /session  /new  /quit",
)
