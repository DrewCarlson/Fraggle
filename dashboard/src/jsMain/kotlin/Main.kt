import androidx.compose.runtime.*
import app.softwork.routingcompose.BrowserRouter
import app.softwork.routingcompose.Router
import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.browser.document
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposableInBody

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
                            Span({
                                classes(DashboardStyles.statusDot, DashboardStyles.statusOnline)
                            })
                            Text("Connected")
                        }
                    }
                }

                // Page content
                Div({
                    classes(DashboardStyles.pageContent)
                }) {
                    route("/") {
                        OverviewPage()
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
fun OverviewPage() {
    Div({
        classes(DashboardStyles.cardGrid)
    }) {
        StatCard(
            title = "Active Conversations",
            value = "3",
            icon = "bi-chat-dots",
            iconBgColor = "#6366f11a",
            iconColor = "#6366f1",
        )
        StatCard(
            title = "Connected Bridges",
            value = "1",
            icon = "bi-plug",
            iconBgColor = "#22c55e1a",
            iconColor = "#22c55e",
        )
        StatCard(
            title = "Available Skills",
            value = "12",
            icon = "bi-tools",
            iconBgColor = "#f59e0b1a",
            iconColor = "#f59e0b",
        )
        StatCard(
            title = "Scheduled Tasks",
            value = "5",
            icon = "bi-calendar-event",
            iconBgColor = "#ec48991a",
            iconColor = "#ec4899",
        )
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
