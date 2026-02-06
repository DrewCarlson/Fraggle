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
import org.drewcarlson.fraggle.models.ChatDetail
import org.drewcarlson.fraggle.models.ChatMessageRecord
import org.drewcarlson.fraggle.models.ChatSummary
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader
import kotlin.time.Instant

@Composable
fun ConversationsScreen(wsService: WebSocketService) {
    var selectedChatId by remember { mutableStateOf<Long?>(null) }

    if (selectedChatId != null) {
        ChatDetailView(
            chatId = selectedChatId!!,
            onBack = { selectedChatId = null },
        )
    } else {
        ChatListView(
            wsService = wsService,
            onSelectChat = { selectedChatId = it },
        )
    }
}

@Composable
private fun ChatListView(
    wsService: WebSocketService,
    onSelectChat: (Long) -> Unit,
) {
    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Chats),
    ) {
        apiClient.get("${getApiBaseUrl()}/chats").body<List<ChatSummary>>()
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
                    Text("Chat History")
                }
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
                LoadingCard("Loading chats...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val chats = state.data
                if (chats.isEmpty()) {
                    EmptyCard("No chat history yet", "bi-chat-dots")
                } else {
                    Div({
                        classes(DashboardStyles.card)
                    }) {
                        Table({
                            classes(DashboardStyles.table)
                        }) {
                            Thead {
                                Tr {
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Platform") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Chat") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Messages") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Last Active") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Actions") }
                                }
                            }
                            Tbody {
                                chats.forEach { chat ->
                                    Tr({
                                        classes(DashboardStyles.tableRow)
                                        style { cursor("pointer") }
                                        onClick { onSelectChat(chat.id) }
                                    }) {
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            PlatformBadge(chat.platform)
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Div({
                                                style {
                                                    display(DisplayStyle.Flex)
                                                    flexDirection(FlexDirection.Column)
                                                    gap(2.px)
                                                }
                                            }) {
                                                if (chat.name != null) {
                                                    Span({
                                                        style { fontWeight("600") }
                                                    }) {
                                                        Text(chat.name!!)
                                                    }
                                                }
                                                Code({
                                                    style {
                                                        fontSize(12.px)
                                                        backgroundColor(Color("#27273a"))
                                                        padding(2.px, 6.px)
                                                        borderRadius(4.px)
                                                        color(Color("#71717a"))
                                                    }
                                                }) {
                                                    val displayId = chat.externalId.take(30) +
                                                        if (chat.externalId.length > 30) "..." else ""
                                                    Text(displayId)
                                                }
                                            }
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Text(chat.messageCount.toString())
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Text(formatInstant(chat.lastActiveAt))
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Button({
                                                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                                                onClick {
                                                    it.stopPropagation()
                                                    onSelectChat(chat.id)
                                                }
                                            }) {
                                                I({ classes("bi", "bi-eye") })
                                                Text("View")
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

@Composable
private fun ChatDetailView(
    chatId: Long,
    onBack: () -> Unit,
) {
    val (detailState, refreshDetail) = rememberRefreshableDataLoader(chatId) {
        apiClient.get("${getApiBaseUrl()}/chats/$chatId").body<ChatDetail>()
    }

    val (messagesState, refreshMessages) = rememberRefreshableDataLoader(chatId) {
        apiClient.get("${getApiBaseUrl()}/chats/$chatId/messages?limit=100").body<List<ChatMessageRecord>>()
    }

    Section({
        classes(DashboardStyles.section)
    }) {
        // Header with back button
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(12.px)
                marginBottom(16.px)
            }
        }) {
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                onClick { onBack() }
            }) {
                I({ classes("bi", "bi-arrow-left") })
                Text("Back")
            }

            H2({
                classes(DashboardStyles.sectionTitle)
                style { property("margin", "0") }
            }) {
                Text("Chat Detail")
            }

            DataStateLoadingSpinner(detailState)

            Div({ style { flex(1) } })

            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                onClick {
                    refreshDetail()
                    refreshMessages()
                }
            }) {
                I({ classes("bi", "bi-arrow-repeat") })
                Text("Refresh")
            }
        }

        // Chat info card
        when (detailState) {
            is DataState.Loading -> {
                LoadingCard("Loading chat details...")
            }
            is DataState.Error -> {
                ErrorCard(detailState.message)
            }
            is DataState.Success -> {
                val chat = detailState.data
                ChatInfoCard(chat)
            }
        }

        // Message history
        Div({ style { marginTop(24.px) } }) {
            H3({
                classes(DashboardStyles.sectionTitle)
            }) {
                Text("Message History")
            }

            when (messagesState) {
                is DataState.Loading -> {
                    LoadingCard("Loading messages...")
                }
                is DataState.Error -> {
                    ErrorCard(messagesState.message)
                }
                is DataState.Success -> {
                    val messages = messagesState.data
                    if (messages.isEmpty()) {
                        EmptyCard("No messages recorded", "bi-chat-left")
                    } else {
                        Div({
                            classes(DashboardStyles.card)
                        }) {
                            Table({
                                classes(DashboardStyles.table)
                            }) {
                                Thead {
                                    Tr {
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Direction") }
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Sender") }
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Type") }
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Time") }
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Processing") }
                                    }
                                }
                                Tbody {
                                    messages.forEach { msg ->
                                        Tr({
                                            classes(DashboardStyles.tableRow)
                                        }) {
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                DirectionBadge(msg.direction)
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                Div({
                                                    style {
                                                        display(DisplayStyle.Flex)
                                                        alignItems(AlignItems.Center)
                                                        gap(6.px)
                                                    }
                                                }) {
                                                    if (msg.senderIsBot) {
                                                        I({
                                                            classes("bi", "bi-robot")
                                                            style {
                                                                fontSize(14.px)
                                                                color(Color("#6366f1"))
                                                            }
                                                        })
                                                    }
                                                    Span {
                                                        Text(msg.senderName ?: msg.senderId)
                                                    }
                                                }
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                ContentTypeBadge(msg.contentType)
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                Text(formatInstant(msg.timestamp))
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                msg.processingDuration?.let { duration ->
                                                    val seconds = duration.inWholeMilliseconds / 1000.0
                                                    Span({
                                                        style {
                                                            color(Color("#71717a"))
                                                            fontSize(13.px)
                                                        }
                                                    }) {
                                                        Text("${seconds.asDynamic().toFixed(1)}s")
                                                    }
                                                } ?: Text("-")
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

@Composable
private fun ChatInfoCard(chat: ChatDetail) {
    Div({
        classes(DashboardStyles.card)
        style { padding(24.px) }
    }) {
        // Chat header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(12.px)
                marginBottom(20.px)
            }
        }) {
            PlatformBadge(chat.platform)
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    gap(2.px)
                }
            }) {
                if (chat.name != null) {
                    Span({
                        style {
                            fontSize(16.px)
                            fontWeight("600")
                        }
                    }) {
                        Text(chat.name!!)
                    }
                }
                Code({
                    style {
                        fontSize(12.px)
                        color(Color("#71717a"))
                    }
                }) {
                    Text(chat.externalId)
                }
            }
            if (chat.isGroup) {
                Span({
                    style {
                        fontSize(12.px)
                        padding(2.px, 8.px)
                        backgroundColor(Color("#27273a"))
                        borderRadius(4.px)
                        color(Color("#71717a"))
                    }
                }) {
                    Text("Group")
                }
            }
        }

        // Stats grid
        Div({
            style {
                display(DisplayStyle.Grid)
                property("grid-template-columns", "repeat(auto-fit, minmax(140px, 1fr))")
                gap(16.px)
            }
        }) {
            StatBox("Total", chat.stats.totalMessages.toString(), "bi-chat-left-text")
            StatBox("Incoming", chat.stats.incomingMessages.toString(), "bi-arrow-down-left")
            StatBox("Outgoing", chat.stats.outgoingMessages.toString(), "bi-arrow-up-right")
            chat.stats.avgProcessingDuration?.let { duration ->
                val seconds = duration.inWholeMilliseconds / 1000.0
                StatBox("Avg Response", "${seconds.asDynamic().toFixed(1)}s", "bi-clock")
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, icon: String) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            gap(10.px)
            padding(12.px)
            backgroundColor(Color("#0f0f1a"))
            borderRadius(8.px)
        }
    }) {
        I({
            classes("bi", icon)
            style {
                fontSize(16.px)
                color(Color("#6366f1"))
            }
        })
        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(2.px)
            }
        }) {
            Span({
                style {
                    fontSize(18.px)
                    fontWeight("700")
                }
            }) {
                Text(value)
            }
            Span({
                style {
                    fontSize(12.px)
                    color(Color("#71717a"))
                }
            }) {
                Text(label)
            }
        }
    }
}

