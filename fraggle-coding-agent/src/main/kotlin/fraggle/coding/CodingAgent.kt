package fraggle.coding

import fraggle.agent.Agent
import fraggle.agent.compaction.CompactionResult
import fraggle.agent.compaction.ContextUsage
import fraggle.agent.compaction.LlmCompactor
import fraggle.agent.event.AgentEvent
import fraggle.agent.loop.AgentOptions
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.coding.session.Session
import fraggle.coding.session.SessionEntry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Orchestrator for a single coding-agent conversation.
 *
 * Sits on top of [Agent] from `fraggle-agent-core` and ties together:
 *  - **Session persistence** — every user, assistant, and tool-result message
 *    is appended to a [Session] as it happens, producing a JSONL timeline the
 *    user can resume, fork, or browse with `/tree`.
 *  - **Compaction** — before each `prompt()` call the orchestrator consults
 *    an [LlmCompactor] built from the options. If compaction fires, the
 *    agent's in-memory state is replaced with the kept-verbatim tail and a
 *    synthetic summary message; the session file keeps the full (uncompacted)
 *    history so `/tree` navigation and resume can still reach every turn.
 *  - **Event subscription** — the underlying [Agent]'s lifecycle events are
 *    re-exposed via [subscribe], so a TUI (or a test fixture) can forward
 *    them to the UI without knowing how the orchestrator is wired.
 *
 * The orchestrator owns the [Agent] instance for its lifetime. Multiple
 * `prompt()` calls reuse the same agent (with replaced state as needed); a
 * fresh orchestrator is constructed for each session resume or fork.
 */
class CodingAgent(private val options: CodingAgentOptions, private val session: Session) {
    private val logger = LoggerFactory.getLogger(CodingAgent::class.java)

    private val compactor: LlmCompactor = LlmCompactor(
        llmBridge = options.llmBridge,
        policy = options.compactionPolicy,
        keepRecentMessages = options.compactionKeepRecentMessages,
    )

    private val agent: Agent = Agent(
        AgentOptions(
            systemPrompt = options.systemPrompt,
            model = options.model,
            llmBridge = options.llmBridge,
            toolExecutor = options.toolCallExecutor,
            maxIterations = options.maxIterations,
            chatId = session.id,
            initialMessages = options.initialMessages,
        ),
    )

    /**
     * Tracks the size of [Agent.state.messages] at the start of the current
     * `prompt()` call so we can figure out which messages to persist as new
     * session entries after it returns.
     */
    private var baselineMessageCount: Int = options.initialMessages.size

    /**
     * Running summary text. Populated when compaction fires; fed back in as
     * `previousSummary` on the next round so successive compactions build on
     * each other rather than forgetting prior context.
     */
    private var currentSummary: String? = null

    /**
     * `parentId` to use for the next persisted session entry. Updated after
     * every successful write so the new entry chains off the last one on the
     * live branch.
     */
    private var tipId: String? = session.tree.entries.lastOrNull()?.id

    /** Subscribe to lifecycle events from the underlying [Agent]. */
    fun subscribe(listener: suspend (AgentEvent) -> Unit): () -> Unit = agent.subscribe(listener)

    /** Read-only view of the current in-memory agent state. */
    val state get() = agent.state

    /**
     * Send a user message and run the agent loop to completion.
     *
     * Flow:
     * 1. Record the [text] as a session `User` entry before running anything.
     *    Crash-safety: if the prompt errors mid-turn, the user's input still
     *    survives in the session file so the next resume can see what was
     *    asked.
     * 2. Attempt compaction. If [CompactionResult.Compacted], replace the
     *    agent's in-memory state with the kept tail + a synthetic summary
     *    message, and record a session `Meta` entry with the summary. The
     *    full pre-compaction history stays in the session file for `/tree`.
     * 3. Set [baselineMessageCount] to `state.messages.size + 1`, reserving
     *    a slot for the user message the agent is about to add. Anything
     *    past that is genuinely new (assistant turns, tool results, and any
     *    steering/follow-up injections) and needs persisting.
     * 4. Call `agent.prompt(text)`, then [waitForIdle], then persist the new tail.
     */
    suspend fun prompt(text: String, attachments: List<String> = emptyList()) {
        recordUserEntry(text, attachments)
        maybeCompact()
        // +1 reserves the slot for the User message that agent.prompt(text)
        // will push onto the state via the MessageEnd event path. Walking past
        // this index means we skip the duplicate of the user we already recorded.
        baselineMessageCount = agent.state.messages.size + 1
        try {
            agent.prompt(text)
            agent.waitForIdle()
        } finally {
            // Persist whatever new messages made it into agent.state even if
            // the run was cancelled (user pressed escape) or errored mid-turn.
            // Re-thrown CancellationException still propagates after this.
            persistNewMessages()
        }
    }

    /** Steer a currently-running prompt with a follow-up message. Non-blocking. */
    fun steer(text: String) {
        agent.steer(AgentMessage.User(text))
    }

    /** Queue a message to be delivered after the current run finishes all work. */
    fun queueFollowUp(text: String) {
        agent.followUp(AgentMessage.User(text))
    }

