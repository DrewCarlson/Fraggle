package fraggle.coding.ui

import fraggle.executor.supervision.ToolPermissionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [ToolPermissionHandler] that surfaces pending tool calls as a [StateFlow] so
 * the TUI can render an approval overlay.
 *
 * When the agent loop calls [requestPermission], the handler stashes the
 * request in [pending] (an immutable state flow the TUI collects) and
 * suspends on a [CompletableDeferred]. When the user answers — by pressing Y
 * or N in the overlay — the TUI calls [approve] or [deny] to complete the
 * deferred, which unsuspends the waiting loop and returns the answer.
 *
 * State flow + deferred separation is necessary because the agent loop runs
 * in its own coroutine: state has to flow from the agent → handler → TUI via
 * [pending], and user intent has to flow back TUI → handler → agent via
 * [approve]/[deny].
 *
 * Only one permission can be pending at a time. If [requestPermission] is
 * called while another is already in flight, the new one blocks until the
 * previous one resolves — tool calls in a single turn are serialized at the
 * supervisor layer anyway, so this is a safety net, not a performance issue.
 */
class TuiToolPermissionHandler : ToolPermissionHandler {

    /**
     * The currently-pending approval request, or `null` if nothing is
     * waiting on the user. The TUI observes this and renders an overlay
     * whenever it transitions to non-null.
     */
    private val _pending = MutableStateFlow<PendingPermission?>(null)
    val pending: StateFlow<PendingPermission?> = _pending.asStateFlow()

    /**
     * Synchronization lock: only one permission in flight at a time. A second
     * [requestPermission] while one is still pending waits for the first to
     * resolve.
     */
    private val serialize = Mutex()

    override suspend fun requestPermission(
        requestId: String,
        toolName: String,
        argsJson: String,
        chatId: String,
    ): Boolean = serialize.withLock {
        val deferred = CompletableDeferred<Boolean>()
        val approval = PendingPermission(
            requestId = requestId,
            toolName = toolName,
            argsJson = argsJson,
            chatId = chatId,
            result = deferred,
        )
        _pending.value = approval
        try {
            deferred.await()
        } finally {
            // Clear only if the flow still points at this approval — if
            // another request has somehow replaced us, leave it alone.
            _pending.compareAndSet(approval, null)
        }
    }

    /** Resolve the currently-pending request as approved. No-op if nothing pending. */
    fun approve() {
        _pending.value?.result?.complete(true)
    }

    /** Resolve the currently-pending request as denied. No-op if nothing pending. */
    fun deny() {
        _pending.value?.result?.complete(false)
    }
}

/**
 * A single in-flight permission request. Exposed to the TUI via
 * [TuiToolPermissionHandler.pending]. The runtime converts this into a
 * display-layer [PendingApproval] (from [ApprovalOverlay]) when rendering.
 */
data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val argsJson: String,
    val chatId: String,
    internal val result: CompletableDeferred<Boolean>,
)
