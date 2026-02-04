package screens

import DashboardStyles
import apiClient
import androidx.compose.runtime.*
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import org.drewcarlson.fraggle.models.ConversationDetail
import org.drewcarlson.fraggle.models.ConversationSummary
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

sealed class ConversationsState {
    data object Loading : ConversationsState()
    data class Success(val conversations: List<ConversationSummary>) : ConversationsState()
    data class Error(val message: String) : ConversationsState()
}

sealed class ConversationDetailState {
    data object Hidden : ConversationDetailState()
    data object Loading : ConversationDetailState()
    data class Success(val detail: ConversationDetail) : ConversationDetailState()
    data class Error(val message: String) : ConversationDetailState()
}

@Composable
fun ConversationsScreen() {
    var state by remember { mutableStateOf<ConversationsState>(ConversationsState.Loading) }
    var detailState by remember { mutableStateOf<ConversationDetailState>(ConversationDetailState.Hidden) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Fetch conversations
    LaunchedEffect(refreshTrigger) {
        state = ConversationsState.Loading
        state = try {
            val conversations = apiClient.get("${getApiBaseUrl()}/conversations").body<List<ConversationSummary>>()
            ConversationsState.Success(conversations)
        } catch (e: Exception) {
            ConversationsState.Error(e.message ?: "Failed to load conversations")
        }
    }

    // Auto-refresh every 10 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(10000)
            refreshTrigger++
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
                Text("Active Conversations")
            }
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                onClick { refreshTrigger++ }
            }) {
                I({ classes("bi", "bi-arrow-repeat") })
                Text("Refresh")
            }
        }

        when (val currentState = state) {
            is ConversationsState.Loading -> {
                LoadingCard("Loading conversations...")
            }
            is ConversationsState.Error -> {
                ErrorCard(currentState.message)
            }
            is ConversationsState.Success -> {
                if (currentState.conversations.isEmpty()) {
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
                                currentState.conversations.forEach { conv ->
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
                                                    onClick {
                                                        detailState = ConversationDetailState.Loading
                                                        // Load details in effect
                                                    }
                                                }) {
                                                    I({ classes("bi", "bi-eye") })
                                                }
                                                Button({
                                                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonDanger)
                                                    onClick {
                                                        // Clear conversation (implementation would need confirmation)
                                                    }
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