@Composable
private fun PlatformBadge(platform: String) {
    val (icon, color) = when (platform.lowercase()) {
        "signal" -> "bi-chat-left-text" to "#3a76f0"
        "discord" -> "bi-discord" to "#5865f2"
        else -> "bi-chat" to "#71717a"
    }
    Span({
        style {
            display(DisplayStyle.Flex)
            property("display", "inline-flex")
            alignItems(AlignItems.Center)
            gap(6.px)
            padding(4.px, 10.px)
            backgroundColor(Color("${color}20"))
            borderRadius(6.px)
            fontSize(13.px)
            fontWeight("500")
            color(Color(color))
        }
    }) {
        I({ classes("bi", icon) })
        Text(platform.replaceFirstChar { it.uppercase() })
    }
}

@Composable
private fun DirectionBadge(direction: String) {
    val isIncoming = direction == "INCOMING"
    val (icon, label, color) = if (isIncoming) {
        Triple("bi-arrow-down-left", "In", "#22c55e")
    } else {
        Triple("bi-arrow-up-right", "Out", "#6366f1")
    }
    Span({
        style {
            display(DisplayStyle.Flex)
            property("display", "inline-flex")
            alignItems(AlignItems.Center)
            gap(4.px)
            padding(2.px, 8.px)
            backgroundColor(Color("${color}15"))
            borderRadius(4.px)
            fontSize(12.px)
            fontWeight("500")
            color(Color(color))
        }
    }) {
        I({
            classes("bi", icon)
            style { fontSize(10.px) }
        })
        Text(label)
    }
}

@Composable
private fun ContentTypeBadge(contentType: String) {
    val icon = when (contentType) {
        "TEXT" -> "bi-fonts"
        "IMAGE" -> "bi-image"
        "FILE" -> "bi-file-earmark"
        "AUDIO" -> "bi-mic"
        "STICKER" -> "bi-emoji-smile"
        "REACTION" -> "bi-hand-thumbs-up"
        else -> "bi-question"
    }
    Span({
        style {
            display(DisplayStyle.Flex)
            property("display", "inline-flex")
            alignItems(AlignItems.Center)
            gap(4.px)
            fontSize(13.px)
            color(Color("#71717a"))
        }
    }) {
        I({ classes("bi", icon) })
        Text(contentType.lowercase().replaceFirstChar { it.uppercase() })
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
