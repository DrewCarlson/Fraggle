import androidx.compose.runtime.*
import app.softwork.routingcompose.BrowserRouter
import app.softwork.routingcompose.Router
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import fraggle.models.SystemStatus
import org.jetbrains.compose.web.css.Style
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposableInBody
import components.ToolPermissionToasts
import screens.*
import org.jetbrains.compose.web.css.*

/**
 * Shared HTTP client for API calls to the backend.
 */
val apiClient = HttpClient(Js) {
    install(AdaptiveProtocolPlugin)
    defaultRequest {
        val location = window.location
        val serverUrl = "${location.protocol}//${location.host}/api/v1/"
        attributes.put(ServerUrlAttribute, serverUrl)
        url {
            takeFrom(serverUrl)
        }
    }
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

fun main() {
    val scope = CoroutineScope(SupervisorJob())
    val webSocketService = WebSocketService(scope)
    webSocketService.connect()
    renderComposableInBody {
        Style(DashboardStyles)
        CompositionLocalProvider(
            LocalWebSocketService provides webSocketService
        ) {
            App()
        }
    }

    // Hide loading spinner
    document.getElementById("loading")?.classList?.add("hidden")
}

@Composable
fun App() {
    val isMobile = rememberIsMobile()
    var sidebarCollapsed by remember { mutableStateOf(false) }
    var sidebarOpen by remember { mutableStateOf(false) }

    // Initialize WebSocket service
    val wsService = LocalWebSocketService.current
    val connectionState by rememberConnectionState(wsService)

    // Load status data with WebSocket refresh
    val (statusState, refreshStatus) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Status, RefreshTrigger.Bridges, RefreshTrigger.Chats),
    ) {
        apiClient.get("status").body<SystemStatus>()
    }

    Div({
        classes(DashboardStyles.appContainer)
    }) {
        BrowserRouter("/") {
            val router = Router.current

            // Mobile backdrop
            if (isMobile && sidebarOpen) {
                Div({
                    classes(DashboardStyles.sidebarBackdrop)
                    onClick { sidebarOpen = false }
                })
            }

            // Sidebar
            Sidebar(
                collapsed = if (isMobile) false else sidebarCollapsed,
                isMobile = isMobile,
                isMobileOpen = sidebarOpen,
                router = router,
                onCollapseToggle = {
                    if (isMobile) {
                        sidebarOpen = !sidebarOpen
                    } else {
                        sidebarCollapsed = !sidebarCollapsed
                    }
                },
                onNavigate = {
                    if (isMobile) sidebarOpen = false
                },
            )

            // Main content
            Main({
                classes(DashboardStyles.mainContent)
            }) {
                // Header
                Header({
                    classes(DashboardStyles.header)
                    if (isMobile) {
                        style {
                            padding(16.px)
                        }
                    }
                }) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                        }
                    }) {
                        if (isMobile) {
                            Button({
                                classes(DashboardStyles.hamburgerButton)
                                onClick { sidebarOpen = true }
                            }) {
                                I({ classes("bi", "bi-list") })
                            }
                        }
                        H1({
                            classes(DashboardStyles.pageTitle)
                            if (isMobile) {
                                style { fontSize(18.px) }
                            }
                        }) {
                            Text(getPageTitle(router.currentPath.path))
                        }
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
                    if (isMobile) {
                        classes(DashboardStyles.pageContentMobile)
                    }
                }) {
                    route("/") {
                        OverviewScreen(statusState, wsService)
                    }
                    route("/chats") {
                        ConversationsScreen(wsService)
                    }
                    route("/bridges") {
                        BridgesScreen(wsService)
                    }
                    route("/tools") {
                        ToolsScreen(wsService)
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
                    route("/tracing") {
                        string { sessionId ->
                            TracingDetailScreen(sessionId, wsService)
                        }
                        noMatch {
                            TracingScreen(wsService)
                        }
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

    // Tool permission toast notifications (global, outside routing)
    ToolPermissionToasts()
}

@Composable
private fun Sidebar(
    collapsed: Boolean,
    isMobile: Boolean,
    isMobileOpen: Boolean,
    router: Router,
    onCollapseToggle: () -> Unit,
    onNavigate: () -> Unit,
) {
    Aside({
        classes(DashboardStyles.sidebar)
        if (isMobile) {
            classes(DashboardStyles.sidebarMobile)
            if (isMobileOpen) {
                classes(DashboardStyles.sidebarMobileOpen)
            }
        } else if (collapsed) {
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
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-chat-dots",
                label = "Chats",
                route = "/chats",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-plug",
                label = "Bridges",
                route = "/bridges",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-tools",
                label = "Tools",
                route = "/tools",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-lightbulb",
                label = "Skills",
                route = "/skills",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-journal-bookmark",
                label = "Memory",
                route = "/memory",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-calendar-event",
                label = "Scheduler",
                route = "/scheduler",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-activity",
                label = "Tracing",
                route = "/tracing",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
            NavItem(
                icon = "bi-gear",
                label = "Settings",
                route = "/settings",
                router = router,
                collapsed = collapsed,
                onNavigate = onNavigate,
            )
        }

        // Collapse toggle (desktop only)
        if (!isMobile) {
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
}

@Composable
private fun NavItem(
    icon: String,
    label: String,
    route: String,
    router: Router,
    collapsed: Boolean,
    onNavigate: () -> Unit,
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
                onNavigate()
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
    path.startsWith("/chats") -> "Chat History"
    path.startsWith("/bridges") -> "Bridges"
    path.startsWith("/tools") -> "Tools"
    path.startsWith("/skills") -> "Skills"
    path.startsWith("/memory") -> "Memory"
    path.startsWith("/scheduler") -> "Scheduler"
    path.startsWith("/tracing") -> "Tracing"
    path.startsWith("/settings") -> "Settings"
    else -> "Fraggle"
}
