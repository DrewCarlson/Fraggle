package fraggle.executor

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import fraggle.agent.ToolExecutionContext
import fraggle.executor.supervision.PermissionResult
import fraggle.executor.supervision.ToolSupervisor

/**
 * Wraps a [SimpleTool] with supervision (permission checks) and optional remote forwarding.
 *
 * In LOCAL mode, the delegate tool executes directly after permission is granted.
 * In REMOTE mode, the tool call is forwarded to a [RemoteToolClient].
 */
class ManagedTool<Args : Any>(
    private val delegate: SimpleTool<Args>,
    private val supervisor: ToolSupervisor,
    private val remoteClient: RemoteToolClient?,
    private val serializer: JSONSerializer = KotlinxSerializer(),
) : SimpleTool<Args>(
    argsType = delegate.argsType,
    name = delegate.name,
    description = delegate.descriptor.description,
) {

    override suspend fun execute(args: Args): String {
        val argsJson = delegate.encodeArgsToString(args, serializer)
        val chatId = ToolExecutionContext.current()?.chatId ?: "unknown"

        return when (val p = supervisor.checkPermission(name, argsJson, chatId)) {
            is PermissionResult.Approved -> {
                remoteClient?.execute(name, argsJson)
                    ?: delegate.execute(args)
            }
            is PermissionResult.Denied -> "Error: Tool denied: ${p.reason}"
            is PermissionResult.Timeout -> "Error: Permission timed out"
        }
    }
}

/**
 * Wrap this tool with supervision and optional remote forwarding.
 */
fun <Args : Any> SimpleTool<Args>.managed(
    supervisor: ToolSupervisor,
    remoteClient: RemoteToolClient?,
): ManagedTool<Args> = ManagedTool(this, supervisor, remoteClient)
