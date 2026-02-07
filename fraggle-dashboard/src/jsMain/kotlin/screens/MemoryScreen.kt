package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.*
import apiClient
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.drewcarlson.fraggle.models.FactInfo
import org.drewcarlson.fraggle.models.MemoryResponse
import org.drewcarlson.fraggle.models.MemoryScopeInfo
import org.drewcarlson.fraggle.models.MemoryScopesResponse
import org.drewcarlson.fraggle.models.UpdateFactRequest
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.events.Event
import rememberRefreshableDataLoader
import kotlin.time.Clock
import kotlin.time.Instant

// Accent colors per scope type
private const val COLOR_GLOBAL = "#6366f1"
private const val COLOR_CHAT = "#22c55e"
private const val COLOR_USER = "#f59e0b"

private const val COLOR_BACKGROUND = "#0f0f1a"
private const val COLOR_SURFACE = "#1a1a2e"
private const val COLOR_BORDER = "#27273a"
private const val COLOR_BORDER_HOVER = "#3f3f5a"
private const val COLOR_TEXT = "#e4e4e7"
private const val COLOR_TEXT_MUTED = "#71717a"
private const val COLOR_TEXT_DIM = "#52525b"
private const val COLOR_ERROR = "#ef4444"

private const val COMPACT_BREAKPOINT = 768

@Composable
private fun rememberIsCompact(): Boolean {
    var isCompact by remember {
        mutableStateOf(window.innerWidth < COMPACT_BREAKPOINT)
    }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = {
            isCompact = window.innerWidth < COMPACT_BREAKPOINT
        }
        window.addEventListener("resize", listener)
        onDispose { window.removeEventListener("resize", listener) }
    }
    return isCompact
}

