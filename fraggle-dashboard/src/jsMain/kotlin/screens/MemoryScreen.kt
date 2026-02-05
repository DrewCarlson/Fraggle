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
import org.drewcarlson.fraggle.models.MemoryResponse
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader
import kotlin.time.Instant

enum class MemoryScope(val label: String, val endpoint: String) {
    GLOBAL("Global", "/memory/global"),
}

@Composable
fun MemoryScreen(wsService: WebSocketService) {
    var selectedScope by remember { mutableStateOf(MemoryScope.GLOBAL) }

    val (state, refresh) = rememberRefreshableDataLoader(
        selectedScope,
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Memory),
    ) {
        apiClient.get("${getApiBaseUrl()}${selectedScope.endpoint}").body<MemoryResponse>()
    }

    Section({
        classes(DashboardStyles.section)
    }) {
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
                DataStateLoadingSpinner(state)
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    gap(8.px)
                }
            }) {
                // Scope selector
                MemoryScope.entries.forEach { scope ->
                    Button({
                        classes(DashboardStyles.button, DashboardStyles.buttonSmall)
                        if (selectedScope == scope) {
                            classes(DashboardStyles.buttonPrimary)
                        } else {
                            classes(DashboardStyles.buttonOutline)
                        }
                        onClick { selectedScope = scope }
                    }) {
                        Text(scope.label)
                    }
                }
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                    onClick { refresh() }
                }) {
                    I({ classes("bi", "bi-arrow-repeat") })
                }
            }
        }

        when (state) {
            is DataState.Loading -> {
                LoadingCard("Loading memory...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val memory = state.data
                if (memory.facts.isEmpty()) {
                    EmptyCard("No facts stored in ${memory.scope}", "bi-journal-bookmark")
                } else {
                    Div({
                        classes(DashboardStyles.card)
                    }) {
                        // Header
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(16.px, 24.px)
                                property("border-bottom", "1px solid #27273a")
                            }
                        }) {
                            Span({
                                style {
                                    fontSize(14.px)
                                    color(Color("#71717a"))
                                }
                            }) {
                                Text("${memory.facts.size} facts in scope: ")
                                Code({
                                    style {
                                        color(Color("#6366f1"))
                                    }
                                }) {
                                    Text(memory.scope)
                                }
                            }
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonDanger)
                            }) {
                                I({ classes("bi", "bi-trash") })
                                Text("Clear All")
                            }
                        }

                        // Facts list
                        Div({
                            style {
                                padding(16.px, 24.px)
                                display(DisplayStyle.Flex)
                                flexDirection(FlexDirection.Column)
                                gap(12.px)
                            }
                        }) {
                            memory.facts.forEach { fact ->
                                Div({
                                    style {
                                        padding(16.px)
                                        backgroundColor(Color("#0f0f1a"))
                                        borderRadius(8.px)
                                        property("border-left", "3px solid #6366f1")
                                    }
                                }) {
                                    P({
                                        style {
                                            fontSize(14.px)
                                            color(Color("#e4e4e7"))
                                            marginTop(0.px)
                                            marginBottom(8.px)
                                            lineHeight("1.6")
                                        }
                                    }) {
                                        Text(fact.content)
                                    }
                                    Div({
                                        style {
                                            display(DisplayStyle.Flex)
                                            gap(12.px)
                                            fontSize(12.px)
                                            color(Color("#71717a"))
                                        }
                                    }) {
                                        fact.source?.let { source ->
                                            Span {
                                                I({
                                                    classes("bi", "bi-person")
                                                    style { marginRight(4.px) }
                                                })
                                                Text(source)
                                            }
                                        }
                                        Span {
                                            I({
                                                classes("bi", "bi-clock")
                                                style { marginRight(4.px) }
                                            })
                                            Text(formatInstant(fact.createdAt))
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
}

private fun formatInstant(instant: Instant): String {
    val epochMillis = instant.toEpochMilliseconds()
    val now = kotlinx.browser.window.asDynamic().Date.now() as Double
    val diffMs = (now - epochMillis).toLong()
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
