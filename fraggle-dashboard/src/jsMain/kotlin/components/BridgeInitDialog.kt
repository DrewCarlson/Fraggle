package components

import DashboardStyles
import WebSocketService
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest
import org.drewcarlson.fraggle.models.FraggleEvent
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

/**
 * State of the bridge initialization dialog.
 */
sealed class InitDialogState {
    data object Connecting : InitDialogState()
    data class Prompting(
        val sessionId: String,
        val prompt: String,
        val helpText: String?,
        val sensitive: Boolean,
    ) : InitDialogState()
    data class InProgress(val message: String) : InitDialogState()
    data class Error(val message: String, val recoverable: Boolean) : InitDialogState()
    data class Complete(val message: String) : InitDialogState()
}

/**
 * Modal dialog for interactive bridge initialization.
 */
@Composable
fun BridgeInitDialog(
    bridgeName: String,
    wsService: WebSocketService,
    onClose: () -> Unit,
) {
    var state by remember { mutableStateOf<InitDialogState>(InitDialogState.Connecting) }
    var inputValue by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf<String?>(null) }

    // Listen to bridge init events
    LaunchedEffect(bridgeName) {
        wsService.bridgeInitEvents.collectLatest { event ->
            when (event) {
                is FraggleEvent.BridgeInitPrompt -> {
                    if (event.bridgeName == bridgeName) {
                        sessionId = event.sessionId
                        state = InitDialogState.Prompting(
                            sessionId = event.sessionId,
                            prompt = event.prompt,
                            helpText = event.helpText,
                            sensitive = event.sensitive,
                        )
                        inputValue = ""
                    }
                }
                is FraggleEvent.BridgeInitProgress -> {
                    if (event.bridgeName == bridgeName) {
                        state = InitDialogState.InProgress(event.message)
                    }
                }
                is FraggleEvent.BridgeInitComplete -> {
                    if (event.bridgeName == bridgeName) {
                        state = InitDialogState.Complete(event.message)
                    }
                }
                is FraggleEvent.BridgeInitError -> {
                    if (event.bridgeName == bridgeName) {
                        state = InitDialogState.Error(event.message, event.recoverable)
                    }
                }
                else -> {}
            }
        }
    }

    // Start initialization when dialog opens
    LaunchedEffect(bridgeName) {
        wsService.startBridgeInit(bridgeName)
    }

    // Modal overlay
    Div({
        classes(DashboardStyles.modalOverlay)
        onClick { event ->
            // Close on overlay click, but not on modal content click
            if (event.target == event.currentTarget) {
                sessionId?.let { wsService.cancelBridgeInit(it) }
                onClose()
            }
        }
    }) {
        // Modal content
        Div({
            classes(DashboardStyles.modal)
            onClick { event -> event.stopPropagation() }
        }) {
            // Header
            Div({
                classes(DashboardStyles.modalHeader)
            }) {
                H3({
                    classes(DashboardStyles.modalTitle)
                }) {
                    Text("Setup ${bridgeName.replaceFirstChar { it.uppercase() }} Bridge")
                }
                Button({
                    classes(DashboardStyles.modalCloseButton)
                    onClick {
                        sessionId?.let { wsService.cancelBridgeInit(it) }
                        onClose()
                    }
                }) {
                    I({ classes("bi", "bi-x") })
                }
            }

            // Body
            Div({
                classes(DashboardStyles.modalBody)
            }) {
                when (val currentState = state) {
                    is InitDialogState.Connecting -> {
                        ConnectingView()
                    }
                    is InitDialogState.Prompting -> {
                        PromptingView(
                            prompt = currentState.prompt,
                            helpText = currentState.helpText,
                            sensitive = currentState.sensitive,
                            inputValue = inputValue,
                            onInputChange = { inputValue = it },
                            onSubmit = {
                                wsService.submitBridgeInitInput(currentState.sessionId, inputValue)
                                state = InitDialogState.InProgress("Processing...")
                            },
                        )
                    }
                    is InitDialogState.InProgress -> {
                        InProgressView(message = currentState.message)
                    }
                    is InitDialogState.Error -> {
                        ErrorView(
                            message = currentState.message,
                            recoverable = currentState.recoverable,
                            onRetry = {
                                wsService.startBridgeInit(bridgeName)
                                state = InitDialogState.Connecting
                            },
                        )
                    }
                    is InitDialogState.Complete -> {
                        CompleteView(message = currentState.message)
                    }
                }
            }

            // Footer
            Div({
                classes(DashboardStyles.modalFooter)
            }) {
                when (val currentState = state) {
                    is InitDialogState.Prompting -> {
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonOutline)
                            onClick {
                                sessionId?.let { wsService.cancelBridgeInit(it) }
                                onClose()
                            }
                        }) {
                            Text("Cancel")
                        }
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                            onClick {
                                wsService.submitBridgeInitInput(currentState.sessionId, inputValue)
                                state = InitDialogState.InProgress("Processing...")
                            }
                        }) {
                            Text("Submit")
                        }
                    }
                    is InitDialogState.Error -> {
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonOutline)
                            onClick { onClose() }
                        }) {
                            Text("Close")
                        }
                        if (currentState.recoverable) {
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                                onClick {
                                    wsService.startBridgeInit(bridgeName)
                                    state = InitDialogState.Connecting
                                }
                            }) {
                                Text("Retry")
                            }
                        }
                    }
                    is InitDialogState.Complete -> {
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                            onClick { onClose() }
                        }) {
                            Text("Done")
                        }
                    }
                    else -> {
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonOutline)
                            onClick {
                                sessionId?.let { wsService.cancelBridgeInit(it) }
                                onClose()
                            }
                        }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectingView() {
    Div({
        classes(DashboardStyles.progressMessage)
    }) {
        Div({ classes(DashboardStyles.spinner) })
        Text("Starting initialization...")
    }
}

