package fraggle.agent.tool

import ai.koog.agents.core.tools.SimpleTool

/**
 * Adapter that wraps a Koog [SimpleTool] as an [AgentToolDef],
 * enabling gradual migration from Koog tools to the new agent system.
 */
@Suppress("UNCHECKED_CAST")
class SimpleToolAdapter<Args : Any>(
    private val simpleTool: SimpleTool<Args>,
) : AgentToolDef<Args>(
    name = simpleTool.name,
    description = simpleTool.descriptor.description,
    argsSerializer = simpleTool.argsType as kotlinx.serialization.KSerializer<Args>,
) {
    override suspend fun execute(args: Args): String {
        return simpleTool.execute(args)
    }
}

/**
 * Convert a Koog SimpleTool to an AgentToolDef.
 */
fun <Args : Any> SimpleTool<Args>.toAgentToolDef(): AgentToolDef<Args> = SimpleToolAdapter(this)
