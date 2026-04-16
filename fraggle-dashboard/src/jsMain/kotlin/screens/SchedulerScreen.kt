package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import apiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.launch
import fraggle.models.ScheduledTaskInfo
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader
import kotlin.time.Instant

@Composable
fun SchedulerScreen(wsService: WebSocketService) {
    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Scheduler),
    ) {
        apiClient.get("scheduler/tasks").body<List<ScheduledTaskInfo>>()
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
                    Text("Scheduled Tasks")
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
                LoadingCard("Loading tasks...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val tasks = state.data
                if (tasks.isEmpty()) {
                    EmptyCard("No scheduled tasks", "bi-calendar-event")
                } else {
                    Div({
                        classes(DashboardStyles.cardList)
                    }) {
                        tasks.forEach { task ->
                            val scope = rememberCoroutineScope()
                            TaskCard(
                                task = task,
                                onCancel = {
                                    scope.launch {
                                        apiClient.delete("scheduler/tasks/${task.id}")
                                        refresh()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: ScheduledTaskInfo,
    onCancel: () -> Unit,
) {
    Div({
        classes(DashboardStyles.card)
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.SpaceBetween)
                padding(20.px, 24.px)
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                }
            }) {
                // Status indicator
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        justifyContent(JustifyContent.Center)
                        width(44.px)
                        height(44.px)
                        backgroundColor(if (task.enabled) Color("#22c55e1a") else Color("#71717a1a"))
                        borderRadius(10.px)
                        fontSize(20.px)
                        color(if (task.enabled) Color("#22c55e") else Color("#71717a"))
                    }
                }) {
                    I({ classes("bi", if (task.enabled) "bi-play-circle" else "bi-pause-circle") })
                }

                // Task info
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        gap(4.px)
                    }
                }) {
                    Span({
                        style {
                            fontSize(15.px)
                            fontWeight("600")
                            color(Color("#e4e4e7"))
                        }
                    }) {
                        Text(task.name)
                    }
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color("#71717a"))
                        }
                    }) {
                        Text(task.action)
                    }
                }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                }
            }) {
                // Schedule badge
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        alignItems(AlignItems.FlexEnd)
                        gap(4.px)
                    }
                }) {
                    Span({
                        style {
                            fontSize(12.px)
                            color(Color("#71717a"))
                            backgroundColor(Color("#27273a"))
                            padding(4.px, 8.px)
                            borderRadius(4.px)
                        }
                    }) {
                        I({
                            classes("bi", "bi-clock")
                            style { marginRight(4.px) }
                        })
                        Text(task.schedule)
                    }
                    task.nextRun?.let { nextRun ->
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color("#71717a"))
                            }
                        }) {
                            Text("Next: ${formatInstant(nextRun)}")
                        }
                    }
                }

                // Chat ID
                Code({
                    style {
                        fontSize(11.px)
                        backgroundColor(Color("#27273a"))
                        padding(4.px, 8.px)
                        borderRadius(4.px)
                        color(Color("#a1a1aa"))
                    }
                }) {
                    Text(task.chatId.take(15) + if (task.chatId.length > 15) "..." else "")
                }

                // Cancel button
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonDanger)
                    onClick { onCancel() }
                }) {
                    I({ classes("bi", "bi-x-lg") })
                }
            }
        }
    }
}

private fun formatInstant(instant: Instant): String {
    val epochMillis = instant.toEpochMilliseconds()
    val now = kotlinx.browser.window.asDynamic().Date.now() as Double
    val diffMs = (epochMillis - now).toLong() // Future time, so reversed
    val diffSec = diffMs / 1000L
    val diffMin = diffSec / 60L
    val diffHour = diffMin / 60L
    val diffDay = diffHour / 24L

    return when {
        diffMs < 0L -> "overdue"
        diffDay > 0L -> "in ${diffDay}d"
        diffHour > 0L -> "in ${diffHour}h"
        diffMin > 0L -> "in ${diffMin}m"
        diffSec > 0L -> "in ${diffSec}s"
        else -> "now"
    }
}
