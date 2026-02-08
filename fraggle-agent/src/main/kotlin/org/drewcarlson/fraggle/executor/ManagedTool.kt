package org.drewcarlson.fraggle.executor

import ai.koog.agents.core.tools.SimpleTool
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.agent.ToolExecutionContext
import org.drewcarlson.fraggle.executor.supervision.PermissionResult
import org.drewcarlson.fraggle.executor.supervision.ToolSupervisor

private val json = Json { ignoreUnknownKeys = true }

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
) : SimpleTool<Args>(delegate.argsSerializer, delegate.name, delegate.descriptor.description) {

    override suspend fun execute(args: Args): String {
        val argsJson = json.encodeToString(delegate.argsSerializer, args)
        val chatId = ToolExecutionContext.current()?.chatId ?: "unknown"

        when (val p = supervisor.checkPermission(name, argsJson, chatId)) {
            is PermissionResult.Approved -> {}
            is PermissionResult.Denied -> return "Error: Tool denied: ${p.reason}"
            is PermissionResult.Timeout -> return "Error: Permission timed out"
        }

        if (remoteClient != null) {
            return remoteClient.execute(name, argsJson)
        }

        return delegate.execute(args)
    }
}

/**
 * Wrap this tool with supervision and optional remote forwarding.
 */
fun <Args : Any> SimpleTool<Args>.managed(
    supervisor: ToolSupervisor,
    remoteClient: RemoteToolClient? = null,
): ManagedTool<Args> = ManagedTool(this, supervisor, remoteClient)
