package org.drewcarlson.fraggle.agent

import kotlinx.coroutines.asContextElement
import org.drewcarlson.fraggle.agent.ToolExecutionContext.Companion.asContextElement
import org.drewcarlson.fraggle.agent.ToolExecutionContext.Companion.current
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
