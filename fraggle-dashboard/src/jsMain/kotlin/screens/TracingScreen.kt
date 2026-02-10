package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.*
import apiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.drewcarlson.fraggle.models.TraceEventRecord
import org.drewcarlson.fraggle.models.TraceSession
import org.drewcarlson.fraggle.models.TraceSessionDetail
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader
import kotlin.time.Instant

@Composable
fun TracingScreen(wsService: WebSocketService) {
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    if (selectedSessionId != null) {
        SessionDetailView(
            sessionId = selectedSessionId!!,
            wsService = wsService,
            onBack = { selectedSessionId = null },
        )
    } else {
        SessionListView(
            wsService = wsService,
            onSelectSession = { selectedSessionId = it },
        )
    }
}

@Composable
private fun SessionListView(
    wsService: WebSocketService,
    onSelectSession: (String) -> Unit,
) {
    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Tracing),
    ) {
        apiClient.get("tracing/sessions").body<List<TraceSession>>()
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
                    Text("Trace Sessions")
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
                LoadingCard("Loading trace sessions...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val sessions = state.data
                if (sessions.isEmpty()) {
                    EmptyCard("No trace sessions yet. Send a message to generate traces.", "bi-activity")
                } else {
                    Div({
                        classes(DashboardStyles.card)
                    }) {
                        Table({
                            classes(DashboardStyles.table)
                        }) {
                            Thead {
                                Tr {
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Status") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Chat") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Events") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Started") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Duration") }
                                    Th({ classes(DashboardStyles.tableHeader) }) { Text("Actions") }
                                }
                            }
                            Tbody {
                                sessions.forEach { session ->
                                    Tr({
                                        classes(DashboardStyles.tableRow)
                                        style { cursor("pointer") }
                                        onClick { onSelectSession(session.id) }
                                    }) {
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            SessionStatusBadge(session.status)
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Code({
                                                style {
                                                    fontSize(12.px)
                                                    backgroundColor(Color("#27273a"))
                                                    padding(2.px, 6.px)
                                                    borderRadius(4.px)
                                                    color(Color("#71717a"))
                                                }
                                            }) {
                                                val displayId = session.chatId.take(30) +
                                                    if (session.chatId.length > 30) "..." else ""
                                                Text(displayId)
                                            }
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Text(session.eventCount.toString())
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Text(formatTraceInstant(session.startTime))
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Text(formatDuration(session.startTime, session.endTime))
                                        }
                                        Td({ classes(DashboardStyles.tableCell) }) {
                                            Button({
                                                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                                                onClick {
                                                    it.stopPropagation()
                                                    onSelectSession(session.id)
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
private fun SessionDetailView(
    sessionId: String,
    wsService: WebSocketService,
    onBack: () -> Unit,
) {
    val (state, refresh) = rememberRefreshableDataLoader(
        sessionId,
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Tracing),
    ) {
        apiClient.get("tracing/sessions/$sessionId").body<TraceSessionDetail>()
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
                Text("Trace Detail")
            }

            DataStateLoadingSpinner(state)

            Div({ style { flex(1) } })

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
                LoadingCard("Loading trace detail...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val detail = state.data
                val session = detail.session

                // Session info card
                Div({
                    classes(DashboardStyles.card)
                    style { padding(24.px) }
                }) {
                    Div({
                        style {
                            display(DisplayStyle.Grid)
                            property("grid-template-columns", "repeat(auto-fit, minmax(140px, 1fr))")
                            gap(16.px)
                        }
                    }) {
                        TraceStatBox("Status", session.status.replaceFirstChar { it.uppercase() }, statusIcon(session.status))
                        TraceStatBox("Chat", session.chatId.take(20), "bi-chat-dots")
                        TraceStatBox("Events", session.eventCount.toString(), "bi-list-task")
                        TraceStatBox("Duration", formatDuration(session.startTime, session.endTime), "bi-clock")
                    }
                }

                // Events timeline
                Div({ style { marginTop(24.px) } }) {
                    H3({
                        classes(DashboardStyles.sectionTitle)
                    }) {
                        Text("Event Timeline")
                    }

                    if (detail.events.isEmpty()) {
                        EmptyCard("No events recorded", "bi-activity")
                    } else {
                        Div({
                            classes(DashboardStyles.card)
                        }) {
                            Table({
                                classes(DashboardStyles.table)
                            }) {
                                Thead {
                                    Tr {
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Type") }
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Phase") }
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Details") }
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Time") }
                                    }
                                }
                                Tbody {
                                    detail.events.forEach { event ->
                                        Tr({
                                            classes(DashboardStyles.tableRow)
                                        }) {
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                EventTypeBadge(event.eventType)
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                PhaseBadge(event.phase)
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                EventDataSummary(event)
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                Text(formatTraceInstant(event.timestamp))
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
private fun SessionStatusBadge(status: String) {
    val (icon, color) = when (status) {
        "running" -> "bi-play-circle" to "#f59e0b"
        "completed" -> "bi-check-circle" to "#22c55e"
        "error" -> "bi-x-circle" to "#ef4444"
        else -> "bi-question-circle" to "#71717a"
    }
    Span({
        style {
            display(DisplayStyle.Flex)
            property("display", "inline-flex")
            alignItems(AlignItems.Center)
            gap(6.px)
            padding(4.px, 10.px)
            backgroundColor(Color("${color}15"))
            borderRadius(6.px)
            fontSize(13.px)
            fontWeight("500")
            color(Color(color))
        }
    }) {
        I({ classes("bi", icon) })
        Text(status.replaceFirstChar { it.uppercase() })
    }
}

@Composable
private fun EventTypeBadge(eventType: String) {
    val (icon, color) = when (eventType) {
        "agent" -> "bi-robot" to "#6366f1"
        "llm_call" -> "bi-cpu" to "#8b5cf6"
        "tool_call" -> "bi-tools" to "#f59e0b"
        "node" -> "bi-diagram-3" to "#71717a"
        "strategy" -> "bi-signpost-split" to "#06b6d4"
        "llm_streaming" -> "bi-broadcast" to "#8b5cf6"
        "subgraph" -> "bi-diagram-2" to "#71717a"
        else -> "bi-question" to "#71717a"
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
            style { fontSize(11.px) }
        })
        Text(eventType.replace("_", " ").replaceFirstChar { it.uppercase() })
    }
}

@Composable
private fun PhaseBadge(phase: String) {
    val color = when (phase) {
        "starting" -> "#06b6d4"
        "finished" -> "#22c55e"
        "error", "validation_error" -> "#ef4444"
        "closing" -> "#71717a"
        else -> "#71717a"
    }
    Span({
        style {
            fontSize(12.px)
            fontWeight("500")
            color(Color(color))
        }
    }) {
        Text(phase.replace("_", " "))
    }
}

@Composable
private fun EventDataSummary(event: TraceEventRecord) {
    val data = event.data
    if (data.isEmpty()) {
        Span({
            style {
                fontSize(12.px)
                color(Color("#71717a"))
            }
        }) {
            Text("-")
        }
        return
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            gap(2.px)
        }
    }) {
        // Show the most relevant fields first
        val priorityKeys = listOf("toolName", "model", "agentId", "error", "result")
        val shown = mutableSetOf<String>()

        for (key in priorityKeys) {
            val value = data[key] ?: continue
            shown.add(key)
            DataField(key, value)
        }

        // Show remaining fields
        for ((key, value) in data) {
            if (key in shown) continue
            DataField(key, value)
        }
    }
}

@Composable
private fun DataField(key: String, value: String) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.FlexStart)
            gap(6.px)
            fontSize(12.px)
        }
    }) {
        Span({
            style {
                color(Color("#71717a"))
                property("white-space", "nowrap")
            }
        }) {
            Text("$key:")
        }
        Span({
            style {
                color(Color("#e4e4e7"))
                property("word-break", "break-all")
                property("max-width", "400px")
                property("overflow", "hidden")
                property("text-overflow", "ellipsis")
                property("display", "-webkit-box")
                property("-webkit-line-clamp", "2")
                property("-webkit-box-orient", "vertical")
            }
        }) {
            Text(value)
        }
    }
}

@Composable
private fun TraceStatBox(label: String, value: String, icon: String) {
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

private fun statusIcon(status: String): String = when (status) {
    "running" -> "bi-play-circle"
    "completed" -> "bi-check-circle"
    "error" -> "bi-x-circle"
    else -> "bi-question-circle"
}

private fun formatTraceInstant(instant: Instant): String {
    val epochMillis = instant.toEpochMilliseconds()
    val now = kotlinx.browser.window.asDynamic().Date.now() as Double
    val diffMs = (now - epochMillis).toLong()
    val diffSec = diffMs / 1000L
    val diffMin = diffSec / 60L
    val diffHour = diffMin / 60L

    return when {
        diffHour > 0L -> "${diffHour}h ${diffMin % 60}m ago"
        diffMin > 0L -> "${diffMin}m ${diffSec % 60}s ago"
        else -> "${diffSec}s ago"
    }
}

private fun formatDuration(start: Instant, end: Instant?): String {
    if (end == null) return "running..."
    val durationMs = (end - start).inWholeMilliseconds
    val seconds = durationMs / 1000.0
    return if (seconds < 1.0) {
        "${durationMs}ms"
    } else {
        "${seconds.asDynamic().toFixed(1)}s"
    }
}
