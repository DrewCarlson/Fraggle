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
import org.drewcarlson.fraggle.models.SkillInfo
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader

@Composable
fun SkillsScreen(wsService: WebSocketService) {
    var expandedSkill by remember { mutableStateOf<String?>(null) }

    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Skills),
    ) {
        apiClient.get("${getApiBaseUrl()}/skills").body<List<SkillInfo>>()
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
                    Text("Available Skills")
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
                LoadingCard("Loading skills...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                val skills = state.data
                if (skills.isEmpty()) {
                    EmptyCard("No skills available", "bi-tools")
                } else {
                    Div({
                        classes(DashboardStyles.cardList)
                    }) {
                        skills.forEach { skill ->
                            SkillCard(
                                skill = skill,
                                isExpanded = expandedSkill == skill.name,
                                onToggle = {
                                    expandedSkill = if (expandedSkill == skill.name) null else skill.name
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
private fun SkillCard(
    skill: SkillInfo,
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
                        Text(skill.name)
                    }
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color("#71717a"))
                        }
                    }) {
                        Text(skill.description)
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
                    Text("${skill.parameters.size} params")
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
        if (isExpanded && skill.parameters.isNotEmpty()) {
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
                    skill.parameters.forEach { param ->
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
