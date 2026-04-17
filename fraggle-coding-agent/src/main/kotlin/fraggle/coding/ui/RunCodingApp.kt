package fraggle.coding.ui

import com.jakewharton.mosaic.tty.Tty
import com.jakewharton.mosaic.tty.terminal.asTerminalIn
import fraggle.agent.skill.SkillCommandExpander
import fraggle.coding.CodingAgent
import fraggle.coding.CodingAgentOptions
import fraggle.tui.ui.Autocompletion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Blocking entry point for the fraggle-tui-backed coding-agent TUI.
 *
 * Replaces [fraggle.coding.tui.runCodingApp]. The public signature matches
 * verbatim so [fraggle.CodeCommand] (once switched over in Wave 4) can call
 * it as a drop-in swap.
 *
 * Flow:
 *  1. Bind a [Tty] via [Tty.tryBind]. Error out if no TTY is present.
 *  2. Build a [CoroutineScope] with [SupervisorJob] so stray failures in the
 *     render loop or event pump don't cancel sibling work.
 *  3. `Tty.asTerminalIn(scope)` is suspending; wrap it in [runBlocking] so
 *     this function stays blocking (matches the old Compose-based signature).
 *  4. Construct [CodingApp] + call [CodingApp.start].
 *  5. Block on a [CompletableDeferred] until either the exit callback fires
 *     or a top-level exception propagates out.
 *  6. Finally: [CodingApp.stop], cancel the scope, close the Tty.
 *
 * Matches the blocking semantics of the old [fraggle.coding.tui.runCodingApp]
 * but routes everything through the new fraggle-tui runtime.
 */
fun runCodingApp(
    agent: CodingAgent,
    options: CodingAgentOptions,
    header: HeaderInfo,
    supervisionLabel: String,
    skillExpander: SkillCommandExpander? = null,
    /**
     * Live source for `/skill:<name>` completion entries. Invoked each time
     * the user opens the `/` autocomplete popup — re-reads disk so
     * mid-session skill installs appear immediately.
     */
    skillCompletionsProvider: () -> List<Autocompletion> = { emptyList() },
    onExitRequest: () -> Unit,
    permissionHandler: TuiToolPermissionHandler? = null,
) {
    val tty = Tty.tryBind() ?: error("no TTY available for coding agent TUI")
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Deferred is completed from the exit callback (or a top-level error) to
    // unblock the outer runBlocking.
    val exitSignal = CompletableDeferred<Unit>()

    var app: CodingApp? = null
    try {
        runBlocking {
            // Suspending: hand the Tty to Mosaic to turn it into a Terminal
            // with its own event pump. Must be called from a coroutine scope.
            val terminal = tty.asTerminalIn(scope)

            app = CodingApp(
                agent = agent,
                options = options,
                header = header,
                supervisionLabel = supervisionLabel,
                skillExpander = skillExpander,
                permissionHandler = permissionHandler,
                skillCompletionsProvider = skillCompletionsProvider,
                onExit = {
                    onExitRequest()
                    exitSignal.complete(Unit)
                },
            ).also { it.start(tty, terminal, scope) }

            // Park the main thread until someone (the exit callback, typically)
            // completes the deferred. This blocks runBlocking without spinning.
            exitSignal.await()
        }
    } finally {
        runCatching { app?.stop() }
        runCatching { scope.cancel() }
        runCatching { tty.close() }
    }
}
