package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.*
import apiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import fraggle.models.ToolInfo
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader

@Composable
fun ToolsScreen(wsService: WebSocketService) {
    var expandedTool by remember { mutableStateOf<String?>(null) }

    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Tools),
    ) {
        apiClient.get("tools").body<List<ToolInfo>>()
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
                    Text("Available Tools")
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
                LoadingCard("Loading tools...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val tools = state.data
                if (tools.isEmpty()) {
                    EmptyCard("No tools available", "bi-tools")
                } else {
                    Div({
                        classes(DashboardStyles.cardList)
                    }) {
                        tools.forEach { tool ->
                            ToolCard(
                                tool = tool,
                                isExpanded = expandedTool == tool.name,
                                onToggle = {
                                    expandedTool = if (expandedTool == tool.name) null else tool.name
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
private fun ToolCard(
    tool: ToolInfo,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Div({
        classes(DashboardStyles.card)
        style {
            overflow("hidden")
        }
    }) {
        // Header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.SpaceBetween)
                padding(20.px, 24.px)
                cursor("pointer")
            }
            onClick { onToggle() }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                }
            }) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        justifyContent(JustifyContent.Center)
                        width(44.px)
                        height(44.px)
                        flexShrink(0)
                        backgroundColor(Color("#6366f11a"))
                        borderRadius(10.px)
                        fontSize(20.px)
                        color(Color("#6366f1"))
                    }
                }) {
                    I({ classes("bi", "bi-puzzle") })
                }
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
                        Text(tool.name)
                    }
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color("#71717a"))
                        }
                    }) {
                        Text(tool.description)
                    }
                }
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(12.px)
                    flexShrink(0)
                }
            }) {
                Span({
                    style {
                        fontSize(12.px)
                        color(Color("#71717a"))
                        backgroundColor(Color("#27273a"))
                        padding(4.px, 8.px)
                        borderRadius(4.px)
                        property("white-space", "nowrap")
                    }
                }) {
                    Text("${tool.parameters.size} params")
                }
                I({
                    classes("bi", if (isExpanded) "bi-chevron-up" else "bi-chevron-down")
                    style {
                        fontSize(16.px)
                        color(Color("#71717a"))
                        property("transition", "transform 0.2s ease")
                    }
                })
            }
        }

        // Expanded content
        if (isExpanded && tool.parameters.isNotEmpty()) {
            Div({
                style {
                    padding(0.px, 24.px, 20.px, 24.px)
                    property("border-top", "1px solid #27273a")
                    marginTop(0.px)
                    paddingTop(16.px)
                }
            }) {
                H4({
                    style {
                        fontSize(13.px)
                        fontWeight("600")
                        color(Color("#71717a"))
                        property("text-transform", "uppercase")
                        letterSpacing(0.5.px)
                        marginBottom(12.px)
                        marginTop(0.px)
                    }
                }) {
                    Text("Parameters")
                }
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        gap(8.px)
                    }
                }) {
                    tool.parameters.forEach { param ->
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.FlexStart)
                                gap(12.px)
                                padding(12.px)
                                backgroundColor(Color("#0f0f1a"))
                                borderRadius(8.px)
                            }
                        }) {
                            Code({
                                style {
                                    fontSize(13.px)
                                    fontWeight("500")
                                    color(Color("#6366f1"))
                                    property("white-space", "nowrap")
                                }
                            }) {
                                Text(param.name)
                            }
                            Span({
                                style {
                                    fontSize(11.px)
                                    color(Color("#71717a"))
                                    backgroundColor(Color("#27273a"))
                                    padding(2.px, 6.px)
                                    borderRadius(4.px)
                                    property("white-space", "nowrap")
                                }
                            }) {
                                Text(param.type)
                            }
                            if (param.required) {
                                Span({
                                    style {
                                        fontSize(11.px)
                                        color(Color("#f59e0b"))
                                        backgroundColor(Color("#f59e0b1a"))
                                        padding(2.px, 6.px)
                                        borderRadius(4.px)
                                    }
                                }) {
                                    Text("required")
                                }
                            }
                            Span({
                                style {
                                    fontSize(13.px)
                                    color(Color("#a1a1aa"))
                                    flex(1)
                                }
                            }) {
                                Text(param.description)
                            }
                        }
                    }
                }
            }
        }
    }
}
