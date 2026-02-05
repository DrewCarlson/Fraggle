package screens

import DataState
import DashboardStyles
import RefreshTrigger
import WebSocketService
import apiClient
import androidx.compose.runtime.*
import components.BridgeInitDialog
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.drewcarlson.fraggle.models.BridgeInfo
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader

@Composable
fun BridgesScreen(wsService: WebSocketService) {
    var initializingBridge by remember { mutableStateOf<String?>(null) }

    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Bridges),
    ) {
        apiClient.get("${getApiBaseUrl()}/bridges").body<List<BridgeInfo>>()
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
                                onSetup = { initializingBridge = bridge.name },
                                onConnect = { /* TODO: implement connect via API */ },
                                onDisconnect = { /* TODO: implement disconnect via API */ },
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
    onSetup: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
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

        // Button logic based on state
        when {
            !isInitialized -> {
                // Not initialized - show Setup button
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonPrimary)
                    onClick { onSetup() }
                }) {
                    I({ classes("bi", "bi-gear") })
                    Text("Setup")
                }
            }
            !isConnected -> {
                // Initialized but not connected - show Connect button
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonPrimary)
                    onClick { onConnect() }
                }) {
                    I({ classes("bi", "bi-plug") })
                    Text("Connect")
                }
            }
            else -> {
                // Connected - show Disconnect button
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                    onClick { onDisconnect() }
                }) {
                    I({ classes("bi", "bi-x-circle") })
                    Text("Disconnect")
                }
            }
        }
    }
}