@Composable
fun MemoryScreen(wsService: WebSocketService) {
    val isCompact = rememberIsCompact()
    var selectedScope by remember { mutableStateOf<MemoryScopeInfo?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val (scopesState, refreshScopes) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Memory),
    ) {
        apiClient.get("${getApiBaseUrl()}/memory/scopes").body<MemoryScopesResponse>()
    }

    // Load facts for the selected scope
    val factsEndpoint = selectedScope?.let { scope ->
        when (scope.type) {
            "global" -> "/memory/global"
            "chat" -> "/memory/chat/${scope.id}"
            "user" -> "/memory/user/${scope.id}"
            else -> null
        }
    }

    val (factsState, refreshFacts) = rememberRefreshableDataLoader(
        factsEndpoint,
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Memory),
    ) {
        if (factsEndpoint != null) {
            apiClient.get("${getApiBaseUrl()}$factsEndpoint").body<MemoryResponse>()
        } else {
            MemoryResponse(scope = "", facts = emptyList())
        }
    }

    // Auto-select the first scope when scopes load and nothing is selected
    LaunchedEffect(scopesState) {
        if (selectedScope == null && scopesState is DataState.Success) {
            selectedScope = scopesState.data.scopes.firstOrNull()
        }
    }

    // Clear confirmation dialog
    if (showClearConfirm && selectedScope != null) {
        ClearConfirmDialog(
            scope = selectedScope!!,
            onConfirm = {
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false },
            onCleared = {
                refreshScopes()
                refreshFacts()
            },
        )
    }

    Section({
        classes(DashboardStyles.section)
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("margin", "0")
            if (isCompact) {
                // Stacked: natural height, page scrolls
                overflow("visible")
            } else {
                // Side-by-side: fill viewport, internal scroll
                height(100.percent)
                overflow("hidden")
            }
        }
    }) {
        // Header
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                marginBottom(16.px)
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(12.px)
                }
            }) {
                H2({
                    classes(DashboardStyles.sectionTitle)
                    style { property("margin", "0") }
                }) {
                    Text("Memory Store")
                }
                DataStateLoadingSpinner(scopesState)
            }
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                onClick {
                    refreshScopes()
                    refreshFacts()
                }
            }) {
                I({ classes("bi", "bi-arrow-repeat") })
                Text("Refresh")
            }
        }

        when (scopesState) {
            is DataState.Loading -> {
                LoadingCard("Loading memory scopes...")
            }
            is DataState.Error -> {
                ErrorCard(scopesState.message)
            }
            is DataState.Success -> {
                val allScopes = scopesState.data.scopes
                if (allScopes.isEmpty()) {
                    EmptyCard("No memory stored yet. The agent will remember facts as you chat.", "bi-journal-bookmark")
                } else {
                    // Stat summary cards
                    MemoryStatCards(allScopes)

                    // Two-panel layout
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            gap(24.px)
                            marginTop(24.px)
                            if (isCompact) {
                                flexDirection(FlexDirection.Column)
                            } else {
                                flex(1)
                                minHeight(0.px)
                                overflow("hidden")
                            }
                        }
                    }) {
                        // Left panel: Scope browser
                        ScopeBrowser(
                            scopes = allScopes,
                            selectedScope = selectedScope,
                            onSelectScope = { scope ->
                                selectedScope = scope
                                searchQuery = ""
                            },
                            isCompact = isCompact,
                        )

                        // Right panel: Facts viewer
                        if (selectedScope != null) {
                            FactsPanel(
                                scope = selectedScope!!,
                                factsState = factsState,
                                searchQuery = searchQuery,
                                onSearchChange = { searchQuery = it },
                                onClear = { showClearConfirm = true },
                                onFactUpdated = {
                                    refreshFacts()
                                    refreshScopes()
                                },
                                isCompact = isCompact,
                            )
                        } else {
                            // No scope selected placeholder
                            Div({
                                style {
                                    flex(1)
                                    minWidth(0.px)
                                }
                            }) {
                                EmptyCard("Select a scope to view its facts", "bi-arrow-left-circle")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryStatCards(scopes: List<MemoryScopeInfo>) {
    val totalFacts = scopes.sumOf { it.factCount }
    val globalCount = scopes.count { it.type == "global" }
    val chatCount = scopes.count { it.type == "chat" }
    val userCount = scopes.count { it.type == "user" }

    Div({
        style {
            display(DisplayStyle.Grid)
            property("grid-template-columns", "repeat(auto-fit, minmax(180px, 1fr))")
            gap(16.px)
        }
    }) {
        MemoryStatCard(
            value = totalFacts.toString(),
            label = "Total Facts",
            icon = "bi-journal-text",
            color = COLOR_GLOBAL,
        )
        MemoryStatCard(
            value = scopes.size.toString(),
            label = "Active Scopes",
            icon = "bi-collection",
            color = COLOR_TEXT_MUTED,
        )
        if (chatCount > 0) {
            MemoryStatCard(
                value = chatCount.toString(),
                label = "Chat Scopes",
                icon = "bi-chat-dots",
                color = COLOR_CHAT,
            )
        }
        if (userCount > 0) {
            MemoryStatCard(
                value = userCount.toString(),
                label = "User Scopes",
                icon = "bi-people",
                color = COLOR_USER,
            )
        }
    }
}

@Composable
private fun MemoryStatCard(value: String, label: String, icon: String, color: String) {
    Div({
        classes(DashboardStyles.statCard)
    }) {
        Div({
            classes(DashboardStyles.statIcon)
            style {
                backgroundColor(Color("${color}15"))
                color(Color(color))
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
                Text(label)
            }
        }
    }
}

@Composable
private fun ScopeBrowser(
    scopes: List<MemoryScopeInfo>,
    selectedScope: MemoryScopeInfo?,
    onSelectScope: (MemoryScopeInfo) -> Unit,
    isCompact: Boolean,
) {
    val globalScopes = scopes.filter { it.type == "global" }
    val chatScopes = scopes.filter { it.type == "chat" }
    val userScopes = scopes.filter { it.type == "user" }

    Div({
        classes(DashboardStyles.card)
        style {
            if (isCompact) {
                width(100.percent)
            } else {
                width(280.px)
            }
            flexShrink(0)
            overflow("hidden")
        }
    }) {
        // Panel header
        Div({
            style {
                padding(16.px, 20.px)
                property("border-bottom", "1px solid $COLOR_BORDER")
            }
        }) {
            Span({
                style {
                    fontSize(13.px)
                    fontWeight("600")
                    color(Color(COLOR_TEXT_MUTED))
                    property("text-transform", "uppercase")
                    letterSpacing(0.5.px)
                }
            }) {
                Text("Scopes")
            }
        }

        // Scope groups
        Div({
            style {
                if (!isCompact) {
                    maxHeight(500.px)
                    overflow("auto")
                }
            }
        }) {
            if (globalScopes.isNotEmpty()) {
                ScopeGroup(
                    label = "Global",
                    icon = "bi-globe2",
                    color = COLOR_GLOBAL,
                    scopes = globalScopes,
                    selectedScope = selectedScope,
                    onSelectScope = onSelectScope,
                )
            }
            if (chatScopes.isNotEmpty()) {
                ScopeGroup(
                    label = "Chats",
                    icon = "bi-chat-dots",
                    color = COLOR_CHAT,
                    scopes = chatScopes,
                    selectedScope = selectedScope,
                    onSelectScope = onSelectScope,
                )
            }
            if (userScopes.isNotEmpty()) {
                ScopeGroup(
                    label = "Users",
                    icon = "bi-people",
                    color = COLOR_USER,
                    scopes = userScopes,
                    selectedScope = selectedScope,
                    onSelectScope = onSelectScope,
                )
            }
        }
    }
}

@Composable
private fun ScopeGroup(
    label: String,
    icon: String,
    color: String,
    scopes: List<MemoryScopeInfo>,
    selectedScope: MemoryScopeInfo?,
    onSelectScope: (MemoryScopeInfo) -> Unit,
) {
    Div({
        style {
            property("border-bottom", "1px solid $COLOR_BORDER")
        }
    }) {
        // Group header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(8.px)
                padding(12.px, 20.px, 8.px)
            }
        }) {
            I({
                classes("bi", icon)
                style {
                    fontSize(12.px)
                    color(Color(color))
                }
            })
            Span({
                style {
                    fontSize(11.px)
                    fontWeight("600")
                    color(Color(COLOR_TEXT_DIM))
                    property("text-transform", "uppercase")
                    letterSpacing(0.5.px)
                }
            }) {
                Text(label)
            }
            Span({
                style {
                    fontSize(11.px)
                    color(Color(COLOR_TEXT_DIM))
                }
            }) {
                Text("(${scopes.size})")
            }
        }

        // Scope items
        Div({
            style {
                padding(0.px, 8.px, 8.px)
            }
        }) {
            scopes.forEach { scope ->
                val isSelected = selectedScope == scope
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        justifyContent(JustifyContent.SpaceBetween)
                        padding(10.px, 12.px)
                        borderRadius(8.px)
                        cursor("pointer")
                        property("transition", "all 0.15s ease")
                        if (isSelected) {
                            backgroundColor(Color("${color}15"))
                        }
                    }
                    onClick { onSelectScope(scope) }
                }) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            gap(8.px)
                            minWidth(0.px)
                            flex(1)
                        }
                    }) {
                        // Accent dot
                        Div({
                            style {
                                width(6.px)
                                height(6.px)
                                borderRadius(50.percent)
                                flexShrink(0)
                                backgroundColor(
                                    if (isSelected) Color(color)
                                    else Color(COLOR_TEXT_DIM)
                                )
                            }
                        })
                        Span({
                            style {
                                fontSize(13.px)
                                fontWeight(if (isSelected) "600" else "400")
                                color(
                                    if (isSelected) Color(COLOR_TEXT)
                                    else Color(COLOR_TEXT_MUTED)
                                )
                                property("white-space", "nowrap")
                                overflow("hidden")
                                property("text-overflow", "ellipsis")
                            }
                        }) {
                            Text(scope.label)
                        }
                    }
                    // Fact count badge
                    Span({
                        style {
                            fontSize(11.px)
                            fontWeight("600")
                            padding(2.px, 8.px)
                            borderRadius(10.px)
                            flexShrink(0)
                            if (isSelected) {
                                backgroundColor(Color("${color}25"))
                                color(Color(color))
                            } else {
                                backgroundColor(Color("#27273a"))
                                color(Color(COLOR_TEXT_DIM))
                            }
                        }
                    }) {
                        Text(scope.factCount.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun FactsPanel(
    scope: MemoryScopeInfo,
    factsState: DataState<MemoryResponse>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClear: () -> Unit,
    onFactUpdated: () -> Unit,
    isCompact: Boolean,
) {
    val accentColor = when (scope.type) {
        "global" -> COLOR_GLOBAL
        "chat" -> COLOR_CHAT
        "user" -> COLOR_USER
        else -> COLOR_GLOBAL
    }

    val scopeIcon = when (scope.type) {
        "global" -> "bi-globe2"
        "chat" -> "bi-chat-dots"
        "user" -> "bi-person"
        else -> "bi-journal"
    }

    Div({
        style {
            minWidth(0.px)
            if (isCompact) {
                width(100.percent)
            } else {
                flex(1)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                minHeight(0.px)
            }
        }
    }) {
        Div({
            classes(DashboardStyles.card)
            style {
                if (!isCompact) {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    flex(1)
                    minHeight(0.px)
                    overflow("hidden")
                }
            }
        }) {
            // Panel header
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                    padding(16.px, 24.px)
                    property("border-bottom", "1px solid $COLOR_BORDER")
                }
            }) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        gap(12.px)
                    }
                }) {
                    // Scope type badge
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            justifyContent(JustifyContent.Center)
                            width(32.px)
                            height(32.px)
                            borderRadius(8.px)
                            backgroundColor(Color("${accentColor}15"))
                            color(Color(accentColor))
                            fontSize(14.px)
                        }
                    }) {
                        I({ classes("bi", scopeIcon) })
                    }
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            flexDirection(FlexDirection.Column)
                            gap(2.px)
                        }
                    }) {
                        Span({
                            style {
                                fontSize(15.px)
                                fontWeight("600")
                                color(Color(COLOR_TEXT))
                            }
                        }) {
                            Text(scope.label)
                        }
                        Span({
                            style {
                                fontSize(12.px)
                                color(Color(COLOR_TEXT_MUTED))
                            }
                        }) {
                            Text("${scope.type} scope")
                        }
                    }
                }

                // Actions
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        gap(8.px)
                    }
                }) {
                    Button({
                        classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonDanger)
                        onClick { onClear() }
                    }) {
                        I({ classes("bi", "bi-trash") })
                        Text("Clear")
                    }
                }
            }

            // Search bar
            Div({
                style {
                    padding(12.px, 24.px)
                    property("border-bottom", "1px solid $COLOR_BORDER")
                }
            }) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        gap(10.px)
                        padding(8.px, 14.px)
                        backgroundColor(Color(COLOR_BACKGROUND))
                        borderRadius(8.px)
                        border(1.px, LineStyle.Solid, Color(COLOR_BORDER))
                    }
                }) {
                    I({
                        classes("bi", "bi-search")
                        style {
                            fontSize(14.px)
                            color(Color(COLOR_TEXT_DIM))
                        }
                    })
                    Input(type = InputType.Text) {
                        placeholder("Filter facts...")
                        value(searchQuery)
                        onInput { onSearchChange(it.value) }
                        style {
                            property("all", "unset")
                            width(100.percent)
                            fontSize(14.px)
                            color(Color(COLOR_TEXT))
                        }
                    }
                    if (searchQuery.isNotEmpty()) {
                        Span({
                            style {
                                cursor("pointer")
                                color(Color(COLOR_TEXT_DIM))
                                fontSize(14.px)
                            }
                            onClick { onSearchChange("") }
                        }) {
                            I({ classes("bi", "bi-x-lg") })
                        }
                    }
                }
            }

            // Facts content
            when (factsState) {
                is DataState.Loading -> {
                    Div({
                        style {
                            padding(48.px)
                            textAlign("center")
                        }
                    }) {
                        I({
                            classes("bi", "bi-arrow-repeat")
                            style {
                                fontSize(24.px)
                                color(Color(accentColor))
                                property("animation", "spin 1s linear infinite")
                            }
                        })
                        P({
                            style {
                                color(Color(COLOR_TEXT_MUTED))
                                marginTop(12.px)
                            }
                        }) {
                            Text("Loading facts...")
                        }
                    }
                }
                is DataState.Error -> {
                    Div({
                        style { padding(24.px) }
                    }) {
                        ErrorCard(factsState.message)
                    }
                }
                is DataState.Success -> {
                    val allFacts = factsState.data.facts
                    val filteredFacts = if (searchQuery.isBlank()) allFacts
                    else allFacts.filter { fact ->
                        fact.content.contains(searchQuery, ignoreCase = true) ||
                            fact.source?.contains(searchQuery, ignoreCase = true) == true
                    }

                    if (allFacts.isEmpty()) {
                        Div({
                            style {
                                padding(48.px)
                                textAlign("center")
                            }
                        }) {
                            I({
                                classes("bi", "bi-journal-bookmark")
                                style {
                                    fontSize(32.px)
                                    color(Color(COLOR_TEXT_DIM))
                                }
                            })
                            P({
                                style {
                                    color(Color(COLOR_TEXT_MUTED))
                                    marginTop(16.px)
                                }
                            }) {
                                Text("No facts in this scope yet")
                            }
                        }
                    } else {
                        // Fact count + filter info
                        Div({
                            style {
                                padding(12.px, 24.px)
                                fontSize(12.px)
                                color(Color(COLOR_TEXT_DIM))
                                property("border-bottom", "1px solid $COLOR_BORDER")
                            }
                        }) {
                            if (searchQuery.isNotBlank()) {
                                Text("${filteredFacts.size} of ${allFacts.size} facts matching ")
                                Code({
                                    style { color(Color(accentColor)) }
                                }) {
                                    Text("\"$searchQuery\"")
                                }
                            } else {
                                Text("${allFacts.size} fact${if (allFacts.size != 1) "s" else ""}")
                            }
                        }

                        if (filteredFacts.isEmpty()) {
                            Div({
                                style {
                                    padding(32.px)
                                    textAlign("center")
                                }
                            }) {
                                I({
                                    classes("bi", "bi-search")
                                    style {
                                        fontSize(24.px)
                                        color(Color(COLOR_TEXT_DIM))
                                    }
                                })
                                P({
                                    style {
                                        color(Color(COLOR_TEXT_MUTED))
                                        marginTop(12.px)
                                        fontSize(13.px)
                                    }
                                }) {
                                    Text("No facts match your filter")
                                }
                            }
                        } else {
                            // Facts list
                            Div({
                                style {
                                    padding(16.px, 24.px)
                                    display(DisplayStyle.Flex)
                                    flexDirection(FlexDirection.Column)
                                    gap(10.px)
                                    if (!isCompact) {
                                        flex(1)
                                        minHeight(0.px)
                                        overflow("auto")
                                    }
                                }
                            }) {
                                allFacts.forEachIndexed { index, fact ->
                                    if (fact in filteredFacts) {
                                        FactCard(
                                            fact = fact,
                                            accentColor = accentColor,
                                            index = index,
                                            scope = scope,
                                            onFactUpdated = onFactUpdated,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactCard(
    fact: FactInfo,
    accentColor: String,
    index: Int,
    scope: MemoryScopeInfo,
    onFactUpdated: () -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember(fact.content) { mutableStateOf(fact.content) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    val factEndpoint = "${getApiBaseUrl()}/memory/${scope.type}/${scope.id}/facts/$index"

    LaunchedEffect(isSaving) {
        if (!isSaving) return@LaunchedEffect
        try {
            apiClient.put(factEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(UpdateFactRequest(editContent))
            }
            isEditing = false
            isSaving = false
            onFactUpdated()
        } catch (_: Exception) {
            isSaving = false
        }
    }

    LaunchedEffect(isDeleting, fact) {
        if (!isDeleting) return@LaunchedEffect
        try {
            apiClient.delete(factEndpoint)
            isDeleting = false
            onFactUpdated()
        } catch (_: Exception) {
            isDeleting = false
        }
    }

    Div({
        style {
            padding(14.px, 16.px)
            backgroundColor(Color(COLOR_BACKGROUND))
            borderRadius(8.px)
            property("border-left", "3px solid $accentColor")
            property("transition", "all 0.15s ease")
            property("position", "relative")
        }
    }) {
        when {
            showDeleteConfirm -> {
                // Inline delete confirmation
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        justifyContent(JustifyContent.SpaceBetween)
                        gap(12.px)
                    }
                }) {
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color(COLOR_TEXT_MUTED))
                        }
                    }) {
                        Text("Delete this fact?")
                    }
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            gap(8.px)
                        }
                    }) {
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                            onClick { showDeleteConfirm = false }
                            if (isDeleting) {
                                attr("disabled", "true")
                            }
                        }) {
                            Text("Cancel")
                        }
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonDanger)
                            onClick { isDeleting = true }
                            if (isDeleting) {
                                attr("disabled", "true")
                                style { property("opacity", "0.6") }
                            }
                        }) {
                            if (isDeleting) {
                                I({
                                    classes("bi", "bi-arrow-repeat")
                                    style { property("animation", "spin 1s linear infinite") }
                                })
                            } else {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
            isEditing -> {
                // Edit mode
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        gap(10.px)
                    }
                }) {
                    Input(type = InputType.Text) {
                        value(editContent)
                        onInput { editContent = it.value }
                        style {
                            width(100.percent)
                            padding(8.px, 12.px)
                            fontSize(14.px)
                            color(Color(COLOR_TEXT))
                            backgroundColor(Color(COLOR_SURFACE))
                            borderRadius(6.px)
                            border(1.px, LineStyle.Solid, Color(COLOR_BORDER))
                            property("outline", "none")
                            property("box-sizing", "border-box")
                        }
                    }
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            justifyContent(JustifyContent.FlexEnd)
                            gap(8.px)
                        }
                    }) {
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                            onClick {
                                isEditing = false
                                editContent = fact.content
                            }
                            if (isSaving) {
                                attr("disabled", "true")
                            }
                        }) {
                            Text("Cancel")
                        }
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonSmall)
                            style {
                                backgroundColor(Color(accentColor))
                                color(Color("#fff"))
                            }
                            onClick { isSaving = true }
                            if (isSaving || editContent.isBlank() || editContent == fact.content) {
                                attr("disabled", "true")
                                if (isSaving) {
                                    style { property("opacity", "0.6") }
                                }
                            }
                        }) {
                            if (isSaving) {
                                I({
                                    classes("bi", "bi-arrow-repeat")
                                    style { property("animation", "spin 1s linear infinite") }
                                })
                            } else {
                                Text("Save")
                            }
                        }
                    }
                }
            }
            else -> {
                // Normal view
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.SpaceBetween)
                        alignItems(AlignItems.FlexStart)
                    }
                }) {
                    P({
                        style {
                            fontSize(14.px)
                            color(Color(COLOR_TEXT))
                            marginTop(0.px)
                            marginBottom(8.px)
                            lineHeight("1.6")
                            flex(1)
                            minWidth(0.px)
                        }
                    }) {
                        Text(fact.content)
                    }
                    // Action buttons
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            gap(4.px)
                            flexShrink(0)
                            marginLeft(8.px)
                        }
                    }) {
                        // Edit button
                        Span({
                            style {
                                cursor("pointer")
                                color(Color(COLOR_TEXT_DIM))
                                fontSize(13.px)
                                padding(4.px)
                                borderRadius(4.px)
                                property("transition", "color 0.15s ease")
                            }
                            onClick { isEditing = true }
                        }) {
                            I({ classes("bi", "bi-pencil") })
                        }
                        // Delete button
                        Span({
                            style {
                                cursor("pointer")
                                color(Color(COLOR_TEXT_DIM))
                                fontSize(13.px)
                                padding(4.px)
                                borderRadius(4.px)
                                property("transition", "color 0.15s ease")
                            }
                            onClick { showDeleteConfirm = true }
                        }) {
                            I({ classes("bi", "bi-trash") })
                        }
                    }
                }
                // Metadata row
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        gap(16.px)
                        fontSize(12.px)
                        color(Color(COLOR_TEXT_DIM))
                    }
                }) {
                    fact.source?.let { source ->
                        Span({
                            style {
                                display(DisplayStyle.Flex)
                                property("display", "inline-flex")
                                alignItems(AlignItems.Center)
                                gap(5.px)
                            }
                        }) {
                            I({
                                classes("bi", "bi-tag")
                                style { fontSize(11.px) }
                            })
                            Text(source)
                        }
                    }
                    Span({
                        style {
                            display(DisplayStyle.Flex)
                            property("display", "inline-flex")
                            alignItems(AlignItems.Center)
                            gap(5.px)
                        }
                    }) {
                        I({
                            classes("bi", "bi-clock")
                            style { fontSize(11.px) }
                        })
                        val prefix = if (fact.updatedAt != null) "created " else ""
                        Text("$prefix${formatInstant(fact.createdAt)}")
                    }
                    fact.updatedAt?.let { updated ->
                        Span({
                            style {
                                display(DisplayStyle.Flex)
                                property("display", "inline-flex")
                                alignItems(AlignItems.Center)
                                gap(5.px)
                                color(Color(COLOR_USER))
                            }
                        }) {
                            I({
                                classes("bi", "bi-pencil")
                                style { fontSize(11.px) }
                            })
                            Text("updated ${formatInstant(updated)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearConfirmDialog(
    scope: MemoryScopeInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
) {
    var isClearing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val accentColor = when (scope.type) {
        "global" -> COLOR_GLOBAL
        "chat" -> COLOR_CHAT
        "user" -> COLOR_USER
        else -> COLOR_GLOBAL
    }

    LaunchedEffect(isClearing) {
        if (!isClearing) return@LaunchedEffect
        try {
            val endpoint = when (scope.type) {
                "global" -> "/memory/global"
                "chat" -> "/memory/chat/${scope.id}"
                "user" -> "/memory/user/${scope.id}"
                else -> return@LaunchedEffect
            }
            apiClient.delete("${getApiBaseUrl()}$endpoint")
            onCleared()
            onConfirm()
        } catch (e: Exception) {
            error = e.message ?: "Failed to clear memory"
            isClearing = false
        }
    }

    // Overlay
    Div({
        classes(DashboardStyles.modalOverlay)
        onClick { if (!isClearing) onDismiss() }
    }) {
        // Modal
        Div({
            classes(DashboardStyles.modal)
            onClick { it.stopPropagation() }
            style {
                maxWidth(420.px)
            }
        }) {
            // Header
            Div({
                classes(DashboardStyles.modalHeader)
            }) {
                H3({
                    classes(DashboardStyles.modalTitle)
                }) {
                    Text("Clear Memory")
                }
                Button({
                    classes(DashboardStyles.modalCloseButton)
                    onClick { if (!isClearing) onDismiss() }
                }) {
                    I({ classes("bi", "bi-x-lg") })
                }
            }

            // Body
            Div({
                classes(DashboardStyles.modalBody)
            }) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        gap(12.px)
                        padding(16.px)
                        backgroundColor(Color("${COLOR_ERROR}10"))
                        borderRadius(8.px)
                        marginBottom(16.px)
                    }
                }) {
                    I({
                        classes("bi", "bi-exclamation-triangle")
                        style {
                            fontSize(20.px)
                            color(Color(COLOR_ERROR))
                        }
                    })
                    Div {
                        P({
                            style {
                                fontSize(14.px)
                                color(Color(COLOR_TEXT))
                                marginTop(0.px)
                                marginBottom(4.px)
                                fontWeight("500")
                            }
                        }) {
                            Text("This action cannot be undone.")
                        }
                        P({
                            style {
                                fontSize(13.px)
                                color(Color(COLOR_TEXT_MUTED))
                                property("margin", "0")
                            }
                        }) {
                            Text("All ${scope.factCount} fact${if (scope.factCount != 1) "s" else ""} in ")
                            Code({
                                style { color(Color(accentColor)) }
                            }) {
                                Text(scope.label)
                            }
                            Text(" will be permanently deleted.")
                        }
                    }
                }

                if (error != null) {
                    Div({
                        classes(DashboardStyles.errorMessage)
                    }) {
                        Text(error!!)
                    }
                }
            }

            // Footer
            Div({
                classes(DashboardStyles.modalFooter)
            }) {
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonOutline)
                    onClick { if (!isClearing) onDismiss() }
                }) {
                    Text("Cancel")
                }
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonDanger)
                    onClick { isClearing = true }
                    if (isClearing) {
                        attr("disabled", "true")
                        style { property("opacity", "0.6") }
                    }
                }) {
                    if (isClearing) {
                        I({
                            classes("bi", "bi-arrow-repeat")
                            style {
                                property("animation", "spin 1s linear infinite")
                                marginRight(4.px)
                            }
                        })
                        Text("Clearing...")
                    } else {
                        I({ classes("bi", "bi-trash") })
                        Text("Clear All Facts")
                    }
                }
            }
        }
    }
}

private fun formatInstant(instant: Instant): String {
    val epochMillis = instant.toEpochMilliseconds()
    val now = Clock.System.now().toEpochMilliseconds()
    val diffMs = now - epochMillis
    val diffSec = diffMs / 1000L
    val diffMin = diffSec / 60L
    val diffHour = diffMin / 60L
    val diffDay = diffHour / 24L

    return when {
        diffDay > 0L -> "${diffDay}d ago"
        diffHour > 0L -> "${diffHour}h ago"
        diffMin > 0L -> "${diffMin}m ago"
        else -> "just now"
    }
}
