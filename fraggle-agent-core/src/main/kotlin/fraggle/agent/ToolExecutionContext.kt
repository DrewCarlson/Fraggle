package fraggle.agent

import kotlinx.coroutines.asContextElement
import fraggle.agent.ToolExecutionContext.Companion.asContextElement
import fraggle.agent.ToolExecutionContext.Companion.current
import java.util.*
import kotlin.coroutines.CoroutineContext

/**
 * Per-request context for tool execution, providing chat context and attachment collection.
 *
 * Propagated via coroutine context using [asContextElement]. Tools access the current
 * context via [current] to read chatId/userId or collect attachments (e.g., screenshots).
 */
class ToolExecutionContext(
    val chatId: String,
    val userId: String? = null,
    /**
     * Skill name to use as the default for skill-aware tools (e.g. `execute_command`)
     * when the tool call does not specify one. Set, for example, by the task scheduler
     * so a scheduled task's shell commands inherit the task's configured skill
     * environment without the model having to pass `skill` on every call.
     */
    val defaultSkill: String? = null,
) {
    /**
     * Thread-safe list of attachments collected during tool execution.
     * Tools like screenshot_page add images here; the agent collects them after completion.
     */
    val attachments: MutableList<ResponseAttachment> = Collections.synchronizedList(mutableListOf())

    companion object {
        private val threadLocal = ThreadLocal<ToolExecutionContext?>()

        /**
         * Get the current execution context, or null if not in a tool execution.
         */
        fun current(): ToolExecutionContext? = threadLocal.get()

        /**
         * Create a coroutine context element that propagates this context to child coroutines.
         */
        fun asContextElement(context: ToolExecutionContext): CoroutineContext.Element =
            threadLocal.asContextElement(context)
    }
}
