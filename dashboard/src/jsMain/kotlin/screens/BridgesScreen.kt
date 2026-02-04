package screens

import DashboardStyles
import apiClient
import androidx.compose.runtime.*
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import org.drewcarlson.fraggle.models.BridgeInfo
import org.jetbrains.compose.web.dom.*

sealed class BridgesState {
    data object Loading : BridgesState()
    data class Success(val bridges: List<BridgeInfo>) : BridgesState()
    data class Error(val message: String) : BridgesState()
}

@Composable
fun BridgesScreen() {
    var state by remember { mutableStateOf<BridgesState>(BridgesState.Loading) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Fetch bridges
    LaunchedEffect(refreshTrigger) {
        state = BridgesState.Loading
        state = try {
            val bridges = apiClient.get("${getApiBaseUrl()}/bridges").body<List<BridgeInfo>>()
            BridgesState.Success(bridges)
        } catch (e: Exception) {
            BridgesState.Error(e.message ?: "Failed to load bridges")
        }
    }

    // Auto-refresh every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            refreshTrigger++
        }
    }

    Section({
        classes(DashboardStyles.section)
    }) {
        H2({
            classes(DashboardStyles.sectionTitle)
        }) {
            Text("Chat Bridges")
        }

        when (val currentState = state) {
            is BridgesState.Loading -> {
                LoadingCard("Loading bridges...")
            }
            is BridgesState.Error -> {
                ErrorCard(currentState.message)
            }
            is BridgesState.Success -> {
                if (currentState.bridges.isEmpty()) {
                    EmptyCard("No bridges configured", "bi-plug")
                } else {
                    Div({
                        classes(DashboardStyles.cardList)
                    }) {
                        currentState.bridges.forEach { bridge ->
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
