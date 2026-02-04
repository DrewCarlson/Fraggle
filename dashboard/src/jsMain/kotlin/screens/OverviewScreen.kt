package screens

import DashboardStyles
import StatusState
import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import kotlin.time.Duration

@Composable
fun OverviewScreen(statusState: StatusState) {
    when (statusState) {
        is StatusState.Loading -> {
            Div({
                classes(DashboardStyles.card)
                style {
                    padding(48.px)
                    textAlign("center")
                }
            }) {
                I({
                    classes("bi", "bi-arrow-repeat")
                    style {
                        fontSize(32.px)
                        color(Color("#6366f1"))
                        property("animation", "spin 1s linear infinite")
                    }
                })
                P({
                    style {
                        color(Color("#71717a"))
                        marginTop(16.px)
                    }
                }) {
                    Text("Loading status...")
                }
            }
        }
        is StatusState.Error -> {
            Div({
                classes(DashboardStyles.card)
                style {
                    padding(48.px)
                    textAlign("center")
                }
            }) {
                I({
                    classes("bi", "bi-exclamation-triangle")
                    style {
                        fontSize(32.px)
                        color(Color("#ef4444"))
                    }
                })
                P({
                    style {
                        color(Color("#ef4444"))
                        marginTop(16.px)
                        fontWeight("600")
                    }
                }) {
                    Text("Connection Error")
                }
                P({
                    style {
                        color(Color("#71717a"))
                        marginTop(8.px)
                    }
                }) {
                    Text(statusState.message)
                }
            }
        }
        is StatusState.Success -> {
            val status = statusState.status

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
                StatCard(
                    title = "Connected Bridges",
                    value = status.connectedBridges.toString(),
                    icon = "bi-plug",
                    iconBgColor = "#22c55e1a",
                    iconColor = "#22c55e",
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
                            value = formatUptime(status.uptime),
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
