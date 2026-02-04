import androidx.compose.runtime.*
import app.softwork.routingcompose.BrowserRouter
import app.softwork.routingcompose.Router
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposableInBody
import kotlin.time.Duration

/**
 * Shared HTTP client for API calls to the backend.
 */
val apiClient = HttpClient(Js) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

/**
 * Get the API base URL from the current window location.
 */
fun getApiBaseUrl(): String {
    val location = window.location
    return "${location.protocol}//${location.host}/api/v1"
}

// ============================================================================
// API Response Models
// ============================================================================

@Serializable
data class SystemStatus(
    val uptime: Duration,
    val activeConversations: Int,
    val connectedBridges: Int,
    val availableSkills: Int,
    val scheduledTasks: Int,
    val memoryUsage: MemoryUsage,
)

@Serializable
data class MemoryUsage(
    val heapUsed: Long,
    val heapMax: Long,
)

/**
 * State holder for system status with loading and error states.
 */
sealed class StatusState {
    data object Loading : StatusState()
    data class Success(val status: SystemStatus) : StatusState()
    data class Error(val message: String) : StatusState()
}

fun main() {
    renderComposableInBody {
        Style(DashboardStyles)
        App()
    }

    // Hide loading spinner
    document.getElementById("loading")?.classList?.add("hidden")
}

