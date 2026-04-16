package fraggle.coding

import fraggle.agent.compaction.CompactionPolicy
import fraggle.agent.compaction.RatioCompactionPolicy
import fraggle.agent.loop.LlmBridge
import fraggle.agent.loop.ToolCallExecutor
import java.nio.file.Path

/**
 * Configuration bundle for a single [CodingAgent] instance.
 *
 * This is a thin data carrier — it holds references to the pre-built pieces
 * (llm bridge, tool executor, initial system prompt, session) and a few
 * tuning knobs. Constructing all the sub-pieces (system prompt, tool
 * registry, session file) is the caller's job, typically in the CLI command.
 */
data class CodingAgentOptions(
    /** Model identifier sent to the LLM on every turn. */
    val model: String,

    /** The cwd the coding agent is operating in — where tool calls resolve paths. */
    val workDir: Path,

    /** Bridge to the LLM provider (typically `ProviderLlmBridge` over `LMStudioProvider`). */
    val llmBridge: LlmBridge,

    /** Pre-built tool executor (includes the registry + supervisor + optional remote client). */
    val toolCallExecutor: ToolCallExecutor,

    /** The full system prompt. Built by the caller via `SystemPromptBuilder`. */
    val systemPrompt: String,

    /**
     * Pre-seeded messages loaded from a resumed session, or empty for a new session.
     * The agent's in-memory state starts with these as if they had been sent/received.
     */
    val initialMessages: List<fraggle.agent.message.AgentMessage> = emptyList(),

    /**
     * Maximum iterations (tool-call rounds) per [CodingAgent.prompt] call.
     * Prevents a runaway agent from looping forever.
     */
    val maxIterations: Int = 20,

    /**
     * Supervision mode for tool calls. Coding agent uses a simple all-or-none
     * model (see docs/plans/coding-agent.md): `ask` means every tool call
     * triggers a prompt handler; `none` means auto-approve everything.
     *
     * The actual supervisor is injected through [toolCallExecutor] — this
     * field is kept on the options for display purposes (footer, header) and
     * to let the orchestrator know whether to surface the approval UI.
     */
    val supervisionMode: SupervisionMode = SupervisionMode.ASK,

    /**
     * Compaction policy. Defaults to [RatioCompactionPolicy] at 70% context
     * usage. When context-window size is unknown (`ContextUsage.isUnknown`),
     * this policy silently becomes a no-op so short LM Studio sessions don't
     * get compacted unnecessarily.
     */
    val compactionPolicy: CompactionPolicy = RatioCompactionPolicy(triggerRatio = 0.70),

    /**
     * Number of recent messages to keep verbatim when compacting. Everything
     * before this tail is summarized by the compactor.
     */
    val compactionKeepRecentMessages: Int = 12,

    /**
     * Total context window size (tokens) for the current model. Used by
     * [fraggle.agent.compaction.ContextUsage.fromMessages] to compute the
     * ratio. Set to 0 if unknown — the ratio policy becomes a no-op and the
     * coding agent effectively stops auto-compacting until the caller
     * provides a real value.
     */
    val contextWindowTokens: Int = 0,
) {
    enum class SupervisionMode {
        /** Prompt the user for approval on every tool call. */
        ASK,

        /** Auto-approve all tool calls. For sandboxed / container environments. */
        NONE,
    }
}
