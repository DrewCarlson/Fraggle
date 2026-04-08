package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.*
import apiClient
import components.BridgeInitDialog
import io.ktor.client.call.*
import io.ktor.client.request.*
import fraggle.models.BridgeInfo
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader

@Composable
fun BridgesScreen(wsService: WebSocketService) {
    var initializingBridge by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Bridges),
    ) {
        apiClient.get("bridges").body<List<BridgeInfo>>()
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
                    Text("Chat Bridges")
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
                LoadingCard("Loading bridges...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val bridges = state.data
                if (bridges.isEmpty()) {
                    EmptyCard("No bridges configured", "bi-plug")
                } else {
                    Div({
                        classes(DashboardStyles.cardList)
                    }) {
                        bridges.forEach { bridge ->
                            BridgeCard(
                                name = bridge.name,
                                platform = bridge.platform,
                                isConnected = bridge.connected,
                                isInitialized = bridge.initialized,
                                persistentActivation = bridge.persistentActivation,
                                onSetup = { initializingBridge = bridge.name },
                                onConnect = {
                                    scope.launch {
                                        try {
                                            apiClient.post("bridges/${bridge.name}/connect")
                                        } catch (_: Exception) {
                                        }
                                        refresh()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Bridge initialization dialog
    initializingBridge?.let { bridgeName ->
        BridgeInitDialog(
            bridgeName = bridgeName,
            wsService = wsService,
            onClose = {
                @Suppress("AssignedValueIsNeverRead")
                initializingBridge = null
                refresh()
            },
        )
    }
}

@Composable
private fun BridgeCard(
    name: String,
    platform: String,
    isConnected: Boolean,
    isInitialized: Boolean,
    persistentActivation: Boolean,
    onSetup: () -> Unit,
    onConnect: () -> Unit,
) {
    val icon = when (platform.lowercase()) {
        "signal" -> "bi-signal"
        "whatsapp" -> "bi-whatsapp"
        "discord" -> "bi-discord"
        "telegram" -> "bi-telegram"
        "slack" -> "bi-slack"
        else -> "bi-chat"
    }

    val statusText = when {
        isConnected -> "Connected"
        isInitialized -> "Ready to connect"
        else -> "Needs setup"
    }

    Div({
        classes(DashboardStyles.bridgeCard)
    }) {
        Div({
            classes(DashboardStyles.bridgeIcon)
        }) {
            I({ classes("bi", icon) })
        }
        Div({
            classes(DashboardStyles.bridgeInfo)
        }) {
            Span({
                classes(DashboardStyles.bridgeName)
            }) {
                Text(name)
            }
            Span({
                classes(DashboardStyles.bridgeStatus)
                if (isConnected) {
                    classes(DashboardStyles.statusOnline)
                }
            }) {
                Text(statusText)
            }
        }

        val (label, action, icon) = when {
            // Not initialized - show Setup button
            !isInitialized -> Triple("Setup", onSetup, "bi-gear")
            // Initialized but not connected - show Connect button
            !isConnected -> Triple("Connect", onConnect, "bi-plug")
            // Setup always available - show setup button regardless of state
            persistentActivation -> Triple("Active", onSetup, "bi-link-45deg")
            // When connected, no button needed
            else -> Triple(null, null, null)
        }

        if (label != null && action != null && icon != null) {
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonPrimary)
                onClick { action() }
            }) {
                I({ classes("bi", icon) })
                Text(label)
            }
        }
    }
}
