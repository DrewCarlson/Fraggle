package fraggle.executor.supervision

/**
 * Supervisor that checks an auto-approve list first, then delegates
 * to a [ToolPermissionHandler] for interactive approval.
 */
class InteractiveToolSupervisor(
    private val autoApproveTools: List<String>,
    private val handler: ToolPermissionHandler,
) : ToolSupervisor {

    override suspend fun checkPermission(toolName: String, argsJson: String, chatId: String): PermissionResult {
        if (toolName in autoApproveTools) {
            return PermissionResult.Approved
        }

        val requestId = java.util.UUID.randomUUID().toString()
        return try {
            val approved = handler.requestPermission(requestId, toolName, argsJson, chatId)
            if (approved) PermissionResult.Approved else PermissionResult.Denied("User denied")
        } catch (_: Exception) {
            PermissionResult.Timeout
        }
    }
}
