package fraggle.scheduling

import fraggle.agent.tool.AgentToolDef
import kotlinx.serialization.Serializable

/**
 * Ends a scheduled-task turn without sending a message to the user.
 *
 * Registered alongside the other scheduling tools. The assistant's tool loop
 * simply records that it was invoked; [fraggle.agent.FraggleAgent] inspects
 * the post-turn message trace and, when the turn originated from a scheduled
 * task and this tool was called, returns an [fraggle.agent.AgentResponse.Silent]
 * so the orchestrator skips the outbound send.
 *
 * On non-scheduled turns (real user messages) the tool is still callable, but
 * the assistant ignores the signal — a human asked a question, so silence
 * would be surprising.
 */
class SkipReplyTool : AgentToolDef<SkipReplyTool.Args>(
    name = NAME,
    description = "End this turn without sending a message to the user. " +
        "Call this when the task or its result suggest there is no new information " +
        "for the user to see.",
    argsSerializer = Args.serializer(),
) {
    @Serializable
    data class Args(val dummy: String = "")

    override suspend fun execute(args: Args): String = "Reply suppressed."

    companion object {
        const val NAME = "skip_reply"
    }
}
