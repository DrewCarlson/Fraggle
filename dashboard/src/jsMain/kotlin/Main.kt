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
import screens.*
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
            Sidebar(
                collapsed = sidebarCollapsed,
                router = router,
                onCollapseToggle = { sidebarCollapsed = !sidebarCollapsed },
            )

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
                        ConnectionStatus(statusState)
                    }
                }

                // Page content
                Div({
                    classes(DashboardStyles.pageContent)
                }) {
                    route("/") {
                        OverviewScreen(statusState)
                    }
                    route("/conversations") {
                        ConversationsScreen()
                    }
                    route("/bridges") {
                        BridgesScreen()
                    }
                    route("/skills") {
                        SkillsScreen()
                    }
                    route("/memory") {
                        MemoryScreen()
                    }
                    route("/scheduler") {
                        SchedulerScreen()
                    }
                    route("/settings") {
                        SettingsScreen()
                    }
                    noMatch {
                        NotFoundScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    collapsed: Boolean,
    router: Router,
    onCollapseToggle: () -> Unit,
) {
    Aside({
        classes(DashboardStyles.sidebar)
        if (collapsed) {
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
            if (!collapsed) {
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
                collapsed = collapsed,
            )
            NavItem(
                icon = "bi-chat-dots",
                label = "Conversations",
                route = "/conversations",
                router = router,
                collapsed = collapsed,
            )
            NavItem(
                icon = "bi-plug",
                label = "Bridges",
                route = "/bridges",
                router = router,
                collapsed = collapsed,
            )
            NavItem(
                icon = "bi-tools",
                label = "Skills",
                route = "/skills",
                router = router,
                collapsed = collapsed,
            )
            NavItem(
                icon = "bi-journal-bookmark",
                label = "Memory",
                route = "/memory",
                router = router,
                collapsed = collapsed,
            )
            NavItem(
                icon = "bi-calendar-event",
                label = "Scheduler",
                route = "/scheduler",
                router = router,
                collapsed = collapsed,
            )
            NavItem(
                icon = "bi-gear",
                label = "Settings",
                route = "/settings",
                router = router,
                collapsed = collapsed,
            )
        }

        // Collapse toggle
        Div({
            classes(DashboardStyles.sidebarFooter)
        }) {
            Button({
                classes(DashboardStyles.collapseButton)
                onClick { onCollapseToggle() }
            }) {
                I({
                    classes("bi", if (collapsed) "bi-chevron-right" else "bi-chevron-left")
                })
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

@Composable
private fun ConnectionStatus(statusState: StatusState) {
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
