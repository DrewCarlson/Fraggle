package components

import DashboardStyles
import LocalWebSocketService
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*
import fraggle.models.FraggleEvent
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

/**
 * A pending tool permission request displayed as a toast.
 */
private data class PendingPermission(
    val requestId: String,
    val chatId: String,
    val toolName: String,
    val argsJson: String,
)

/**
 * Non-blocking toast notifications for tool permission requests.
 * Appears in the bottom-right corner of the dashboard.
 */
@Composable
fun ToolPermissionToasts() {
    val wsService = LocalWebSocketService.current
    var pendingRequests by remember { mutableStateOf(listOf<PendingPermission>()) }

    LaunchedEffect(Unit) {
        wsService.toolPermissionEvents.collectLatest { event ->
            when (event) {
                is FraggleEvent.ToolPermissionRequest -> {
                    val request = PendingPermission(
                        requestId = event.requestId,
                        chatId = event.chatId,
                        toolName = event.toolName,
                        argsJson = event.argsJson,
                    )
                    pendingRequests = pendingRequests + request
                }
                is FraggleEvent.ToolPermissionGranted -> {
                    pendingRequests = pendingRequests.filter { it.requestId != event.requestId }
                }
                is FraggleEvent.ToolPermissionTimeout -> {
                    pendingRequests = pendingRequests.filter { it.requestId != event.requestId }
                }
                else -> {}
            }
        }
    }

    if (pendingRequests.isEmpty()) return

    // Toast container — fixed bottom-right
    Div({
        classes(DashboardStyles.toastContainer)
    }) {
        pendingRequests.forEach { request ->
            key(request.requestId) {
                PermissionToast(
                    request = request,
                    onApprove = {
                        wsService.respondToolPermission(request.requestId, granted = true)
                        pendingRequests = pendingRequests.filter { it.requestId != request.requestId }
                    },
                    onDeny = {
                        wsService.respondToolPermission(request.requestId, granted = false)
                        pendingRequests = pendingRequests.filter { it.requestId != request.requestId }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionToast(
    request: PendingPermission,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Div({
        classes(DashboardStyles.toast)
    }) {
        // Header
        Div({
            classes(DashboardStyles.toastHeader)
        }) {
            Div({
                classes(DashboardStyles.toastIcon)
            }) {
                I({ classes("bi", "bi-shield-exclamation") })
            }
            Div({
                classes(DashboardStyles.toastTitle)
            }) {
                Text("Tool Permission")
            }
            Span({
                style {
                    fontSize(12.px)
                    color(Color("#71717a"))
                }
            }) {
                Text(request.chatId)
            }
        }

        // Tool name
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(8.px)
                marginBottom(8.px)
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    color(Color("#71717a"))
                }
            }) {
                Text("Tool:")
            }
            Span({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color("#e4e4e7"))
                }
            }) {
                Text(request.toolName)
            }
        }

        // Args preview
        Div({
            classes(DashboardStyles.toastBody)
        }) {
            Text(formatArgs(request.argsJson))
        }

        // Actions
        Div({
            classes(DashboardStyles.toastActions)
        }) {
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonDanger)
                onClick { onDeny() }
            }) {
                I({ classes("bi", "bi-x-lg") })
                Text("Deny")
            }
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonSuccess)
                onClick { onApprove() }
            }) {
                I({ classes("bi", "bi-check-lg") })
                Text("Approve")
            }
        }
    }
}

/**
 * Format JSON args for display, showing key-value pairs cleanly.
 */
private fun formatArgs(argsJson: String): String {
    return try {
        val json = Json { prettyPrint = true }
        val element = json.parseToJsonElement(argsJson)
        if (element is JsonObject) {
            element.entries.joinToString("\n") { (key, value) ->
                val valueStr = when (value) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
                // Truncate long values
                val display = if (valueStr.length > 200) {
                    valueStr.take(200) + "..."
                } else {
                    valueStr
                }
                "$key: $display"
            }
        } else {
            argsJson
        }
    } catch (_: Exception) {
        argsJson
    }
}
