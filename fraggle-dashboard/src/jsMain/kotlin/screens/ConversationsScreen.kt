package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.Composable
import apiClient
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.drewcarlson.fraggle.models.ConversationSummary
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader
import kotlin.time.Instant

@Composable
fun ConversationsScreen(wsService: WebSocketService) {
    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Conversations),
    ) {
        apiClient.get("${getApiBaseUrl()}/conversations").body<List<ConversationSummary>>()
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
                    Text("Active Conversations")
                }
                // Show subtle refresh indicator
                DataStateLoadingSpinner(state)
            }
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                onClick { refresh() }
            }) {
                I({ classes("bi", "bi-arrow-repeat") })
                Text("Refresh")
            }
        }

        when (state) {
            is DataState.Loading -> {
                LoadingCard("Loading conversations...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val conversations = state.data
                if (conversations.isEmpty()) {
                    EmptyCard("No active conversations", "bi-chat-dots")
                } else {
                    Div({
                        classes(DashboardStyles.card)
                    }) {
                        Table({
                            classes(DashboardStyles.table)
                        }) {
                            Thead {
                                Tr {
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Chat ID") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Messages") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Last Activity") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Actions") }
                                }
                            }
                            Tbody {
                                conversations.forEach { conv ->
                                    Tr({
                                        classes(DashboardStyles.tableRow)
                                    }) {
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Code({
                                                style {
                                                    fontSize(12.px)
                                                    backgroundColor(Color("#27273a"))
                                                    padding(4.px, 8.px)
                                                    borderRadius(4.px)
                                                }
                                            }) {
                                                Text(conv.chatId.take(20) + if (conv.chatId.length > 20) "..." else "")
                                            }
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Text(conv.messageCount.toString())
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            conv.lastMessageAt?.let { instant ->
                                                Text(formatInstant(instant))
                                            } ?: Text("-")
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Div({
                                                style {
                                                    display(DisplayStyle.Flex)
                                                    gap(8.px)
                                                }
                                            }) {
                                                Button({
                                                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                                                }) {
                                                    I({ classes("bi", "bi-eye") })
                                                }
                                                Button({
                                                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonDanger)
                                                }) {
                                                    I({ classes("bi", "bi-trash") })
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
