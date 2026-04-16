package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import app.softwork.routingcompose.Router
import androidx.compose.runtime.*
import apiClient
import kotlinx.coroutines.launch
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import fraggle.models.TraceEventRecord
import fraggle.models.TraceSession
import fraggle.models.TraceSessionDetail
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import rememberRefreshableDataLoader
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Composable
fun TracingScreen(wsService: WebSocketService) {
    val router = Router.current
    SessionListView(
        wsService = wsService,
        onSelectSession = { router.navigate("/tracing/$it") },
    )
}

@Composable
fun TracingDetailScreen(sessionId: String, wsService: WebSocketService) {
    val router = Router.current
    SessionDetailView(
        sessionId = sessionId,
        wsService = wsService,
        onBack = { router.navigate("/tracing") },
    )
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
                                            val scope = rememberCoroutineScope()
                                            Div({
                                                style {
                                                    display(DisplayStyle.Flex)
                                                    gap(4.px)
                                                }
                                            }) {
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
                                                Button({
                                                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                                                    onClick {
                                                        it.stopPropagation()
                                                        scope.launch {
                                                            val detail = apiClient.get("tracing/sessions/${session.id}")
                                                                .body<TraceSessionDetail>()
                                                            exportTraceJson(detail)
                                                        }
                                                    }
                                                }) {
                                                    I({ classes("bi", "bi-download") })
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

    // Detail dialog — dispatches by event type
    detailEvent?.let { event ->
        EventDetailDialog(event = event, onClose = { detailEvent = null })
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

            if (state is DataState.Success) {
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                    onClick { exportTraceJson((state as DataState.Success<TraceSessionDetail>).data) }
                }) {
                    I({ classes("bi", "bi-download") })
                    Text("Export")
                }
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
                                        Th({ classes(DashboardStyles.tableHeader) }) { Text("Duration") }
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
                                                DurationCell(event)
                                            }
                                            Td({ classes(DashboardStyles.tableCell) }) {
                                                if (event.detail != null || event.data.isNotEmpty()) {
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
        "turn" -> "bi-arrow-repeat" to "#06b6d4"
        "message" -> "bi-chat-left-text" to "#8b5cf6"
        "tool", "tool_call" -> "bi-tools" to "#f59e0b"
        "llm_call" -> "bi-cpu" to "#8b5cf6"
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
        "start", "starting" -> "#06b6d4"
        "end", "finished" -> "#22c55e"
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
private fun EventDetailDialog(event: TraceEventRecord, onClose: () -> Unit) {
    val json = event.detail?.let {
        try {
            Json.parseToJsonElement(it).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    val title = when (event.eventType) {
        "agent" -> "Agent"
        "turn" -> "Turn"
        "message" -> "Message"
        "tool", "tool_call" -> "Tool"
        "llm_call" -> if (event.phase == "starting") "LLM Request" else "LLM Response"
        else -> event.eventType.replace("_", " ").replaceFirstChar { it.uppercase() }
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
                        Text(title)
                    }
                    EventTypeBadge(event.eventType)
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
                when {
                    event.eventType == "tool" || event.eventType == "tool_call" -> ToolEventContent(event, json)
                    event.eventType == "message" -> MessageEventContent(event, json)
                    event.eventType == "turn" -> TurnEventContent(event, json)
                    event.eventType == "agent" -> AgentEventContent(event, json)
                    event.eventType == "llm_call" && json != null -> {
                        if (event.phase == "starting") LlmRequestContent(json) else LlmResponseContent(json)
                    }
                    json != null -> RawJsonContent(json)
                    else -> DataMapContent(event.data)
                }
            }
        }
    }
}

@Composable
private fun ToolEventContent(event: TraceEventRecord, json: JsonObject?) {
    val isEnd = event.phase == "end" || event.phase == "finished"
    DetailSection("Tool", "bi-tools") {
        DetailGrid {
            val toolName = json?.get("tool_name")?.jsonPrimitive?.contentOrNull
                ?: event.data["tool_name"] ?: "-"
            DetailGridItem("Name", toolName)
            val callId = json?.get("tool_call_id")?.jsonPrimitive?.contentOrNull
                ?: event.data["tool_call_id"] ?: "-"
            DetailGridItem("Call ID", callId)
            if (isEnd) {
                val isError = json?.get("is_error")?.jsonPrimitive?.booleanOrNull
                    ?: event.data["is_error"]?.toBooleanStrictOrNull()
                    ?: false
                DetailGridItem("Error", if (isError) "yes" else "no")
            }
        }
    }

    json?.get("arguments")?.let { args ->
        DetailSection("Arguments", "bi-sliders") {
            JsonBlock(args)
        }
    }

    json?.get("result")?.let { result ->
        DetailSection("Result", "bi-reply") {
            JsonBlock(result)
        }
    }
}

@Composable
private fun MessageEventContent(event: TraceEventRecord, json: JsonObject?) {
    val role = json?.get("type")?.jsonPrimitive?.contentOrNull
        ?: event.data["type"] ?: "unknown"

    DetailSection("Message", "bi-chat-left-text") {
        DetailGrid {
            DetailGridItem("Type", role)
            json?.get("tool_call_id")?.jsonPrimitive?.contentOrNull?.let {
                DetailGridItem("Tool Call ID", it)
            }
            json?.get("is_error")?.jsonPrimitive?.booleanOrNull?.let {
                DetailGridItem("Error", if (it) "yes" else "no")
            }
        }
    }

    json?.get("usage")?.jsonObject?.let { usage ->
        DetailSection("Token Usage", "bi-speedometer2") {
            DetailGrid {
                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    DetailGridItem("Prompt", it.toString())
                }
                usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    DetailGridItem("Completion", it.toString())
                }
                usage["total_tokens"]?.jsonPrimitive?.intOrNull?.let {
                    DetailGridItem("Total", it.toString())
                }
            }
        }
    }

    val content = json?.get("content")?.jsonPrimitive?.contentOrNull
    if (!content.isNullOrBlank()) {
        DetailSection("Content", "bi-file-text") {
            Pre({
                style {
                    backgroundColor(Color("#0f0f1a"))
                    padding(12.px)
                    borderRadius(8.px)
                    fontSize(12.px)
                    color(Color("#e4e4e7"))
                    property("white-space", "pre-wrap")
                    property("word-break", "break-word")
                    property("max-height", "400px")
                    property("overflow-y", "auto")
                    property("margin", "0")
                }
            }) {
                Text(content)
            }
        }
    }

    json?.get("tool_calls")?.jsonArray?.let { calls ->
        if (calls.isNotEmpty()) {
            DetailSection("Tool Calls (${calls.size})", "bi-tools") {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        gap(8.px)
                    }
                }) {
                    calls.forEach { call ->
                        val obj = call.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                        val callId = obj["id"]?.jsonPrimitive?.contentOrNull
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
                                    Text(name)
                                }
                                callId?.let {
                                    Span({
                                        style {
                                            fontSize(11.px)
                                            color(Color("#71717a"))
                                        }
                                    }) {
                                        Text(it)
                                    }
                                }
                            }
                            obj["arguments"]?.let { args -> JsonBlock(args) }
                        }
                    }
                }
            }
        }
    }

    if (json == null) {
        DataMapContent(event.data)
    }
}

