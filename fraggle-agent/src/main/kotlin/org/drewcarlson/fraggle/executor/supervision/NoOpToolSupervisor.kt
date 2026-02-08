package org.drewcarlson.fraggle.executor.supervision

/**
 * Supervisor that auto-approves all tool calls.
 */
class NoOpToolSupervisor : ToolSupervisor {
    override suspend fun checkPermission(
        toolName: String,
        argsJson: String,
        chatId: String
    ): PermissionResult {
        return PermissionResult.Approved
    }
}
