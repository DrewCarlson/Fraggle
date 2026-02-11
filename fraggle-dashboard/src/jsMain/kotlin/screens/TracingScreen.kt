package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.*
import apiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
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
    var detailEvent by remember { mutableStateOf<TraceEventRecord?>(null) }

    val (state, refresh) = rememberRefreshableDataLoader(
        sessionId,
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Tracing),
    ) {
        apiClient.get("tracing/sessions/$sessionId").body<TraceSessionDetail>()
    }

    // LLM Detail Dialog
    detailEvent?.let { event ->
        LlmDetailDialog(event = event, onClose = { detailEvent = null })
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
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Actions") }
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
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                if (event.eventType == "llm_call" && event.detail != null) {
                                                    Button({
                                                        classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                                                        onClick { detailEvent = event }
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

@Composable
private fun LlmDetailDialog(event: TraceEventRecord, onClose: () -> Unit) {
    val json = try {
        Json.parseToJsonElement(event.detail!!).jsonObject
    } catch (_: Exception) {
        null
    }

    Div({
        classes(DashboardStyles.modalOverlay)
        onClick { e ->
            if (e.target == e.currentTarget) onClose()
        }
    }) {
        Div({
            classes(DashboardStyles.modal)
            onClick { it.stopPropagation() }
            style {
                property("max-width", "800px")
                property("max-height", "85vh")
                property("min-width", "600px")
                property("overflow", "hidden")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
            }
        }) {
            // Header
            Div({
                classes(DashboardStyles.modalHeader)
                style { property("flex-shrink", "0") }
            }) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        gap(8.px)
                    }
                }) {
                    H3({ classes(DashboardStyles.modalTitle) }) {
                        Text(if (event.phase == "starting") "LLM Request" else "LLM Response")
                    }
                    PhaseBadge(event.phase)
                }
                Button({
                    classes(DashboardStyles.modalCloseButton)
                    onClick { onClose() }
                }) {
                    I({ classes("bi", "bi-x") })
                }
            }

            // Body
            Div({
                classes(DashboardStyles.modalBody)
                style {
                    property("overflow-y", "auto")
                    property("flex", "1 1 auto")
                    property("min-height", "0")
                }
            }) {
                if (json == null) {
                    Span({
                        style {
                            color(Color("#71717a"))
                            fontSize(13.px)
                        }
                    }) {
                        Text("Unable to parse detail data.")
                    }
                } else if (event.phase == "starting") {
                    LlmRequestContent(json)
                } else {
                    LlmResponseContent(json)
                }
            }
        }
    }
}

@Composable
private fun LlmRequestContent(json: JsonObject) {
    // Model section
    json["model"]?.jsonObject?.let { model ->
        DetailSection("Model", "bi-cpu") {
            DetailGrid {
                DetailGridItem("Provider", model["provider"]?.jsonPrimitive?.contentOrNull ?: "-")
                DetailGridItem("Model", model["model"]?.jsonPrimitive?.contentOrNull ?: "-")
                model["contextLength"]?.jsonPrimitive?.longOrNull?.let {
                    DetailGridItem("Context Length", it.toString())
                }
                model["maxOutputTokens"]?.jsonPrimitive?.longOrNull?.let {
                    DetailGridItem("Max Output Tokens", it.toString())
                }
            }
        }
    }

    // Parameters section
    json["params"]?.jsonObject?.let { params ->
        if (params.isNotEmpty()) {
            DetailSection("Parameters", "bi-sliders") {
                DetailGrid {
                    params["temperature"]?.jsonPrimitive?.doubleOrNull?.let {
                        DetailGridItem("Temperature", it.toString())
                    }
                    params["maxTokens"]?.jsonPrimitive?.intOrNull?.let {
                        DetailGridItem("Max Tokens", it.toString())
                    }
                    params["toolChoice"]?.jsonPrimitive?.contentOrNull?.let {
                        DetailGridItem("Tool Choice", it)
                    }
                    params["numberOfChoices"]?.jsonPrimitive?.intOrNull?.let {
                        DetailGridItem("Num Choices", it.toString())
                    }
                }
            }
        }
    }

    // Available tools section
    json["tools"]?.jsonArray?.let { tools ->
        if (tools.isNotEmpty()) {
            DetailSection("Available Tools", "bi-tools") {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("flex-wrap", "wrap")
                        gap(6.px)
                    }
                }) {
                    tools.forEach { tool ->
                        Span({
                            style {
                                padding(2.px, 8.px)
                                backgroundColor(Color("#f59e0b15"))
                                borderRadius(4.px)
                                fontSize(12.px)
                                color(Color("#f59e0b"))
                                fontWeight("500")
                            }
                        }) {
                            Text(tool.jsonPrimitive.content)
                        }
                    }
                }
            }
        }
    }

    // Messages section
    json["messages"]?.jsonArray?.let { messages ->
        DetailSection("Messages (${messages.size})", "bi-chat-left-text") {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    gap(8.px)
                }
            }) {
                messages.forEach { msg ->
                    val msgObj = msg.jsonObject
                    val role = msgObj["role"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val content = msgObj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val toolName = msgObj["tool"]?.jsonPrimitive?.contentOrNull
                    MessageBlock(role, content, toolName)
                }
            }
        }
    }
}