    /** Cancel the currently-running prompt if any. Safe to call when idle. */
    fun abort() {
        agent.abort()
    }

    /** Suspend until any in-flight prompt completes. */
    suspend fun waitForIdle() = agent.waitForIdle()

    // ───────────────────────── internals ─────────────────────────

    private fun recordUserEntry(text: String, attachments: List<String>) {
        val entry = SessionEntry(
            id = newId(),
            parentId = tipId,
            timestampMs = System.currentTimeMillis(),
            payload = SessionEntry.Payload.User(text = text, attachments = attachments),
        )
        tipId = session.record(entry)
    }

    private fun recordMetaEntry(label: String?, summary: String?) {
        val entry = SessionEntry(
            id = newId(),
            parentId = tipId,
            timestampMs = System.currentTimeMillis(),
            payload = SessionEntry.Payload.Meta(label = label, summary = summary),
        )
        tipId = session.record(entry)
    }

    private fun recordAssistantEntry(message: AgentMessage.Assistant) {
        val entry = SessionEntry(
            id = newId(),
            parentId = tipId,
            timestampMs = System.currentTimeMillis(),
            payload = SessionEntry.Payload.Assistant(
                text = message.textContent,
                toolCalls = message.toolCalls.map { call ->
                    SessionEntry.ToolCallSnapshot(
                        id = call.id,
                        name = call.name,
                        argsJson = call.arguments,
                    )
                },
                stopReason = message.stopReason.name,
                errorMessage = message.errorMessage,
                usage = message.usage?.let { u ->
                    SessionEntry.UsageSnapshot(
                        inputTokens = u.promptTokens,
                        outputTokens = u.completionTokens,
                        totalTokens = u.totalTokens,
                    )
                },
            ),
        )
        tipId = session.record(entry)
    }

    private fun recordToolResultEntry(result: AgentMessage.ToolResult) {
        val entry = SessionEntry(
            id = newId(),
            parentId = tipId,
            timestampMs = System.currentTimeMillis(),
            payload = SessionEntry.Payload.ToolResult(
                callId = result.toolCallId,
                toolName = result.toolName,
                output = result.textContent,
                error = if (result.isError) result.textContent else null,
            ),
        )
        tipId = session.record(entry)
    }

    /**
     * Walk [Agent.state.messages] starting at [baselineMessageCount] and
     * persist each new entry. The first index was pre-reserved for the user
     * message we already recorded, so anything here is genuinely new:
     * assistant turns, tool results, or later User messages produced by
     * steering/follow-up injections.
     */
    private fun persistNewMessages() {
        val all = agent.state.messages
        if (all.size <= baselineMessageCount) return
        for (msg in all.subList(baselineMessageCount, all.size)) {
            when (msg) {
                is AgentMessage.User -> {
                    val text = msg.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                    val entry = SessionEntry(
                        id = newId(),
                        parentId = tipId,
                        timestampMs = System.currentTimeMillis(),
                        payload = SessionEntry.Payload.User(text = text),
                    )
                    tipId = session.record(entry)
                }
                is AgentMessage.Assistant -> recordAssistantEntry(msg)
                is AgentMessage.ToolResult -> recordToolResultEntry(msg)
                is AgentMessage.Platform -> {
                    // Platform messages are assistant-side metadata; coding agent has none.
                }
            }
        }
    }

    /**
     * Check the compaction policy against the current in-memory state and,
     * if it fires, replace the agent's history with the kept tail + a
     * synthetic summary message. The original messages stay in the session
     * file for `/tree` browsing — compaction only affects the live working set.
     */
    private suspend fun maybeCompact() {
        val messages = agent.state.messages
        val usage = ContextUsage.fromMessages(messages, maxTokens = options.contextWindowTokens)
        val result = compactor.compact(
            messages = messages,
            usage = usage,
            previousSummary = currentSummary,
        )
        when (result) {
            CompactionResult.NotNeeded -> Unit
            is CompactionResult.Failed -> {
                logger.warn("Compaction failed, keeping uncompacted state: ${result.reason}")
            }
            is CompactionResult.Compacted -> {
                logger.info(
                    "Compaction fired: elided {} messages, kept {} recent, summary {} chars",
                    result.compactedCount, result.recentMessages.size, result.summary.length,
                )
                currentSummary = result.summary
                // Splice a synthetic user message carrying the summary in front
                // of the kept tail. The agent loop treats it as regular history;
                // the LLM sees "Earlier in this session: <summary>" then the
                // recent turns, with no gap.
                val syntheticSummary = AgentMessage.User(
                    text = "Earlier in this session (compacted summary):\n\n${result.summary}",
                )
                agent.replaceMessages(listOf(syntheticSummary) + result.recentMessages)
                // Record the summary as a session Meta entry so a future /tree
                // browse can see where compaction happened and what it produced.
                recordMetaEntry(label = "compaction", summary = result.summary)
            }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

/**
 * The stop reason Fraggle's core enum exposes, re-exported here for tests
 * and downstream consumers that would otherwise need to transitively depend
 * on `fraggle.agent.message`.
 */
typealias CodingStopReason = StopReason
