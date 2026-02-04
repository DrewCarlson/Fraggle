package screens

import DataState
import DashboardStyles
import apiClient
import androidx.compose.runtime.*
import external.highlightElement
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.browser.document
import org.drewcarlson.fraggle.models.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import rememberRefreshableDataLoader

enum class ConfigViewMode {
    YAML,
    UI,
}

@Composable
fun SettingsScreen() {
    var viewMode by remember { mutableStateOf(ConfigViewMode.UI) }

    val (state, refresh) = rememberRefreshableDataLoader {
        apiClient.get("${getApiBaseUrl()}/settings/config").body<ConfigResponse>()
    }

    Section({
        classes(DashboardStyles.section)
    }) {
        // Header with toggle
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
                    Text("Configuration")
                }
                DataStateLoadingSpinner(state)
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    gap(8.px)
                }
            }) {
                // View mode toggle
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall)
                    if (viewMode == ConfigViewMode.UI) {
                        classes(DashboardStyles.buttonPrimary)
                    } else {
                        classes(DashboardStyles.buttonOutline)
                    }
                    onClick { viewMode = ConfigViewMode.UI }
                }) {
                    I({ classes("bi", "bi-layout-text-sidebar") })
                    Text("UI View")
                }
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall)
                    if (viewMode == ConfigViewMode.YAML) {
                        classes(DashboardStyles.buttonPrimary)
                    } else {
                        classes(DashboardStyles.buttonOutline)
                    }
                    onClick { viewMode = ConfigViewMode.YAML }
                }) {
                    I({ classes("bi", "bi-code-slash") })
                    Text("YAML")
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
                LoadingCard("Loading configuration...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                when (viewMode) {
                    ConfigViewMode.YAML -> YamlConfigView(state.data.yaml)
                    ConfigViewMode.UI -> UiConfigView(state.data.config)
                }
            }
        }
    }
}

@Composable
private fun YamlConfigView(yaml: String) {
    Div({
        classes(DashboardStyles.card)
    }) {
        // Use a unique key to track when we need to re-highlight
        val codeId = remember { "yaml-code-${yaml.hashCode()}" }

        Pre({
            style {
                margin(0.px)
                padding(0.px)
                backgroundColor(Color("#0d1117"))
                overflow("auto")
                property("max-height", "70vh")
                borderRadius(8.px)
            }
        }) {
            Code({
                id(codeId)
                classes("language-yaml", "hljs")
                style {
                    display(DisplayStyle.Block)
                    padding(24.px)
                    fontFamily("JetBrains Mono")
                    fontSize(13.px)
                    lineHeight("1.6")
                    property("tab-size", "2")
                }
            }) {
                Text(yaml)
            }
        }

        // Trigger highlight.js after render
        DisposableEffect(yaml) {
            val element = document.getElementById(codeId) as? HTMLElement
            if (element != null) {
                highlightElement(element)
            }
            onDispose { }
        }
    }
}

