package fraggle.agent.tool

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Base class for tools in the Fraggle agent system.
 * Replaces Koog's SimpleTool for the new agent loop.
 *
 * Tools define their argument schema via kotlinx.serialization and produce string results.
 */
abstract class AgentToolDef<Args : Any>(
    val name: String,
    val description: String,
    val argsSerializer: KSerializer<Args>,
) {
    /** The serial descriptor for argument schema generation. */
    val argsDescriptor: SerialDescriptor get() = argsSerializer.descriptor

    /** Execute the tool with deserialized arguments. */
    abstract suspend fun execute(args: Args): String
}
