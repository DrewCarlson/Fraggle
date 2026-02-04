package screens

import DashboardStyles
import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun SettingsScreen() {
    Section({
        classes(DashboardStyles.section)
    }) {
        H2({
            classes(DashboardStyles.sectionTitle)
        }) {
            Text("Configuration")
        }
        Div({
            classes(DashboardStyles.card)
        }) {
            P({
                style {
                    color(Color("#71717a"))
                    textAlign("center")
                    padding(32.px)
                }
            }) {
                Text("Settings management coming soon...")
            }
        }
    }
}
