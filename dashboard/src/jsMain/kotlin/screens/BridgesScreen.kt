package screens

import DashboardStyles
import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*

@Composable
fun BridgesScreen() {
    Section({
        classes(DashboardStyles.section)
    }) {
        H2({
            classes(DashboardStyles.sectionTitle)
        }) {
            Text("Chat Bridges")
        }
        Div({
            classes(DashboardStyles.cardList)
        }) {
            BridgeCard(
                name = "Signal",
                status = "Connected",
                isConnected = true,
                icon = "bi-signal",
            )
            BridgeCard(
                name = "WhatsApp",
                status = "Not configured",
                isConnected = false,
                icon = "bi-whatsapp",
            )
            BridgeCard(
                name = "Discord",
                status = "Not configured",
                isConnected = false,
                icon = "bi-discord",
            )
            BridgeCard(
                name = "Telegram",
                status = "Not configured",
                isConnected = false,
                icon = "bi-telegram",
            )
        }
    }
}

@Composable
private fun BridgeCard(
    name: String,
    status: String,
    isConnected: Boolean,
    icon: String,
) {
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
                Text(status)
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
