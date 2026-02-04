package screens

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun NotFoundScreen() {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.Center)
            height(100.percent)
            gap(16.px)
        }
    }) {
        I({
            classes("bi", "bi-exclamation-triangle")
            style {
                fontSize(48.px)
                color(Color("#f59e0b"))
            }
        })
        H2 {
            Text("Page Not Found")
        }
        P({
            style {
                color(Color("#71717a"))
            }
        }) {
            Text("The page you're looking for doesn't exist.")
        }
    }
}