@Composable
private fun TurnEventContent(event: TraceEventRecord, json: JsonObject?) {
    DetailSection("Turn", "bi-arrow-repeat") {
        DetailGrid {
            val turn = json?.get("turn")?.jsonPrimitive?.intOrNull?.toString()
                ?: event.data["turn"] ?: "-"
            DetailGridItem("Number", turn)
            val msgType = json?.get("message_type")?.jsonPrimitive?.contentOrNull
                ?: event.data["type"]
            if (msgType != null) DetailGridItem("Message Type", msgType)
            val toolResults = json?.get("tool_results_count")?.jsonPrimitive?.intOrNull?.toString()
                ?: event.data["tool_results"]
            if (toolResults != null) DetailGridItem("Tool Results", toolResults)
        }
    }

    json?.get("message")?.jsonObject?.let { msg ->
        DetailSection("Final Message", "bi-chat-left-text") {
            val content = msg["content"]?.jsonPrimitive?.contentOrNull
            if (!content.isNullOrBlank()) {
                Pre({
                    style {
                        backgroundColor(Color("#0f0f1a"))
                        padding(12.px)
                        borderRadius(8.px)
                        fontSize(12.px)
                        color(Color("#e4e4e7"))
                        property("white-space", "pre-wrap")
                        property("word-break", "break-word")
                        property("max-height", "400px")
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

@Composable
private fun AgentEventContent(event: TraceEventRecord, json: JsonObject?) {
    DetailSection("Agent", "bi-robot") {
        if (event.data.isNotEmpty()) {
            DetailGrid {
                for ((k, v) in event.data) DetailGridItem(prettyKey(k), v)
            }
        } else {
            Span({
                style {
                    fontSize(12.px)
                    color(Color("#71717a"))
                }
            }) {
                Text("No additional data for this event.")
            }
        }
    }
    if (json != null) {
        DetailSection("Raw", "bi-code") {
            JsonBlock(json)
        }
    }
}

@Composable
private fun RawJsonContent(json: JsonObject) {
    DetailSection("Detail", "bi-code") {
        JsonBlock(json)
    }
}

@Composable
private fun DataMapContent(data: Map<String, String>) {
    if (data.isEmpty()) {
        Span({
            style {
                fontSize(13.px)
                color(Color("#71717a"))
            }
        }) {
            Text("No data captured for this event.")
        }
        return
    }
    DetailSection("Data", "bi-list-task") {
        DetailGrid {
            for ((k, v) in data) DetailGridItem(prettyKey(k), v)
        }
    }
}

@Composable
private fun JsonBlock(element: JsonElement) {
    val pretty = remember(element) { prettyJson.encodeToString(JsonElement.serializer(), element) }
    Pre({
        style {
            backgroundColor(Color("#0f0f1a"))
            padding(12.px)
            borderRadius(8.px)
            fontSize(12.px)
            color(Color("#e4e4e7"))
            property("white-space", "pre-wrap")
            property("word-break", "break-word")
            property("max-height", "400px")
            property("overflow-y", "auto")
            property("margin", "0")
        }
    }) {
        Text(pretty)
    }
}

private fun prettyKey(key: String): String =
    key.replace("_", " ").split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
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
                                val prettyToolArgs = remember(toolArgs) { formatJsonString(toolArgs) }
                                Text(prettyToolArgs)
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

private val exportJson = Json { prettyPrint = true }

private fun exportTraceJson(detail: TraceSessionDetail) {
    val json = exportJson.encodeToString(TraceSessionDetail.serializer(), detail)
    val blob = Blob(arrayOf(json), BlobPropertyBag(type = "application/json"))
    val url = URL.createObjectURL(blob)
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.download = "trace-${detail.session.id.take(8)}.json"
    anchor.click()
    URL.revokeObjectURL(url)
}

private val prettyJson = Json { prettyPrint = true }

private fun formatJsonString(jsonStr: String): String {
    return try {
        val element = Json.parseToJsonElement(jsonStr)
        prettyJson.encodeToString(JsonElement.serializer(), element)
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

@Composable
private fun DurationCell(event: TraceEventRecord) {
    val duration = event.duration
    val text = when {
        duration != null -> duration.toString()
        event.phase == "start" || event.phase == "starting" -> "—"
        else -> "—"
    }
    val color = when {
        duration == null -> "#71717a"
        duration < 1.seconds -> "#22c55e"
        duration < 5.seconds -> "#06b6d4"
        duration < 10.seconds -> "#f59e0b"
        else -> "#ef4444"
    }
    Span({
        style {
            fontSize(12.px)
            fontWeight("500")
            property("font-variant-numeric", "tabular-nums")
            color(Color(color))
        }
    }) {
        Text(text)
    }
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
