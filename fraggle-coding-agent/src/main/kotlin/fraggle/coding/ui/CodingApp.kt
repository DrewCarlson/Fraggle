package fraggle.coding.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.tty.Tty
import fraggle.agent.compaction.ContextUsage
import fraggle.agent.event.AgentEvent
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.skill.SkillCommandExpander
import fraggle.coding.CodingAgent
import fraggle.coding.CodingAgentOptions
import fraggle.tui.core.Component
import fraggle.tui.core.Container
import fraggle.tui.core.Focusable
import fraggle.tui.core.TUI
import fraggle.tui.core.TtyOutput
import fraggle.tui.input.matches
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth
import fraggle.tui.text.wordWrap
import fraggle.tui.ui.Autocompletion
import fraggle.tui.ui.CompositeAutocompleteProvider
import fraggle.tui.ui.Editor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Terminal orchestrator for the coding agent, built on top of the fraggle-tui
 * runtime. Replaces the Compose-based [fraggle.coding.tui.CodingApp].
 *
 * The visible tree is a flat vertical stack:
 *
 * ```
 * TUI (root Container)
 * ├── HeaderBanner          (once, top)
 * ├── history Container     (UserMessage / AssistantMessage / ToolExecution, appended over time)
 * ├── StreamingMessage      (added when an assistant turn begins; removed on turn end)
 * ├── bottom chrome Container
 * │    ├── notice rows      (ephemeral — /hotkeys, /session)
 * │    ├── error row        (single line — cleared on next submit)
 * │    ├── Editor           (focused by default)
 * │    └── Footer           (status + cwd + tokens + supervision)
 * └── ApprovalOverlay       (present only when a tool call is awaiting consent)
 * ```
 *
 * Responsibilities:
 *  - Subscribe to [CodingAgent.events] and mutate the tree in response.
 *  - Collect [TuiToolPermissionHandler.pending] and add/remove
 *    the [ApprovalOverlay].
 *  - Own the slash-command registry and optional [SkillCommandExpander]; route
 *    editor submissions through both before sending to the agent.
 *  - Handle global keys (Ctrl+C / Esc = abort-or-exit, double-press to quit).
 *  - Thread bracketed-paste notifications from the runtime into the editor so
 *    Enter inserts a newline mid-paste instead of submitting.
 *
 * The orchestrator runs for the lifetime of a single CLI invocation. Call
 * [start] to bring up the TUI, and [stop] (or exit via callback) to tear it down.
 */
