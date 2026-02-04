package screens

import DataState
import DashboardStyles
import RefreshTrigger
import WebSocketService
import apiClient
import androidx.compose.runtime.*
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.drewcarlson.fraggle.models.BridgeInfo
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader

@Composable
fun BridgesScreen(wsService: WebSocketService) {
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
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeCard(
    name: String,
    platform: String,
    isConnected: Boolean,
) {
    val icon = when (platform.lowercase()) {
        "signal" -> "bi-signal"
        "whatsapp" -> "bi-whatsapp"
        "discord" -> "bi-discord"
        "telegram" -> "bi-telegram"
        "slack" -> "bi-slack"
        else -> "bi-chat"
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
                Text(if (isConnected) "Connected" else "Disconnected")
            }
        }
        Button({
            classes(DashboardStyles.button, DashboardStyles.buttonSmall)
            if (isConnected) {
                classes(DashboardStyles.buttonOutline)
            } else {
                classes(DashboardStyles.buttonPrimary)
            }
        }) {
            Text(if (isConnected) "Configure" else "Connect")
        }
    }
}
