package screens

import ConnectionState
import DashboardStyles
import DataState
import WebSocketService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.softwork.routingcompose.Router
import kotlinx.coroutines.delay
import org.drewcarlson.fraggle.models.SystemStatus
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberConnectionState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun OverviewScreen(statusState: DataState<SystemStatus>, wsService: WebSocketService) {
    when (statusState) {
        is DataState.Loading -> {
            LoadingCard("Loading status...")
        }

        is DataState.Error -> {
            ErrorCard(statusState.message)
        }

        is DataState.Success -> {
            val status = statusState.data
            val connectionState by rememberConnectionState(wsService)

            // Increment elapsed time every second when connected
            val liveUptime by produceState(status.uptime, status, connectionState) {
                if (connectionState == ConnectionState.CONNECTED) {
                    while (true) {
                        delay(1.seconds)
                        value += 1.seconds
                    }
                }
            }

            // Show subtle refresh indicator if refreshing
            if (statusState.isRefreshing) {
                Div({
                    style {
                        position(Position.Fixed)
                        top(80.px)
                        right(32.px)
                        fontSize(12.px)
                        color(Color("#6366f1"))
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        gap(6.px)
                    }
                }) {
                    I({
                        classes("bi", "bi-arrow-repeat")
                        style {
                            property("animation", "spin 1s linear infinite")
                        }
                    })
                    Text("Refreshing...")
                }
            }

            val router = Router.current

            Div({
                classes(DashboardStyles.cardGrid)
            }) {
                StatCard(
                    title = "Active Conversations",
                    value = status.activeConversations.toString(),
                    icon = "bi-chat-dots",
                    iconBgColor = "#6366f11a",
                    iconColor = "#6366f1",
                )
                BridgesStatCard(
                    connectedCount = status.connectedBridges,
                    uninitializedBridges = status.uninitializedBridges,
                    onClick = { router.navigate("/bridges") },
                )
                StatCard(
                    title = "Available Skills",
                    value = status.availableSkills.toString(),
                    icon = "bi-tools",
                    iconBgColor = "#f59e0b1a",
                    iconColor = "#f59e0b",
                )
                StatCard(
                    title = "Scheduled Tasks",
                    value = status.scheduledTasks.toString(),
                    icon = "bi-calendar-event",
                    iconBgColor = "#ec48991a",
                    iconColor = "#ec4899",
                )
            }

            // System info section
            Section({
                classes(DashboardStyles.section)
            }) {
                H2({
                    classes(DashboardStyles.sectionTitle)
                }) {
                    Text("System Information")
                }
                Div({
                    classes(DashboardStyles.card)
                }) {
                    Div({
                        style {
                            padding(24.px)
                            display(DisplayStyle.Grid)
                            property("grid-template-columns", "repeat(auto-fit, minmax(200px, 1fr))")
                            gap(24.px)
                        }
                    }) {
                        SystemInfoItem(
                            label = "Uptime",
                            value = formatUptime(liveUptime),
                        )
                        SystemInfoItem(
                            label = "Heap Used",
                            value = formatBytes(status.memoryUsage.heapUsed),
                        )
                        SystemInfoItem(
                            label = "Heap Max",
                            value = formatBytes(status.memoryUsage.heapMax),
                        )
                        SystemInfoItem(
                            label = "Memory Usage",
                            value = "${((status.memoryUsage.heapUsed.toDouble() / status.memoryUsage.heapMax) * 100).toInt()}%",
                        )
                    }
                }
            }

            Section({
                classes(DashboardStyles.section)
            }) {
                H2({
                    classes(DashboardStyles.sectionTitle)
                }) {
                    Text("Recent Activity")
                }
                Div({
                    classes(DashboardStyles.card)
                }) {
                    P({
                        style {
                            color(Color("#71717a"))
                            textAlign("center")
                            padding(32.px)
                        }
                    }) {
                        Text("No recent activity")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: String,
    iconBgColor: String,
    iconColor: String,
) {
    Div({
        classes(DashboardStyles.statCard)
    }) {
        Div({
            classes(DashboardStyles.statIcon)
            style {
                backgroundColor(Color(iconBgColor))
                color(Color(iconColor))
            }
        }) {
            I({ classes("bi", icon) })
        }
        Div({
            classes(DashboardStyles.statContent)
        }) {
            Span({
                classes(DashboardStyles.statValue)
            }) {
                Text(value)
            }
            Span({
                classes(DashboardStyles.statTitle)
            }) {
                Text(title)
            }
        }
    }
}

@Composable
private fun BridgesStatCard(
    connectedCount: Int,
    uninitializedBridges: List<String>,
    onClick: () -> Unit,
) {
    val hasWarning = uninitializedBridges.isNotEmpty()
    val tooltipText = if (hasWarning) {
        "Needs setup: ${uninitializedBridges.joinToString(", ")}"
    } else null

    Div({
        classes(DashboardStyles.statCard)
        style {
            cursor("pointer")
        }
        onClick { onClick() }
        tooltipText?.let { title(it) }
    }) {
        Div({
            classes(DashboardStyles.statIcon)
            style {
                backgroundColor(Color(if (hasWarning) "#f59e0b1a" else "#22c55e1a"))
                color(Color(if (hasWarning) "#f59e0b" else "#22c55e"))
            }
        }) {
            I({ classes("bi", "bi-plug") })
        }
        Div({
            classes(DashboardStyles.statContent)
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(8.px)
                }
            }) {
                Span({
                    classes(DashboardStyles.statValue)
                }) {
                    Text(connectedCount.toString())
                }
                if (hasWarning) {
                    Span({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            gap(4.px)
                            fontSize(12.px)
                            color(Color("#f59e0b"))
                            backgroundColor(Color("#f59e0b1a"))
                            padding(2.px, 6.px)
                            borderRadius(4.px)
                        }
                    }) {
                        I({ classes("bi", "bi-exclamation-triangle-fill") })
                        Text("${uninitializedBridges.size}")
                    }
                }
            }
            Span({
                classes(DashboardStyles.statTitle)
            }) {
                Text("Connected Bridges")
            }
        }
    }
}

@Composable
private fun SystemInfoItem(label: String, value: String) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            gap(4.px)
        }
    }) {
        Span({
            style {
                fontSize(12.px)
                color(Color("#71717a"))
                property("text-transform", "uppercase")
                letterSpacing(0.5.px)
            }
        }) {
            Text(label)
        }
        Span({
            style {
                fontSize(18.px)
                fontWeight("600")
                color(Color("#e4e4e7"))
            }
        }) {
            Text(value)
        }
    }
}

private fun formatUptime(uptime: Duration): String {
    val absMillis = kotlin.math.abs(uptime.inWholeMilliseconds)
    val seconds = absMillis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    return when {
        gb >= 1 -> "${gb.toInt()}GB"
        mb >= 1 -> "${mb.toInt()}MB"
        kb >= 1 -> "${kb.toInt()}KB"
        else -> "${bytes}B"
    }
}
