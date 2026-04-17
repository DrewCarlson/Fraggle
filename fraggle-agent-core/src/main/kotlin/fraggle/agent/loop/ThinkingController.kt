package fraggle.agent.loop

import fraggle.provider.ThinkingLevel

/**
 * Shared mutable holder for the current session's reasoning-level override.
 *
 * The UI layer (slash-command handlers) writes to [level]; the LLM bridge
 * reads it on every request. A single instance is constructed per session
 * and handed to both sides — that way `/think high` affects the next
 * provider call without plumbing the bridge instance out to the UI.
 *
 * `null` means "no override — let the provider pick its default for the
 * current model". The controller is transient; nothing persists it across
 * process restarts.
 *
 * Thread-safety: [level] is `@Volatile` so writes from the slash-command
 * thread are immediately visible to the agent-loop thread.
 */
class ThinkingController(initial: ThinkingLevel? = null) {
    @Volatile
    var level: ThinkingLevel? = initial
}