@Composable
private fun LlmResponseContent(json: JsonObject) {
    // Model section
    json["model"]?.jsonObject?.let { model ->
        DetailSection("Model", "bi-cpu") {
            DetailGrid {
                DetailGridItem("Provider", model["provider"]?.jsonPrimitive?.contentOrNull ?: "-")
                DetailGridItem("Model", model["model"]?.jsonPrimitive?.contentOrNull ?: "-")
            }
        }
    }

    // Token usage section
    json["tokenUsage"]?.jsonObject?.let { usage ->
        DetailSection("Token Usage", "bi-speedometer2") {
            DetailGrid {
                usage["input"]?.jsonPrimitive?.intOrNull?.let {
                    DetailGridItem("Input", it.toString())
                }
                usage["output"]?.jsonPrimitive?.intOrNull?.let {
                    DetailGridItem("Output", it.toString())
                }
                usage["total"]?.jsonPrimitive?.intOrNull?.let {
                    DetailGridItem("Total", it.toString())
                }
            }
        }
    }

    // Responses section
    json["responses"]?.jsonArray?.let { responses ->
        DetailSection("Responses (${responses.size})", "bi-reply") {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    gap(8.px)
                }
            }) {
                responses.forEach { resp ->
                    val respObj = resp.jsonObject
                    val role = respObj["role"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val content = respObj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val finishReason = respObj["finishReason"]?.jsonPrimitive?.contentOrNull
                    val toolCall = respObj["toolCall"]?.jsonObject

                    Div({
                        style {
                            backgroundColor(Color("#0f0f1a"))
                            borderRadius(8.px)
                            padding(12.px)
                        }
                    }) {
                        // Role badge row
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                gap(8.px)
                                marginBottom(8.px)
                            }
                        }) {
                            RoleBadge(role)
                            finishReason?.let {
                                Span({
                                    style {
                                        fontSize(11.px)
                                        color(Color("#71717a"))
                                    }
                                }) {
                                    Text("finish: $it")
                                }
                            }
                        }

                        // Tool call info
                        if (toolCall != null) {
                            val toolName = toolCall["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                            val toolArgs = toolCall["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
                            Div({
                                style {
                                    marginBottom(8.px)
                                }
                            }) {
                                Span({
                                    style {
                                        fontSize(12.px)
                                        fontWeight("600")
                                        color(Color("#f59e0b"))
                                    }
                                }) {
                                    Text("Tool: $toolName")
                                }
                            }
                            Pre({
                                style {
                                    backgroundColor(Color("#1a1a2e"))
                                    padding(10.px)
                                    borderRadius(6.px)
                                    fontSize(12.px)
                                    color(Color("#e4e4e7"))
                                    property("white-space", "pre-wrap")
                                    property("word-break", "break-all")
                                    property("max-height", "200px")
                                    property("overflow-y", "auto")
                                    property("margin", "0")
                                }
                            }) {
                                Text(formatJsonString(toolArgs))
                            }
                        } else if (content.isNotBlank()) {
                            // Text content
                            Pre({
                                style {
                                    backgroundColor(Color("#1a1a2e"))
                                    padding(10.px)
                                    borderRadius(6.px)
                                    fontSize(12.px)
                                    color(Color("#e4e4e7"))
                                    property("white-space", "pre-wrap")
                                    property("word-break", "break-word")
                                    property("max-height", "300px")
                                    property("overflow-y", "auto")
                                    property("margin", "0")
                                }
                            }) {
                                Text(content)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, icon: String, content: @Composable () -> Unit) {
    Div({
        style {
            marginBottom(16.px)
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(6.px)
                marginBottom(8.px)
            }
        }) {
            I({
                classes("bi", icon)
                style {
                    fontSize(14.px)
                    color(Color("#6366f1"))
                }
            })
            Span({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color("#e4e4e7"))
                }
            }) {
                Text(title)
            }
        }
        content()
    }
}

