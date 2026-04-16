package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.*
import apiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import fraggle.models.SkillDetail
import fraggle.models.SkillInfo
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader

@Composable
fun SkillsScreen(wsService: WebSocketService) {
    val scope = rememberCoroutineScope()
    var expandedSkill by remember { mutableStateOf<String?>(null) }
    val details = remember { mutableStateMapOf<String, SkillDetail>() }

    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Skills),
    ) {
        apiClient.get("skills").body<List<SkillInfo>>()
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
                    Text("Loaded Skills")
                }
                DataStateLoadingSpinner(state)
            }
            Button({
                classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                onClick {
                    details.clear()
                    refresh()
                }
            }) {
                I({ classes("bi", "bi-arrow-repeat") })
                Text("Refresh")
            }
        }

        when (state) {
            is DataState.Loading -> LoadingCard("Loading skills...")
            is DataState.Error -> ErrorCard(state.message)
            is DataState.Success -> {
                val skills = state.data
                if (skills.isEmpty()) {
                    EmptyCard("No skills loaded", "bi-lightbulb")
                } else {
                    Div({ classes(DashboardStyles.cardList) }) {
                        skills.forEach { skill ->
                            SkillCard(
                                skill = skill,
                                detail = details[skill.name],
                                isExpanded = expandedSkill == skill.name,
                                onToggle = {
                                    val wasExpanded = expandedSkill == skill.name
                                    expandedSkill = if (wasExpanded) null else skill.name
                                    if (!wasExpanded && details[skill.name] == null) {
                                        scope.launch {
                                            runCatching {
                                                apiClient.get("skills/${skill.name}").body<SkillDetail>()
                                            }.onSuccess { details[skill.name] = it }
                                        }
                                    }
                                },
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
    detail: SkillDetail?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Div({
        classes(DashboardStyles.card)
        style { overflow("hidden") }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.SpaceBetween)
                padding(20.px, 24.px)
                cursor("pointer")
                gap(12.px)
            }
            onClick { onToggle() }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                    flex(1)
                    minWidth(0.px)
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
                        backgroundColor(Color("#f59e0b1a"))
                        borderRadius(10.px)
                        fontSize(20.px)
                        color(Color("#f59e0b"))
                    }
                }) {
                    I({ classes("bi", "bi-lightbulb") })
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
                    }) { Text(skill.name) }
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color("#71717a"))
                        }
                    }) { Text(skill.description) }
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
                SkillBadge(skill.source.lowercase(), "#6366f1")
                if (skill.disableModelInvocation) {
                    SkillBadge("manual", "#71717a")
                }
                I({
                    classes("bi", if (isExpanded) "bi-chevron-up" else "bi-chevron-down")
                    style {
                        fontSize(16.px)
                        color(Color("#71717a"))
                    }
                })
            }
        }

        if (isExpanded) {
            Div({
                style {
                    padding(0.px, 24.px, 20.px, 24.px)
                    property("border-top", "1px solid #27273a")
                    paddingTop(16.px)
                }
            }) {
                if (detail == null) {
                    Span({
                        style {
                            fontSize(13.px)
                            color(Color("#71717a"))
                        }
                    }) { Text("Loading skill body...") }
                } else {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            flexDirection(FlexDirection.Column)
                            gap(8.px)
                            marginBottom(16.px)
                        }
                    }) {
                        MetadataRow("File", detail.filePath)
                        detail.license?.let { MetadataRow("License", it) }
                        detail.compatibility?.let { MetadataRow("Compatibility", it) }
                        if (detail.allowedTools.isNotEmpty()) {
                            MetadataRow("Allowed tools", detail.allowedTools.joinToString(", "))
                        }
                    }
                    Pre({
                        style {
                            backgroundColor(Color("#0f0f1a"))
                            padding(16.px)
                            borderRadius(8.px)
                            fontSize(12.px)
                            color(Color("#a1a1aa"))
                            overflow("auto")
                            maxHeight(400.px)
                            property("white-space", "pre-wrap")
                            property("word-break", "break-word")
                        }
                    }) { Text(detail.body) }
                }
            }
        }
    }
}

@Composable
private fun SkillBadge(text: String, color: String) {
    Span({
        style {
            fontSize(11.px)
            color(Color(color))
            backgroundColor(Color("${color}1a"))
            padding(2.px, 8.px)
            borderRadius(4.px)
            property("text-transform", "uppercase")
            property("letter-spacing", "0.5px")
            fontWeight("600")
        }
    }) { Text(text) }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Div({
        style {
            display(DisplayStyle.Flex)
            gap(12.px)
            fontSize(13.px)
        }
    }) {
        Span({
            style {
                color(Color("#71717a"))
                minWidth(120.px)
            }
        }) { Text(label) }
        Code({
            style {
                color(Color("#e4e4e7"))
                property("word-break", "break-all")
            }
        }) { Text(value) }
    }
}