class CodingApp(
    private val agent: CodingAgent,
    private val options: CodingAgentOptions,
    private val header: HeaderInfo,
    private val supervisionLabel: String,
    private val skillExpander: SkillCommandExpander? = null,
    private val permissionHandler: TuiToolPermissionHandler? = null,
    /**
     * Supplier for `/skill:<name>` autocomplete entries. Invoked every time
     * the user opens the `/` popup, so newly-installed skills appear on the
     * next keystroke without a restart. Return an empty list to disable
     * skill completion.
     *
     * Kept as a live lambda (rather than a captured list) because the skill
     * registry is loaded from disk on every message in the assistant path,
     * and the autocomplete should reflect the same freshness.
     */
    private val skillCompletionsProvider: () -> List<Autocompletion> = { emptyList() },
    private val onExit: () -> Unit,
) {
    // ── Runtime ────────────────────────────────────────────────────────────

    private var tui: TUI? = null
    private var scope: CoroutineScope? = null

    // ── Tree pieces ────────────────────────────────────────────────────────

    private val headerBanner = HeaderBanner(header)
    private val history = Container()
    private val bottom = Container()
    private val footer = Footer(
        FooterInfo(
            cwd = options.workDir,
            sessionId = "new",
            usedTokens = 0,
            contextRatio = 0.0,
            status = FooterStatus.IDLE,
            supervisionLabel = supervisionLabel,
        ),
    )
    private val editor = Editor(
        placeholder = "type a message, /command, or press Esc to cancel",
    )
    private val inputRouter = InputRouter()

    // ── Dynamic zone slots ─────────────────────────────────────────────────
    /** The streaming assistant message component for the current turn, if any. */
    private var streaming: StreamingMessage? = null
    /** The approval overlay, if any. */
    private var approvalOverlay: ApprovalOverlay? = null
    /** Optional multi-line notice (e.g. /hotkeys, /session output). Cleared on next submit. */
    private var notice: List<String>? = null
    /** Single-line error message. Cleared on next submit. */
    private var errorMessage: String? = null
    /** Whether the agent is currently busy running a prompt. */
    private var busy: Boolean = false
    /** Whether the user has pressed the exit-confirm key once. Auto-clears after ~1s. */
    private var confirmExit: Boolean = false
    private var confirmExitClearJob: Job? = null

    // ── Shadow state for footer/token usage ────────────────────────────────
    /** Shadow mirror of Agent.state.messages. Used only to compute token totals. */
    private val allMessages: MutableList<AgentMessage> = mutableListOf()

    // ── Background jobs ────────────────────────────────────────────────────
    private var eventsJob: Job? = null
    private var permissionJob: Job? = null
    private var terminalEventsJob: Job? = null

    // Slash commands are rebuilt once we know the scope/agent — deferred to start().
    private var slashCommands: SlashCommandRegistry? = null

    /**
     * Bring up the TUI. Wires the editor submit, key router, and event flows;
     * adds the container tree to [TUI]; and calls [TUI.start] which draws the
     * first frame and begins the event loop.
     */
    fun start(tty: Tty, terminal: Terminal, scope: CoroutineScope) {
        this.scope = scope

        // 1. Build the TUI.
        val tui = TUI(
            terminal = terminal,
            output = TtyOutput(tty),
            scope = scope,
        )
        this.tui = tui

        // 2. Build the bottom chrome tree. Order matters: notice → error →
        //    editor → footer. We re-materialize this on every state change so
        //    ephemeral rows come and go cleanly.
        rebuildBottomChrome()

        // 3. Compose the root tree.
        tui.addChild(headerBanner)
        tui.addChild(history)
        tui.addChild(bottom)
        // Streaming / overlay are appended to tui directly when active so their
        // placement in the z-order is predictable (overlay last = on top).

        // 4. Seed history from any resumed-session messages.
        for (msg in agent.state.messages) {
            appendHistoryMessage(msg)
            allMessages += msg
        }

        // 5. Build slash command registry now that scope/agent are bound.
        slashCommands = SlashCommandRegistry.builtIn(
            onNewSession = {
                setNotice(
                    listOf(
                        "/new: not available from inside a running session.",
                        "Exit (Esc Esc / Ctrl+C) and relaunch `fraggle code` for a new session.",
                    ),
                )
            },
            onQuit = { triggerExit() },
            onHotkeys = { setNotice(HOTKEYS_HELP) },
            onSessionInfo = {
                setNotice(
                    listOf(
                        "session id: ${agent.sessionId}",
                        "file:       ${agent.sessionFile}",
                    ),
                )
            },
        )

        // 6. Wire editor submission + autocomplete. Two providers compose:
        //    - `@path` opens a file picker rooted at [options.workDir]. On
        //      submit, [AtFileExpander] inlines the referenced file contents
        //      into the message before it reaches the agent.
        //    - `/command` opens a slash-command picker showing built-in
        //      commands (`/hotkeys`, `/quit`, …) and installed skills
        //      (`/skill:<name>`). The skill list is fetched live from
        //      [skillCompletionsProvider] so freshly-installed skills appear
        //      immediately without a restart.
        editor.setOnSubmit { text -> onEditorSubmit(text) }
        val slashProvider = SlashCommandAutocompleteProvider(
            completionsProvider = ::buildSlashCompletions,
        )
        editor.setAutocompleteProvider(
            CompositeAutocompleteProvider(
                FileAutocompleteProvider(root = options.workDir),
                slashProvider,
            ),
        )

        // 7. Focus the router (which wraps the editor + global key handling).
        tui.setFocus(inputRouter)

        // 8. Subscribe to agent events (streaming + completed messages).
        eventsJob = scope.launch {
            agent.events().collect { event -> handleAgentEvent(event) }
        }

        // 9. Subscribe to pending approval state.
        if (permissionHandler != null) {
            permissionJob = scope.launch {
                permissionHandler.pending.collectLatest { pending ->
                    updateApprovalOverlay(pending)
                }
            }
        }

        // 10. Bracketed-paste: fraggle-tui's TUI event loop only forwards
        //     KeyboardEvent to focused components. BracketedPasteEvent falls
        //     on the floor, so [Editor.setPasteActive] is never called. Paste
        //     still works via the flurry-of-printable-keys fallback path the
        //     editor documents; a future runtime enhancement can wire paste
        //     events through for paste-aware Enter handling.

        // 11. Start the runtime. Draws first frame + begins event loop.
        tui.start()
    }

    /** Tear down: cancel jobs, stop the TUI (restores terminal state). Idempotent. */
    fun stop() {
        eventsJob?.cancel()
        permissionJob?.cancel()
        terminalEventsJob?.cancel()
        confirmExitClearJob?.cancel()
        eventsJob = null
        permissionJob = null
        terminalEventsJob = null
        confirmExitClearJob = null
        tui?.stop()
        tui = null
    }

    // ── Event handling ──────────────────────────────────────────────────────

    /**
     * Project a single [AgentEvent] onto the TUI tree. Pure state transitions
     * extracted into [applyEvent] for easier testing.
     */
    internal fun handleAgentEvent(event: AgentEvent) {
        applyEvent(event)
        requestRender()
    }

    /**
     * Testable pure transition: mutate local state + the visible tree in
     * response to [event]. No render side-effects here — the caller is
     * expected to call [requestRender] after.
     */
    internal fun applyEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageStart -> {
                val msg = event.message
                if (msg is AgentMessage.Assistant) {
                    val sm = StreamingMessage(msg.textContent)
                    streaming = sm
                    mountStreaming(sm)
                }
            }
            is AgentEvent.MessageUpdate -> {
                streaming?.setText(event.message.textContent)
            }
            is AgentEvent.MessageEnd -> {
                // Flush the completed assistant turn to history, remove streaming.
                unmountStreaming()
                streaming = null
                val msg = event.message
                if (msg is AgentMessage.Assistant) {
                    appendHistoryMessage(msg)
                    allMessages += msg
                    if (msg.errorMessage != null) {
                        errorMessage = msg.errorMessage
                    }
                }
            }
            is AgentEvent.MessageRecord -> {
                appendHistoryMessage(event.message)
                allMessages += event.message
            }
            is AgentEvent.TurnEnd -> {
                val msg = event.message
                if (msg is AgentMessage.Assistant && msg.errorMessage != null) {
                    errorMessage = msg.errorMessage
                }
            }
            else -> Unit
        }
        // Refresh chrome for error/notice rows + footer.
        refreshFooter()
        rebuildBottomChrome()
    }

    /** Submission path from the editor. Handles slash commands, skills, and plain prompts. */
    internal fun onEditorSubmit(text: String) {
        val trimmed = text
        if (trimmed.isBlank()) return

        // 1. Skill expansion. /skill:name [args] flows through the agent as
        //    an inlined block; not a registered slash command.
        val expander = skillExpander
        if (expander != null) {
            when (val r = expander.tryExpand(trimmed)) {
                is SkillCommandExpander.Result.Expanded -> {
                    clearEphemeral()
                    setNotice(listOf("activated skill: ${r.skill.name}"))
                    sendToAgent(r.text)
                    return
                }
                is SkillCommandExpander.Result.UnknownSkill -> {
                    errorMessage = "unknown skill: ${r.name}"
                    notice = null
                    rebuildBottomChrome()
                    requestRender()
                    return
                }
                is SkillCommandExpander.Result.MalformedCommand -> {
                    errorMessage = r.reason
                    notice = null
                    rebuildBottomChrome()
                    requestRender()
                    return
                }
                is SkillCommandExpander.Result.ReadError -> {
                    errorMessage = "failed to read skill ${r.name}: ${r.reason}"
                    notice = null
                    rebuildBottomChrome()
                    requestRender()
                    return
                }
                SkillCommandExpander.Result.NotASkillCommand -> Unit
            }
        }

        // 2. Slash command.
        val registry = slashCommands
        if (registry != null) {
            when (val parsed = registry.parse(trimmed)) {
                is SlashCommandParse.Matched -> {
                    errorMessage = null
                    confirmExit = false
                    parsed.command.handler(parsed.args)
                    rebuildBottomChrome()
                    requestRender()
                    return
                }
                is SlashCommandParse.Unknown -> {
                    errorMessage = "unknown slash command: /${parsed.name}"
                    notice = null
                    confirmExit = false
                    rebuildBottomChrome()
                    requestRender()
                    return
                }
                null -> Unit
            }
        }

        // 3. Expand any `@path` file references into inlined content before
        //    the agent sees the message. Local LLMs don't interpret `@path`
        //    natively the way cloud assistants do, so we inline content
        //    client-side. Unresolved references are left as plain text and
        //    surfaced as a single-line warning.
        val expansion = AtFileExpander.expand(trimmed, options.workDir)
        clearEphemeral()
        if (expansion.unresolved.isNotEmpty()) {
            errorMessage = "couldn't resolve: ${expansion.unresolved.joinToString(", ") { "@$it" }}"
            rebuildBottomChrome()
        }
        sendToAgent(expansion.expandedText)
    }

    private fun sendToAgent(text: String) {
        val scope = scope ?: return
        scope.launch {
            busy = true
            refreshFooter()
            rebuildBottomChrome()
            requestRender()
            try {
                agent.prompt(text)
            } catch (_: CancellationException) {
                // Abort — swallow; partial session already persisted by CodingAgent.prompt.
            } catch (e: Exception) {
                errorMessage = e.message ?: e::class.simpleName ?: "error"
            } finally {
                busy = false
                refreshFooter()
                rebuildBottomChrome()
                requestRender()
            }
        }
    }

    /** Clear ephemeral UI (notice, error, confirm-exit) before the next prompt. */
    private fun clearEphemeral() {
        notice = null
        errorMessage = null
        confirmExit = false
    }

    /** Drive an exit confirmation: shows the warning, clears it after ~1s. */
    private fun armExitConfirm() {
        confirmExit = true
        val scope = scope ?: return
        confirmExitClearJob?.cancel()
        confirmExitClearJob = scope.launch {
            delay(1000)
            confirmExit = false
            refreshFooter()
            rebuildBottomChrome()
            requestRender()
        }
        refreshFooter()
        rebuildBottomChrome()
        requestRender()
    }

    private fun triggerExit() {
        onExit()
    }

    /**
     * Collect every valid `/…` completion — built-in commands from the
     * active [SlashCommandRegistry] plus whatever [skillCompletionsProvider]
     * returns right now. Called on each keystroke while the `/` popup is
     * open; both sources are cheap to enumerate so there's no caching here.
     */
    private fun buildSlashCompletions(): List<Autocompletion> {
        val out = ArrayList<Autocompletion>()
        slashCommands?.commands?.forEach { cmd ->
            out += Autocompletion(
                label = cmd.name,
                replacement = cmd.name,
                description = cmd.description,
                trailingSpace = false,
                continueCompletion = false,
            )
        }
        out += skillCompletionsProvider()
        return out
    }

    // ── Tree mutations ──────────────────────────────────────────────────────

    private fun appendHistoryMessage(msg: AgentMessage) {
        val component = when (msg) {
            is AgentMessage.User -> {
                val text = msg.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                // Skill invocations are inlined as <skill name="..."> blocks so the LLM
                // sees the full SKILL.md body. Hide that wall of text from the TUI — the
                // notice row ("activated skill: <name>") already tells the user it fired.
                if (text.trimStart().startsWith("<skill name=\"")) return
                UserMessage(text)
            }
            is AgentMessage.Assistant -> AssistantMessage(
                text = msg.textContent,
                toolCalls = msg.toolCalls.map { call ->
                    AssistantMessage.ToolCallSnippet(name = call.name, argsJson = call.arguments)
                },
                errorMessage = msg.errorMessage,
            )
            is AgentMessage.ToolResult -> ToolExecution(
                toolName = msg.toolName,
                isError = msg.isError,
                output = msg.textContent,
            )
            is AgentMessage.Platform -> return
        }
        history.addChild(component)
    }

    /** Add the [streaming] message after [history] (so it sits above the bottom chrome). */
    private fun mountStreaming(sm: StreamingMessage) {
        val tui = tui ?: return
        // Insert streaming between history and bottom: remove bottom, add streaming, re-add bottom + overlay.
        // fraggle-tui Container only supports add/remove/clear at the end — so we rebuild.
        rebuildRoot(withStreaming = sm, withOverlay = approvalOverlay)
    }

    private fun unmountStreaming() {
        rebuildRoot(withStreaming = null, withOverlay = approvalOverlay)
    }

    /**
     * Rebuild the TUI root's child list in the canonical order:
     * header, history, streaming (optional), bottom, overlay (optional).
     * Called whenever ordering changes (streaming toggled, overlay toggled).
     */
    private fun rebuildRoot(withStreaming: StreamingMessage?, withOverlay: ApprovalOverlay?) {
        val tui = tui ?: return
        tui.clear()
        tui.addChild(headerBanner)
        tui.addChild(history)
        if (withStreaming != null) tui.addChild(withStreaming)
        tui.addChild(bottom)
        if (withOverlay != null) tui.addChild(withOverlay)
    }

    /** Rebuild just the bottom chrome (notice/error/editor/footer). */
    private fun rebuildBottomChrome() {
        bottom.clear()
        notice?.let { lines ->
            for (line in lines) {
                bottom.addChild(NoticeLine(line))
            }
        }
        errorMessage?.let { msg ->
            bottom.addChild(ErrorLine(msg))
        }
        bottom.addChild(editor)
        bottom.addChild(footer)
    }

    private fun setNotice(lines: List<String>) {
        notice = lines
        errorMessage = null
        rebuildBottomChrome()
        requestRender()
    }

    private fun updateApprovalOverlay(pending: PendingPermission?) {
        if (pending == null) {
            // Remove.
            approvalOverlay = null
            rebuildRoot(withStreaming = streaming, withOverlay = null)
            // Editor regains focus.
            tui?.setFocus(inputRouter)
            refreshFooter()
            rebuildBottomChrome()
            requestRender()
            return
        }
        val overlay = ApprovalOverlay(
            approval = PendingApproval(
                toolName = pending.toolName,
                argsSummary = compactArgs(pending.argsJson),
            ),
            onApprove = { permissionHandler?.approve() },
            onDeny = { permissionHandler?.deny() },
        )
        approvalOverlay = overlay
        rebuildRoot(withStreaming = streaming, withOverlay = overlay)
        // Overlay takes focus while it's visible.
        tui?.setFocus(overlay)
        refreshFooter()
        rebuildBottomChrome()
        requestRender()
    }

    private fun compactArgs(json: String): String =
        json.replace(Regex("\\s+"), " ").trim()

    private fun refreshFooter() {
        val usage = ContextUsage.fromMessages(allMessages.toList(), options.contextWindowTokens)
        val status = when {
            errorMessage != null -> FooterStatus.ERROR
            approvalOverlay != null -> FooterStatus.AWAITING_APPROVAL
            busy -> FooterStatus.BUSY
            else -> FooterStatus.IDLE
        }
        footer.setInfo(
            FooterInfo(
                cwd = options.workDir,
                sessionId = if (allMessages.isNotEmpty()) "active" else "new",
                usedTokens = usage.usedTokens,
                contextRatio = usage.ratio,
                status = status,
                supervisionLabel = supervisionLabel,
                confirmExit = confirmExit,
            ),
        )
    }

    private fun requestRender() {
        tui?.requestRender()
    }

    // ── Input router ────────────────────────────────────────────────────────

    /**
     * The focused component in normal operation. Delegates printable/editing
     * keys to the [editor], intercepts Ctrl+C / Esc for abort + exit
     * confirmation, and routes to the [approvalOverlay] when one is active
     * (though the overlay also receives focus directly when mounted).
     *
     * Modeled as a Focusable wrapper because the TUI runtime dispatches
     * keyboard events strictly to its single focused component — there's no
     * fall-through API for global hotkeys.
     */
    private inner class InputRouter : Component, Focusable {
        override var focused: Boolean = false

        override fun render(width: Int): List<String> {
            // Router is invisible — only the editor (below) contributes rows.
            return emptyList()
        }

        override fun handleInput(key: KeyboardEvent): Boolean {
            // Approval overlay should get focus directly; if we get here while
            // one is active, still route to it as a safety net.
            val overlay = approvalOverlay
            if (overlay != null) {
                return overlay.handleInput(key)
            }

            // Global keys: Ctrl+C and Esc.
            //  - busy       → abort the running turn
            //  - non-empty  → Ctrl+C clears the editor buffer; Esc falls to confirm
            //  - confirmed  → exit
            //  - otherwise  → arm exit confirmation
            if (key.matches('c', ctrl = true)) {
                when {
                    busy -> {
                        agent.abort()
                        return true
                    }
                    !editor.isEmpty() -> {
                        editor.clear()
                        return true
                    }
                    confirmExit -> {
                        triggerExit()
                        return true
                    }
                    else -> {
                        armExitConfirm()
                        return true
                    }
                }
            }
            if (key.matches(27)) { // Esc
                when {
                    busy -> {
                        agent.abort()
                        return true
                    }
                    confirmExit -> {
                        triggerExit()
                        return true
                    }
                    else -> {
                        armExitConfirm()
                        return true
                    }
                }
            }

            // Any other key cancels a pending exit confirm before being
            // delivered to the editor.
            if (confirmExit) {
                confirmExit = false
                confirmExitClearJob?.cancel()
                refreshFooter()
                rebuildBottomChrome()
            }

            // While busy, eat all keys except the globals above. Prevents the
            // user from typing into a locked editor.
            if (busy) return true

            // Clear ephemeral error on any keystroke that would be forwarded.
            if (errorMessage != null) {
                errorMessage = null
                rebuildBottomChrome()
            }

            // Delegate to the editor.
            return editor.handleInput(key)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Helpers used by the bottom-chrome container.
// ────────────────────────────────────────────────────────────────────────────

/**
 * Single-line notice row. Renders "  {message}" colored in the theme's accent
 * color, padded to the viewport width.
 */
internal class NoticeLine(private val text: String) : Component {
    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme
        val leading = "  "
        val leadWidth = visibleWidth(leading)
        val contentRoom = (width - leadWidth).coerceAtLeast(0)
        if (contentRoom == 0) return listOf(padRightToWidth("", width))
        val wrapped = if (visibleWidth(text) <= contentRoom) {
            listOf(text)
        } else {
            // wordWrap keeps words intact; each line fits the room budget.
            wordWrap(text, contentRoom)
        }
        return wrapped.map { line ->
            padRightToWidth("$leading${theme.accent}$line${Ansi.RESET}", width)
        }
    }
}

/**
 * Single-line error row. Renders "  ! error: {message}" in the theme's error
 * color. Wraps long messages to keep every line inside the width contract.
 */
internal class ErrorLine(private val message: String) : Component {
    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme
        val lead = "  "
        val marker = "! error: "
        val firstLead = "$lead$marker"
        val contLead = " ".repeat(visibleWidth(firstLead))

        val flat = message.replace('\n', ' ')
        val contentRoom = (width - visibleWidth(firstLead)).coerceAtLeast(0)
        if (contentRoom == 0) return listOf(padRightToWidth("", width))

        val wrapped = if (visibleWidth(flat) <= contentRoom) {
            listOf(flat)
        } else {
            wordWrap(flat, contentRoom)
        }

        return wrapped.mapIndexed { index, line ->
            val prefix = if (index == 0) firstLead else contLead
            val styled = "${theme.error}$prefix${Ansi.RESET}${theme.error}$line${Ansi.RESET}"
            val final = if (visibleWidth(styled) > width) truncateToWidth(styled, width) else styled
            padRightToWidth(final, width)
        }
    }
}

/** Static hotkeys help content shown by `/hotkeys`. */
internal val HOTKEYS_HELP: List<String> = listOf(
    "keys:",
    "  Enter         send message / run slash command",
    "  Shift+Enter   newline in editor",
    "  Esc           abort running turn or confirm exit",
    "  Ctrl+C        abort running turn, clear editor, deny pending tool call, or confirm exit",
    "  ←/→/↑/↓       move cursor   •   Home/End   line start/end",
    "  Backspace/Delete  edit buffer",
    "  Y / N         approve / deny pending tool call",
    "",
    "slash commands:  /hotkeys  /session  /new  /quit  /skill:<name>",
)
