package fraggle.executor.supervision

import fraggle.models.ApprovalPolicy

/**
 * Supervisor that evaluates tool policy rules first, then delegates
 * to a [ToolPermissionHandler] for interactive approval.
 *
 * Policy outcomes:
 * - [ApprovalPolicy.ALLOW] -> approved immediately
 * - [ApprovalPolicy.DENY] -> denied immediately without consulting the handler
 * - [ApprovalPolicy.ASK] or no matching rule (null) -> delegate to handler
 */
class InteractiveToolSupervisor(
    private val evaluator: ToolPolicyEvaluator,
    private val handler: ToolPermissionHandler,
) : ToolSupervisor {

    override suspend fun checkPermission(toolName: String, argsJson: String, chatId: String): PermissionResult {
        when (evaluator.evaluate(toolName, argsJson)) {
            ApprovalPolicy.ALLOW -> return PermissionResult.Approved
            ApprovalPolicy.DENY -> return PermissionResult.Denied("Denied by policy")
            ApprovalPolicy.ASK, null -> { /* delegate to handler */ }
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