@Composable
fun App() {
    var sidebarCollapsed by remember { mutableStateOf(false) }
    var statusState by remember { mutableStateOf<StatusState>(StatusState.Loading) }

    // Fetch status periodically
    LaunchedEffect(Unit) {
        while (true) {
            statusState = try {
                val status = apiClient.get("${getApiBaseUrl()}/status").body<SystemStatus>()
                StatusState.Success(status)
            } catch (e: Exception) {
                StatusState.Error(e.message ?: "Connection failed")
            }
            delay(5000) // Refresh every 5 seconds
        }
    }

    Div({
        classes(DashboardStyles.appContainer)
    }) {
        BrowserRouter("/") {
            val router = Router.current

            // Sidebar
            Aside({
                classes(DashboardStyles.sidebar)
                if (sidebarCollapsed) {
                    classes(DashboardStyles.sidebarCollapsed)
                }
            }) {
                // Logo/Brand
                Div({
                    classes(DashboardStyles.sidebarHeader)
                }) {
                    Span({
                        classes(DashboardStyles.logo)
                    }) {
                        Text("Fraggle")
                    }
                    if (!sidebarCollapsed) {
                        Span({
                            classes(DashboardStyles.logoSubtitle)
                        }) {
                            Text("Dashboard")
                        }
                    }
                }

                // Navigation
                Nav({
                    classes(DashboardStyles.sidebarNav)
                }) {
                    NavItem(
                        icon = "bi-house",
                        label = "Overview",
                        route = "/",
                        router = router,
                        collapsed = sidebarCollapsed,
                    )
                    NavItem(
                        icon = "bi-chat-dots",
                        label = "Conversations",
                        route = "/conversations",
                        router = router,
                        collapsed = sidebarCollapsed,
                    )
                    NavItem(
                        icon = "bi-plug",
                        label = "Bridges",
                        route = "/bridges",
                        router = router,
                        collapsed = sidebarCollapsed,
                    )
                    NavItem(
                        icon = "bi-tools",
                        label = "Skills",
                        route = "/skills",
                        router = router,
                        collapsed = sidebarCollapsed,
                    )
                    NavItem(
                        icon = "bi-journal-bookmark",
                        label = "Memory",
                        route = "/memory",
                        router = router,
                        collapsed = sidebarCollapsed,
                    )
                    NavItem(
                        icon = "bi-calendar-event",
                        label = "Scheduler",
                        route = "/scheduler",
                        router = router,
                        collapsed = sidebarCollapsed,
                    )
                    NavItem(
                        icon = "bi-gear",
                        label = "Settings",
                        route = "/settings",
                        router = router,
                        collapsed = sidebarCollapsed,
                    )
                }

                // Collapse toggle
                Div({
                    classes(DashboardStyles.sidebarFooter)
                }) {
                    Button({
                        classes(DashboardStyles.collapseButton)
                        onClick { sidebarCollapsed = !sidebarCollapsed }
                    }) {
                        I({
                            classes("bi", if (sidebarCollapsed) "bi-chevron-right" else "bi-chevron-left")
                        })
                    }
                }
            }

            // Main content
            Main({
                classes(DashboardStyles.mainContent)
            }) {
                // Header
                Header({
                    classes(DashboardStyles.header)
                }) {
                    H1({
                        classes(DashboardStyles.pageTitle)
                    }) {
                        Text(getPageTitle(router.currentPath.path))
                    }

                    Div({
                        classes(DashboardStyles.headerActions)
                    }) {
                        // Status indicator
                        Div({
                            classes(DashboardStyles.statusIndicator)
                        }) {
                            when (statusState) {
                                is StatusState.Loading -> {
                                    Span({
                                        classes(DashboardStyles.statusDot)
                                    })
                                    Text("Connecting...")
                                }
                                is StatusState.Success -> {
                                    Span({
                                        classes(DashboardStyles.statusDot, DashboardStyles.statusOnline)
                                    })
                                    Text("Connected")
                                }
                                is StatusState.Error -> {
                                    Span({
                                        classes(DashboardStyles.statusDot, DashboardStyles.statusError)
                                    })
                                    Text("Disconnected")
                                }
                            }
                        }
                    }
                }

                // Page content
                Div({
                    classes(DashboardStyles.pageContent)
                }) {
                    route("/") {
                        OverviewPage(statusState)
                    }
                    route("/conversations") {
                        ConversationsPage()
                    }
                    route("/bridges") {
                        BridgesPage()
                    }
                    route("/skills") {
                        SkillsPage()
                    }
                    route("/memory") {
                        MemoryPage()
                    }
                    route("/scheduler") {
                        SchedulerPage()
                    }
                    route("/settings") {
                        SettingsPage()
                    }
                    noMatch {
                        NotFoundPage()
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: String,
    label: String,
    route: String,
    router: Router,
    collapsed: Boolean,
) {
    val isActive = router.currentPath.path == route ||
            (route != "/" && router.currentPath.path.startsWith(route))

    A(
        href = route,
        attrs = {
            classes(DashboardStyles.navItem)
            if (isActive) {
                classes(DashboardStyles.navItemActive)
            }
            onClick {
                it.preventDefault()
                router.navigate(route)
            }
            if (collapsed) {
                title(label)
            }
        }
    ) {
        I({
            classes("bi", icon, DashboardStyles.navIcon)
        })
        if (!collapsed) {
            Span({
                classes(DashboardStyles.navLabel)
            }) {
                Text(label)
            }
        }
    }
}

private fun getPageTitle(path: String): String = when {
    path == "/" -> "Overview"
    path.startsWith("/conversations") -> "Conversations"
    path.startsWith("/bridges") -> "Bridges"
    path.startsWith("/skills") -> "Skills"
    path.startsWith("/memory") -> "Memory"
    path.startsWith("/scheduler") -> "Scheduler"
    path.startsWith("/settings") -> "Settings"
    else -> "Fraggle"
}

// ============================================================================
// Page Components
// ============================================================================

@Composable
fun OverviewPage(statusState: StatusState) {
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
fun ConversationsPage() {
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
            Text("Conversation management coming soon...")
        }
    }
}

@Composable
fun BridgesPage() {
    Section({
        classes(DashboardStyles.section)
    }) {
        H2({
            classes(DashboardStyles.sectionTitle)
        }) {
            Text("Chat Bridges")
        }
        Div({
            classes(DashboardStyles.cardList)
        }) {
            BridgeCard(
                name = "Signal",
                status = "Connected",
                isConnected = true,
                icon = "bi-signal",
            )
            BridgeCard(
                name = "WhatsApp",
                status = "Not configured",
                isConnected = false,
                icon = "bi-whatsapp",
            )
            BridgeCard(
                name = "Discord",
                status = "Not configured",
                isConnected = false,
                icon = "bi-discord",
            )
            BridgeCard(
                name = "Telegram",
                status = "Not configured",
                isConnected = false,
                icon = "bi-telegram",
            )
        }
    }
}

@Composable
private fun BridgeCard(
    name: String,
    status: String,
    isConnected: Boolean,
    icon: String,
) {
    Div({
        classes(DashboardStyles.bridgeCard)
    }) {
        Div({
            classes(DashboardStyles.bridgeIcon)
        }) {
            I({ classes("bi", icon) })
        }
        Div({
            classes(DashboardStyles.bridgeInfo)
        }) {
            Span({
                classes(DashboardStyles.bridgeName)
            }) {
                Text(name)
            }
            Span({
                classes(DashboardStyles.bridgeStatus)
                if (isConnected) {
                    classes(DashboardStyles.statusOnline)
                }
            }) {
                Text(status)
            }
        }
        Button({
            classes(DashboardStyles.button, DashboardStyles.buttonSmall)
            if (isConnected) {
                classes(DashboardStyles.buttonOutline)
            } else {
                classes(DashboardStyles.buttonPrimary)
            }
        }) {
            Text(if (isConnected) "Configure" else "Connect")
        }
    }
}

@Composable
fun SkillsPage() {
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
            Text("Skills management coming soon...")
        }
    }
}

@Composable
fun MemoryPage() {
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
            Text("Memory management coming soon...")
        }
    }
}

@Composable
fun SchedulerPage() {
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
            Text("Task scheduler coming soon...")
        }
    }
}

@Composable
fun SettingsPage() {
    Section({
        classes(DashboardStyles.section)
    }) {
        H2({
            classes(DashboardStyles.sectionTitle)
        }) {
            Text("Configuration")
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
                Text("Settings management coming soon...")
            }
        }
    }
}

@Composable
fun NotFoundPage() {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.Center)
            height(100.percent)
            gap(16.px)
        }
    }) {
        I({
            classes("bi", "bi-exclamation-triangle")
            style {
                fontSize(48.px)
                color(Color("#f59e0b"))
            }
        })
        H2 {
            Text("Page Not Found")
        }
        P({
            style {
                color(Color("#71717a"))
            }
        }) {
            Text("The page you're looking for doesn't exist.")
        }
    }
}