@Composable
private fun UiConfigView(config: FraggleSettings) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            gap(24.px)
        }
    }) {
        // Provider section
        ConfigSection("Provider", "bi-cpu") {
            ConfigField("Type", config.provider.type.name.lowercase())
            ConfigField("URL", config.provider.url)
            ConfigField("Model", config.provider.model.ifBlank { "(default)" })
            ConfigField("API Key", if (config.provider.apiKey != null) "Configured" else "Not set",
                valueColor = if (config.provider.apiKey != null) "#22c55e" else "#71717a")
        }

        // Bridges section
        ConfigSection("Bridges", "bi-plug") {
            ConfigSubSection("Signal") {
                val signal = config.bridges.signal ?: SignalBridgeConfig()
                val isConfigured = config.bridges.signal != null

                if (!isConfigured) {
                    Div({
                        style {
                            color(Color("#71717a"))
                            fontStyle("italic")
                            marginBottom(12.px)
                            fontSize(12.px)
                        }
                    }) {
                        Text("Not configured (showing defaults)")
                    }
                }

                ConfigField("Enabled", signal.enabled.toString(),
                    valueColor = if (signal.enabled) "#22c55e" else "#71717a")
                ConfigField("Phone", signal.phone.ifBlank { "(not set)" },
                    valueColor = if (signal.phone.isBlank()) "#71717a" else null)
                ConfigField("Config Directory", signal.configDir)
                ConfigField("Trigger", signal.trigger ?: "(all messages)")
                ConfigField("Signal CLI Path", signal.signalCliPath ?: "(system PATH)")
                ConfigField("Respond to DMs", signal.respondToDirectMessages.toString())
                ConfigField("Typing Indicator", signal.showTypingIndicator.toString())
            }
        }

        // Agent section
        ConfigSection("Agent", "bi-robot") {
            ConfigField("Temperature", config.agent.temperature.toString())
            ConfigField("Max Tokens", config.agent.maxTokens.toString())
            ConfigField("Max Iterations", config.agent.maxIterations.toString())
            ConfigField("Max History Messages", config.agent.maxHistoryMessages.toString())
        }

        // Sandbox section
        ConfigSection("Sandbox", "bi-shield-check") {
            ConfigField("Type", config.sandbox.type.name.lowercase())
            ConfigField("Work Directory", config.sandbox.workDir)
        }

        // Memory section
        ConfigSection("Memory", "bi-journal-bookmark") {
            ConfigField("Base Directory", config.memory.baseDir)
        }

        // Prompts section
        ConfigSection("Prompts", "bi-file-text") {
            ConfigField("Prompts Directory", config.prompts.promptsDir)
            ConfigField("Max File Characters", config.prompts.maxFileChars.toString())
            ConfigField("Auto Create Missing", config.prompts.autoCreateMissing.toString())
        }

        // Chats section
        ConfigSection("Registered Chats", "bi-chat-dots") {
            if (config.chats.registered.isEmpty()) {
                Div({
                    style {
                        color(Color("#71717a"))
                        fontStyle("italic")
                    }
                }) {
                    Text("No registered chats")
                }
            } else {
                config.chats.registered.forEachIndexed { index, chat ->
                    if (index > 0) {
                        Div({
                            style {
                                height(1.px)
                                backgroundColor(Color("#27273a"))
                                margin(12.px, 0.px)
                            }
                        })
                    }
                    ConfigField("ID", chat.id)
                    ConfigField("Name", chat.name ?: "(unnamed)")
                    ConfigField("Trigger Override", chat.triggerOverride ?: "(default)")
                    ConfigField("Enabled", chat.enabled.toString(),
                        valueColor = if (chat.enabled) "#22c55e" else "#71717a")
                }
            }
        }

        // Web section
        ConfigSection("Web", "bi-globe") {
            ConfigSubSection("Playwright") {
                val pw = config.web.playwright
                val isConfigured = pw != null

                if (!isConfigured) {
                    Div({
                        style {
                            color(Color("#71717a"))
                            fontStyle("italic")
                            marginBottom(12.px)
                            fontSize(12.px)
                        }
                    }) {
                        Text("Not configured")
                    }
                    // Show default values
                    ConfigField("WebSocket Endpoint", "(not set)", valueColor = "#71717a")
                    ConfigField("Navigation Timeout", "30000ms")
                    ConfigField("Wait After Load", "2000ms")
                    ConfigField("Viewport", "1280x720")
                    ConfigField("User Agent", "(browser default)")
                } else {
                    ConfigField("WebSocket Endpoint", pw.wsEndpoint)
                    ConfigField("Navigation Timeout", "${pw.navigationTimeout}ms")
                    ConfigField("Wait After Load", "${pw.waitAfterLoad}ms")
                    ConfigField("Viewport", "${pw.viewportWidth}x${pw.viewportHeight}")
                    ConfigField("User Agent", pw.userAgent ?: "(browser default)")
                }
            }
        }

        // API section
        ConfigSection("API Server", "bi-hdd-network") {
            ConfigField("Enabled", config.api.enabled.toString(),
                valueColor = if (config.api.enabled) "#22c55e" else "#71717a")
            ConfigField("Host", config.api.host)
            ConfigField("Port", config.api.port.toString())
            ConfigField("CORS Enabled", config.api.cors.enabled.toString())
            if (config.api.cors.allowedOrigins.isNotEmpty()) {
                ConfigField("Allowed Origins", config.api.cors.allowedOrigins.joinToString(", "))
            } else {
                ConfigField("Allowed Origins", "(default localhost)", valueColor = "#71717a")
            }
        }

        // Dashboard section
        ConfigSection("Dashboard", "bi-window") {
            ConfigField("Enabled", config.dashboard.enabled.toString(),
                valueColor = if (config.dashboard.enabled) "#22c55e" else "#71717a")
            ConfigField("Static Path", config.dashboard.staticPath ?: "(embedded)")
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    icon: String,
    content: @Composable () -> Unit,
) {
    Div({
        classes(DashboardStyles.card)
    }) {
        // Header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(12.px)
                padding(16.px, 24.px)
                property("border-bottom", "1px solid #27273a")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    width(32.px)
                    height(32.px)
                    backgroundColor(Color("#6366f11a"))
                    borderRadius(8.px)
                    color(Color("#6366f1"))
                }
            }) {
                I({ classes("bi", icon) })
            }
            H3({
                style {
                    fontSize(16.px)
                    fontWeight("600")
                    color(Color("#e4e4e7"))
                    property("margin", "0")
                }
            }) {
                Text(title)
            }
        }

        // Content
        Div({
            style {
                padding(20.px, 24.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(12.px)
            }
        }) {
            content()
        }
    }
}

@Composable
private fun ConfigSubSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            padding(16.px)
            backgroundColor(Color("#0f0f1a"))
            borderRadius(8.px)
        }
    }) {
        H4({
            style {
                fontSize(14.px)
                fontWeight("600")
                color(Color("#a1a1aa"))
                marginTop(0.px)
                marginBottom(12.px)
            }
        }) {
            Text(title)
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(8.px)
            }
        }) {
            content()
        }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    valueColor: String? = null,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            gap(16.px)
        }
    }) {
        Span({
            style {
                fontSize(13.px)
                color(Color("#71717a"))
                flexShrink(0)
            }
        }) {
            Text(label)
        }
        Span({
            style {
                fontSize(13.px)
                color(Color(valueColor ?: "#e4e4e7"))
                fontFamily("JetBrains Mono")
                textAlign("right")
                property("word-break", "break-all")
            }
        }) {
            Text(value)
        }
    }
}
