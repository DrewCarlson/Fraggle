package org.drewcarlson.fraggle.executor.supervision

/**
 * Handler for requesting tool execution permission from the user.
 * Implementations handle the actual I/O (CLI stdin, WebSocket events, etc.).
 */
interface ToolPermissionHandler {
    suspend fun requestPermission(requestId: String, toolName: String, argsJson: String, chatId: String): Boolean
}
