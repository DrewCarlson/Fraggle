package org.drewcarlson.fraggle.executor.supervision

/**
 * Interface for checking tool execution permissions.
 */
interface ToolSupervisor {
    suspend fun checkPermission(toolName: String, argsJson: String, chatId: String): PermissionResult
}
