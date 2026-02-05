package screens

import DashboardStyles
import DataState
import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.I
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

@Composable
fun LoadingCard(message: String) {
    Div({
        classes(DashboardStyles.card)
        style {
            padding(48.px)
            textAlign("center")
        }
    }) {
        I({
            classes("bi", "bi-arrow-repeat")
            style {
                fontSize(32.px)
                color(Color("#6366f1"))
                property("animation", "spin 1s linear infinite")
            }
        })
        P({
            style {
                color(Color("#71717a"))
                marginTop(16.px)
            }
        }) {
            Text(message)
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Div({
        classes(DashboardStyles.card)
        style {
            padding(48.px)
            textAlign("center")
        }
    }) {
        I({
            classes("bi", "bi-exclamation-triangle")
            style {
                fontSize(32.px)
                color(Color("#ef4444"))
            }
        })
        P({
            style {
                color(Color("#ef4444"))
                marginTop(16.px)
                fontWeight("600")
            }
        }) {
            Text("Error")
        }
        P({
            style {
                color(Color("#71717a"))
                marginTop(8.px)
            }
        }) {
            Text(message)
        }
    }
}

@Composable
fun EmptyCard(message: String, icon: String) {
    Div({
        classes(DashboardStyles.card)
        style {
            padding(48.px)
            textAlign("center")
        }
    }) {
        I({
            classes("bi", icon)
            style {
                fontSize(32.px)
                color(Color("#52525b"))
            }
        })
        P({
            style {
                color(Color("#71717a"))
                marginTop(16.px)
            }
        }) {
            Text(message)
        }
    }
}

@Composable
fun DataStateLoadingSpinner(state: DataState<*>) {
    if (state is DataState.Success && state.isRefreshing) {
        I({
            classes("bi", "bi-arrow-repeat")
            style {
                fontSize(14.px)
                color(Color("#6366f1"))
                property("animation", "spin 1s linear infinite")
            }
        })
    }
}