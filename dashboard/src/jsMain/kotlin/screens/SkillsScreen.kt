package screens

import DashboardStyles
import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun SkillsScreen() {
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
            Text("Skills management coming soon...")
        }
    }
}