@Composable
private fun DetailGrid(content: @Composable () -> Unit) {
    Div({
        style {
            display(DisplayStyle.Grid)
            property("grid-template-columns", "repeat(auto-fill, minmax(160px, 1fr))")
            gap(8.px)
        }
    }) {
        content()
    }
}

@Composable
private fun DetailGridItem(label: String, value: String) {
    Div({
        style {
            padding(8.px, 12.px)
            backgroundColor(Color("#0f0f1a"))
            borderRadius(6.px)
        }
    }) {
        Div({
            style {
                fontSize(11.px)
                color(Color("#71717a"))
                marginBottom(2.px)
            }
        }) {
            Text(label)
        }
        Div({
            style {
                fontSize(13.px)
                fontWeight("500")
                color(Color("#e4e4e7"))
            }
        }) {
            Text(value)
        }
    }
}

@Composable
private fun MessageBlock(role: String, content: String, toolName: String? = null) {
    Div({
        style {
            backgroundColor(Color("#0f0f1a"))
            borderRadius(8.px)
            padding(12.px)
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(8.px)
                marginBottom(if (content.isNotBlank()) 8.px else 0.px)
            }
        }) {
            RoleBadge(role)
            toolName?.let {
                Span({
                    style {
                        fontSize(11.px)
                        color(Color("#71717a"))
                    }
                }) {
                    Text("tool: $it")
                }
            }
        }
        if (content.isNotBlank()) {
            Pre({
                style {
                    backgroundColor(Color("#1a1a2e"))
                    padding(10.px)
                    borderRadius(6.px)
                    fontSize(12.px)
                    color(Color("#e4e4e7"))
                    property("white-space", "pre-wrap")
                    property("word-break", "break-word")
                    property("max-height", "200px")
                    property("overflow-y", "auto")
                    property("margin", "0")
                }
            }) {
                Text(content)
            }
        }
    }
}

@Composable
private fun RoleBadge(role: String) {
    val color = when (role) {
        "system" -> "#71717a"
        "user" -> "#3b82f6"
        "assistant" -> "#8b5cf6"
        "tool" -> "#f59e0b"
        "reasoning" -> "#06b6d4"
        else -> "#71717a"
    }
    Span({
        style {
            padding(2.px, 8.px)
            backgroundColor(Color("${color}20"))
            borderRadius(4.px)
            fontSize(11.px)
            fontWeight("600")
            color(Color(color))
            property("text-transform", "uppercase")
            property("letter-spacing", "0.05em")
        }
    }) {
        Text(role)
    }
}

private fun formatJsonString(jsonStr: String): String {
    return try {
        val element = Json.parseToJsonElement(jsonStr)
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)
    } catch (_: Exception) {
        jsonStr
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
