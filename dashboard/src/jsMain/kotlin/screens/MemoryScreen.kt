package screens

import DashboardStyles
import apiClient
import androidx.compose.runtime.*
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.datetime.Instant
import org.drewcarlson.fraggle.models.MemoryResponse
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

sealed class MemoryState {
    data object Loading : MemoryState()
    data class Success(val memory: MemoryResponse) : MemoryState()
    data class Error(val message: String) : MemoryState()
}

enum class MemoryScope(val label: String, val endpoint: String) {
    GLOBAL("Global", "/memory/global"),
}

@Composable
fun MemoryScreen() {
    var selectedScope by remember { mutableStateOf(MemoryScope.GLOBAL) }
    var state by remember { mutableStateOf<MemoryState>(MemoryState.Loading) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Fetch memory for selected scope
    LaunchedEffect(selectedScope, refreshTrigger) {
        state = MemoryState.Loading
        state = try {
            val memory = apiClient.get("${getApiBaseUrl()}${selectedScope.endpoint}").body<MemoryResponse>()
            MemoryState.Success(memory)
        } catch (e: Exception) {
            MemoryState.Error(e.message ?: "Failed to load memory")
        }
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
            H2({
                classes(DashboardStyles.sectionTitle)
                style { property("margin", "0") }
            }) {
                Text("Memory Store")
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
                    onClick { refreshTrigger++ }
                }) {
                    I({ classes("bi", "bi-arrow-repeat") })
                }
            }
        }

        when (val currentState = state) {
            is MemoryState.Loading -> {
                LoadingCard("Loading memory...")
            }
            is MemoryState.Error -> {
                ErrorCard(currentState.message)
            }
            is MemoryState.Success -> {
                val memory = currentState.memory
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
                                onClick {
                                    // Clear memory (would need confirmation)
                                }
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