@Composable
private fun PromptingView(
    prompt: String,
    helpText: String?,
    sensitive: Boolean,
    inputValue: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    // Help text
    helpText?.let { text ->
        Div({
            classes(DashboardStyles.helpText)
        }) {
            Text(text)
        }
    }

    // Input field
    Div({
        classes(DashboardStyles.formGroup)
    }) {
        Span({
            classes(DashboardStyles.label)
        }) {
            Text(prompt)
        }
        Input(type = if (sensitive) InputType.Password else InputType.Text) {
            classes(DashboardStyles.input)
            value(inputValue)
            placeholder("Enter your response...")
            onInput { event ->
                onInputChange(event.value)
            }
            onKeyDown { event ->
                if (event.key == "Enter") {
                    onSubmit()
                }
            }
        }
    }
}

@Composable
private fun InProgressView(message: String) {
    Div({
        classes(DashboardStyles.progressMessage)
    }) {
        Div({ classes(DashboardStyles.spinner) })
        Text(message)
    }
}

@Composable
private fun ErrorView(
    message: String,
    recoverable: Boolean,
    onRetry: () -> Unit,
) {
    Div({
        classes(DashboardStyles.errorMessage)
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.FlexStart)
                gap(12.px)
            }
        }) {
            I({
                classes("bi", "bi-exclamation-circle")
                style { fontSize(18.px) }
            })
            Div {
                Div({
                    style {
                        fontWeight("600")
                        marginBottom(4.px)
                    }
                }) {
                    Text(if (recoverable) "Error" else "Initialization Failed")
                }
                Text(message)
            }
        }
    }
}

@Composable
private fun CompleteView(message: String) {
    Div({
        classes(DashboardStyles.successMessage)
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.FlexStart)
                gap(12.px)
            }
        }) {
            I({
                classes("bi", "bi-check-circle")
                style { fontSize(18.px) }
            })
            Div {
                Div({
                    style {
                        fontWeight("600")
                        marginBottom(8.px)
                    }
                }) {
                    Text("Setup Complete")
                }
                // Render message with clickable URLs and line breaks
                FormattedMessage(message)
            }
        }
    }
}

/**
 * Renders text with URLs converted to clickable links and line breaks preserved.
 */
@Composable
private fun FormattedMessage(message: String) {
    val urlRegex = Regex("""https?://[^\s]+""")

    // Split by lines first to preserve line breaks
    message.lines().forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) {
            Br()
        }

        // Find URLs in this line
        val matches = urlRegex.findAll(line).toList()

        if (matches.isEmpty()) {
            Text(line)
        } else {
            var lastEnd = 0
            matches.forEach { match ->
                // Text before the URL
                if (match.range.first > lastEnd) {
                    Text(line.substring(lastEnd, match.range.first))
                }
                // The URL as a link
                A(
                    href = match.value,
                    attrs = {
                        attr("target", "_blank")
                        attr("rel", "noopener noreferrer")
                        style {
                            color(Color("#7289da"))
                            property("word-break", "break-all")
                        }
                    }
                ) {
                    Text(match.value)
                }
                lastEnd = match.range.last + 1
            }
            // Text after the last URL
            if (lastEnd < line.length) {
                Text(line.substring(lastEnd))
            }
        }
    }
}
