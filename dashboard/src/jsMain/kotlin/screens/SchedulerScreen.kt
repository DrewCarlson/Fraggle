package screens

import DashboardStyles
import apiClient
import androidx.compose.runtime.*
import getApiBaseUrl
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import org.drewcarlson.fraggle.models.ScheduledTaskInfo
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

sealed class SchedulerState {
    data object Loading : SchedulerState()
    data class Success(val tasks: List<ScheduledTaskInfo>) : SchedulerState()
    data class Error(val message: String) : SchedulerState()
}

@Composable
fun SchedulerScreen() {
    var state by remember { mutableStateOf<SchedulerState>(SchedulerState.Loading) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Fetch tasks
    LaunchedEffect(refreshTrigger) {
        state = SchedulerState.Loading
        state = try {
            val tasks = apiClient.get("${getApiBaseUrl()}/scheduler/tasks").body<List<ScheduledTaskInfo>>()
            SchedulerState.Success(tasks)
        } catch (e: Exception) {
            SchedulerState.Error(e.message ?: "Failed to load tasks")
        }
    }

    // Auto-refresh every 10 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(10000)
            refreshTrigger++
        }
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
            H2({
                classes(DashboardStyles.sectionTitle)
                style { property("margin", "0") }
            }) {
                Text("Scheduled Tasks")
            }
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                onClick { refreshTrigger++ }
            }) {
                I({ classes("bi", "bi-arrow-repeat") })
                Text("Refresh")
            }
        }

        when (val currentState = state) {
            is SchedulerState.Loading -> {
                LoadingCard("Loading tasks...")
            }
            is SchedulerState.Error -> {
                ErrorCard(currentState.message)
            }
            is SchedulerState.Success -> {
                if (currentState.tasks.isEmpty()) {
                    EmptyCard("No scheduled tasks", "bi-calendar-event")
                } else {
                    Div({
                        classes(DashboardStyles.cardList)
                    }) {
                        currentState.tasks.forEach { task ->
                            TaskCard(
                                task = task,
                                onCancel = {
                                    // Would trigger cancel API call
                                    refreshTrigger++
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
