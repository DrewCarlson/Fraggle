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
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.models.SystemStatus
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposableInBody
import screens.*

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

    // Initialize WebSocket service
    val wsService = rememberWebSocketService()
    val connectionState by rememberConnectionState(wsService)

    // Load status data with WebSocket refresh
    val (statusState, refreshStatus) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Status, RefreshTrigger.Bridges, RefreshTrigger.Conversations),
    ) {
        apiClient.get("${getApiBaseUrl()}/status").body<SystemStatus>()
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
                        // Connection status indicator
                        ConnectionStatus(connectionState)
                    }
                }

                // Page content
                Div({
                    classes(DashboardStyles.pageContent)
                }) {
                    route("/") {
                        OverviewScreen(statusState, wsService)
                    }
                    route("/conversations") {
                        ConversationsScreen(wsService)
                    }
                    route("/bridges") {
                        BridgesScreen(wsService)
                    }
                    route("/skills") {
                        SkillsScreen(wsService)
                    }
                    route("/memory") {
                        MemoryScreen(wsService)
                    }
                    route("/scheduler") {
                        SchedulerScreen(wsService)
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
private fun ConnectionStatus(connectionState: ConnectionState) {
    Div({
        classes(DashboardStyles.statusIndicator)
    }) {
        when (connectionState) {
            ConnectionState.DISCONNECTED -> {
                Span({
                    classes(DashboardStyles.statusDot, DashboardStyles.statusError)
                })
                Text("Disconnected")
            }
            ConnectionState.CONNECTING -> {
                Span({
                    classes(DashboardStyles.statusDot)
                    style {
                        property("animation", "pulse 1.5s ease-in-out infinite")
                    }
                })
                Text("Connecting...")
            }
            ConnectionState.CONNECTED -> {
                Span({
                    classes(DashboardStyles.statusDot, DashboardStyles.statusOnline)
                })
                Text("Connected")
            }
            ConnectionState.RECONNECTING -> {
                Span({
                    classes(DashboardStyles.statusDot, DashboardStyles.statusWarning)
                    style {
                        property("animation", "pulse 1.5s ease-in-out infinite")
                    }
                })
                Text("Reconnecting...")
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
