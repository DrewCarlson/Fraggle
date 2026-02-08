package org.drewcarlson.fraggle.executor.supervision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Permission handler that prompts the user on stdout/stdin with a 60-second timeout.
 */
class CliToolPermissionHandler : ToolPermissionHandler {

    override suspend fun requestPermission(requestId: String, toolName: String, argsJson: String): Boolean {
        return withContext(Dispatchers.IO) {
            println()
            println("=== Tool Permission Request ===")
            println("Tool: $toolName")
            println("Args: $argsJson")
            print("Allow? (y/n) [n]: ")
            System.out.flush()

            val response = withTimeoutOrNull(60.seconds) {
                readlnOrNull()?.trim()?.lowercase()
            }

            response == "y" || response == "yes"
        }
    }
}
