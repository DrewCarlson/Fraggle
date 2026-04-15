package fraggle.executor.supervision

/**
 * Result of a tool permission check.
 */
sealed class PermissionResult {
    data object Approved : PermissionResult()
    data class Denied(val reason: String) : PermissionResult()
    data object Timeout : PermissionResult()
}
