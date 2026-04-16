package screens

import DashboardStyles
import DataState
import RefreshTrigger
import WebSocketService
import androidx.compose.runtime.*
import apiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import fraggle.models.ErrorResponse
import fraggle.models.SkillDetail
import fraggle.models.SkillInfo
import fraggle.models.SkillInstallRequest
import fraggle.models.SkillInstallResponse
import fraggle.models.SkillPreviewEntry
import fraggle.models.SkillPreviewRequest
import fraggle.models.SkillPreviewResponse
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import rememberRefreshableDataLoader

@Composable
fun SkillsScreen(wsService: WebSocketService) {
    val scope = rememberCoroutineScope()
    var expandedSkill by remember { mutableStateOf<String?>(null) }
    val details = remember { mutableStateMapOf<String, SkillDetail>() }
    var showInstallDialog by remember { mutableStateOf(false) }

    val (state, refresh) = rememberRefreshableDataLoader(
        wsService = wsService,
        refreshOn = setOf(RefreshTrigger.Skills),
    ) {
        apiClient.get("skills").body<List<SkillInfo>>()
    }

    if (showInstallDialog) {
        SkillInstallDialog(
            onClose = { showInstallDialog = false },
            onInstalled = {
                showInstallDialog = false
                details.clear()
                refresh()
            },
        )
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
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(8.px)
                }
            }) {
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonPrimary)
                    onClick { showInstallDialog = true }
                }) {
                    I({ classes("bi", "bi-plus-lg") })
                    Text("Install")
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

// ---- Skill Install Dialog ----

private sealed class InstallStep {
    data object Input : InstallStep()
    data object Previewing : InstallStep()
    data class Preview(
        val sourceLabel: String,
        val skills: List<SkillPreviewEntry>,
        val diagnostics: List<String>,
    ) : InstallStep()
    data object Installing : InstallStep()
    data class Done(val response: SkillInstallResponse) : InstallStep()
    data class Error(val message: String) : InstallStep()
}

@Composable
private fun SkillInstallDialog(
    onClose: () -> Unit,
    onInstalled: () -> Unit,
) {
    var sourceInput by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<InstallStep>(InstallStep.Input) }
    val scope = rememberCoroutineScope()

    Div({
        classes(DashboardStyles.modalOverlay)
        onClick { e ->
            if (e.target == e.currentTarget && step !is InstallStep.Previewing && step !is InstallStep.Installing) {
                onClose()
            }
        }
    }) {
        Div({
            classes(DashboardStyles.modal)
            onClick { it.stopPropagation() }
            style {
                property("max-width", "600px")
                property("overflow", "hidden")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                maxHeight(85.vh)
            }
        }) {
            // Header
            Div({
                classes(DashboardStyles.modalHeader)
                style { property("flex-shrink", "0") }
            }) {
                H3({ classes(DashboardStyles.modalTitle) }) {
                    Text("Install Skill")
                }
                Button({
                    classes(DashboardStyles.modalCloseButton)
                    onClick { onClose() }
                }) {
                    I({ classes("bi", "bi-x") })
                }
            }

            // Body
            Div({
                style {
                    flex(1)
                    property("overflow-y", "auto")
                    property("min-height", "0")
                }
            }) {
                when (val currentStep = step) {
                    is InstallStep.Input, is InstallStep.Previewing -> {
                        val isPreviewing = currentStep is InstallStep.Previewing

                        // Source input section
                        Div({
                            style { marginBottom(16.px) }
                        }) {
                            Div({
                                classes(DashboardStyles.label)
                            }) {
                                Text("Skill source")
                            }
                            Input(type = InputType.Text) {
                                classes(DashboardStyles.input)
                                placeholder("owner/repo, GitHub URL, git URL, or local path")
                                value(sourceInput)
                                onInput { sourceInput = it.value }
                                if (isPreviewing) {
                                    attr("disabled", "true")
                                }
                            }
                            Div({
                                style {
                                    fontSize(12.px)
                                    color(Color("#71717a"))
                                    marginTop(8.px)
                                    lineHeight("1.6")
                                }
                            }) {
                                Text("Examples: ")
                                Code({ style { color(Color("#6366f1")) } }) { Text("owner/repo") }
                                Text(", ")
                                Code({ style { color(Color("#6366f1")) } }) { Text("owner/repo@branch") }
                                Text(", ")
                                Code({ style { color(Color("#6366f1")) } }) { Text("https://github.com/...") }
                            }
                        }

                        // Preview button
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.FlexEnd)
                            }
                        }) {
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                                if (sourceInput.isBlank() || isPreviewing) {
                                    attr("disabled", "true")
                                    if (isPreviewing) {
                                        style { property("opacity", "0.7") }
                                    }
                                }
                                onClick {
                                    step = InstallStep.Previewing
                                    scope.launch {
                                        step = try {
                                            val response = apiClient.post("skills/preview") {
                                                contentType(ContentType.Application.Json)
                                                setBody(SkillPreviewRequest(sourceInput.trim()))
                                            }
                                            if (response.status.isSuccess()) {
                                                val preview = response.body<SkillPreviewResponse>()
                                                if (preview.skills.isEmpty()) {
                                                    InstallStep.Error("No skills found at this source")
                                                } else {
                                                    InstallStep.Preview(
                                                        sourceLabel = preview.sourceLabel,
                                                        skills = preview.skills,
                                                        diagnostics = preview.diagnostics,
                                                    )
                                                }
                                            } else {
                                                val error = runCatching {
                                                    response.body<ErrorResponse>().error
                                                }.getOrDefault("Preview failed (${response.status})")
                                                InstallStep.Error(error)
                                            }
                                        } catch (e: Exception) {
                                            InstallStep.Error(e.message ?: "Preview failed")
                                        }
                                    }
                                }
                            }) {
                                if (isPreviewing) {
                                    I({
                                        classes("bi", "bi-arrow-repeat")
                                        style { property("animation", "spin 1s linear infinite") }
                                    })
                                    Text("Resolving...")
                                } else {
                                    I({ classes("bi", "bi-search") })
                                    Text("Preview")
                                }
                            }
                        }
                    }

                    is InstallStep.Preview -> {
                        // Source label
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                gap(8.px)
                                marginBottom(16.px)
                                padding(10.px, 14.px)
                                backgroundColor(Color("#0f0f1a"))
                                borderRadius(8.px)
                            }
                        }) {
                            I({
                                classes("bi", "bi-box-arrow-in-down")
                                style {
                                    color(Color("#6366f1"))
                                    fontSize(14.px)
                                }
                            })
                            Code({
                                style {
                                    fontSize(13.px)
                                    color(Color("#e4e4e7"))
                                    property("word-break", "break-all")
                                }
                            }) { Text(currentStep.sourceLabel) }
                        }

                        // Skills list
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                flexDirection(FlexDirection.Column)
                                gap(12.px)
                                marginBottom(16.px)
                            }
                        }) {
                            Div({
                                style {
                                    fontSize(13.px)
                                    fontWeight("600")
                                    color(Color("#e4e4e7"))
                                    marginBottom(4.px)
                                }
                            }) {
                                val count = currentStep.skills.size
                                Text("$count skill${if (count != 1) "s" else ""} found:")
                            }

                            currentStep.skills.forEach { entry ->
                                PreviewSkillCard(entry)
                            }
                        }

                        // Diagnostics
                        if (currentStep.diagnostics.isNotEmpty()) {
                            Div({
                                style {
                                    padding(12.px)
                                    backgroundColor(Color("#f59e0b10"))
                                    borderRadius(8.px)
                                    marginBottom(16.px)
                                }
                            }) {
                                Div({
                                    style {
                                        display(DisplayStyle.Flex)
                                        alignItems(AlignItems.Center)
                                        gap(6.px)
                                        marginBottom(8.px)
                                        fontSize(13.px)
                                        fontWeight("600")
                                        color(Color("#f59e0b"))
                                    }
                                }) {
                                    I({ classes("bi", "bi-exclamation-triangle") })
                                    Text("Warnings")
                                }
                                Div({
                                    style {
                                        display(DisplayStyle.Flex)
                                        flexDirection(FlexDirection.Column)
                                        gap(4.px)
                                    }
                                }) {
                                    currentStep.diagnostics.forEach { diag ->
                                        Div({
                                            style {
                                                fontSize(12.px)
                                                color(Color("#71717a"))
                                            }
                                        }) { Text(diag) }
                                    }
                                }
                            }
                        }

                        // Actions
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.FlexEnd)
                                gap(8.px)
                            }
                        }) {
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonOutline)
                                onClick {
                                    step = InstallStep.Input
                                }
                            }) {
                                Text("Back")
                            }
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                                onClick {
                                    step = InstallStep.Installing
                                    scope.launch {
                                        step = try {
                                            val response = apiClient.post("skills/install") {
                                                contentType(ContentType.Application.Json)
                                                setBody(SkillInstallRequest(sourceInput.trim()))
                                            }
                                            if (response.status.isSuccess()) {
                                                InstallStep.Done(response.body<SkillInstallResponse>())
                                            } else {
                                                val error = runCatching {
                                                    response.body<ErrorResponse>().error
                                                }.getOrDefault("Install failed (${response.status})")
                                                InstallStep.Error(error)
                                            }
                                        } catch (e: Exception) {
                                            InstallStep.Error(e.message ?: "Install failed")
                                        }
                                    }
                                }
                            }) {
                                I({ classes("bi", "bi-download") })
                                Text("Install")
                            }
                        }
                    }

                    is InstallStep.Installing -> {
                        Div({
                            style {
                                padding(32.px)
                                textAlign("center")
                            }
                        }) {
                            I({
                                classes("bi", "bi-arrow-repeat")
                                style {
                                    fontSize(28.px)
                                    color(Color("#6366f1"))
                                    property("animation", "spin 1s linear infinite")
                                }
                            })
                            P({
                                style {
                                    color(Color("#71717a"))
                                    marginTop(12.px)
                                }
                            }) { Text("Installing skills...") }
                        }
                    }

                    is InstallStep.Done -> {
                        val response = currentStep.response

                        if (response.installed.isNotEmpty()) {
                            Div({
                                classes(DashboardStyles.successMessage)
                                style { marginBottom(16.px) }
                            }) {
                                Div({
                                    style {
                                        display(DisplayStyle.Flex)
                                        alignItems(AlignItems.Center)
                                        gap(8.px)
                                        marginBottom(8.px)
                                    }
                                }) {
                                    I({ classes("bi", "bi-check-circle") })
                                    Text("${response.installed.size} skill${if (response.installed.size != 1) "s" else ""} installed")
                                }
                                Div({
                                    style {
                                        display(DisplayStyle.Flex)
                                        flexDirection(FlexDirection.Column)
                                        gap(4.px)
                                    }
                                }) {
                                    response.installed.forEach { inst ->
                                        Div({
                                            style { fontSize(13.px) }
                                        }) {
                                            Span({ style { fontWeight("600") } }) { Text(inst.name) }
                                            Span({
                                                style {
                                                    color(Color("#71717a"))
                                                    marginLeft(8.px)
                                                    fontSize(12.px)
                                                }
                                            }) { Text(inst.destination) }
                                        }
                                    }
                                }
                            }
                        }

                        if (response.skipped.isNotEmpty()) {
                            Div({
                                style {
                                    padding(12.px)
                                    backgroundColor(Color("#f59e0b10"))
                                    borderRadius(8.px)
                                    marginBottom(16.px)
                                }
                            }) {
                                Div({
                                    style {
                                        fontSize(13.px)
                                        fontWeight("600")
                                        color(Color("#f59e0b"))
                                        marginBottom(8.px)
                                    }
                                }) { Text("Skipped") }
                                response.skipped.forEach { msg ->
                                    Div({
                                        style {
                                            fontSize(12.px)
                                            color(Color("#71717a"))
                                        }
                                    }) { Text(msg) }
                                }
                            }
                        }

                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.FlexEnd)
                            }
                        }) {
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                                onClick { onInstalled() }
                            }) { Text("Done") }
                        }
                    }

                    is InstallStep.Error -> {
                        Div({
                            classes(DashboardStyles.errorMessage)
                            style { marginBottom(16.px) }
                        }) {
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    gap(8.px)
                                }
                            }) {
                                I({ classes("bi", "bi-exclamation-circle") })
                                Text(currentStep.message)
                            }
                        }

                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.FlexEnd)
                                gap(8.px)
                            }
                        }) {
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonOutline)
                                onClick { onClose() }
                            }) { Text("Cancel") }
                            Button({
                                classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                                onClick { step = InstallStep.Input }
                            }) { Text("Try Again") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSkillCard(entry: SkillPreviewEntry) {
    Div({
        style {
            padding(14.px, 16.px)
            backgroundColor(Color("#0f0f1a"))
            borderRadius(8.px)
            property("border-left", "3px solid #6366f1")
        }
    }) {
        // Name + description
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(8.px)
                marginBottom(8.px)
            }
        }) {
            Span({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color("#e4e4e7"))
                }
            }) { Text(entry.name) }
        }
        Div({
            style {
                fontSize(13.px)
                color(Color("#a1a1aa"))
                marginBottom(8.px)
                lineHeight("1.5")
            }
        }) { Text(entry.description) }

        // Metadata badges
        Div({
            style {
                display(DisplayStyle.Flex)
                property("flex-wrap", "wrap")
                gap(6.px)
            }
        }) {
            entry.license?.let { license ->
                PreviewBadge("bi-file-earmark-text", license, "#71717a")
            }
            entry.compatibility?.let { compat ->
                PreviewBadge("bi-puzzle", compat, "#71717a")
            }
            if (entry.hasPythonDeps) {
                PreviewBadge("bi-filetype-py", "Python deps", "#3b82f6")
            }
            if (entry.requiredEnv.isNotEmpty()) {
                PreviewBadge("bi-key", "${entry.requiredEnv.size} env var${if (entry.requiredEnv.size != 1) "s" else ""}", "#f59e0b")
            }
            if (entry.allowedTools.isNotEmpty()) {
                PreviewBadge("bi-tools", "${entry.allowedTools.size} tool${if (entry.allowedTools.size != 1) "s" else ""}", "#6366f1")
            }
        }
    }
}

@Composable
private fun PreviewBadge(icon: String, text: String, color: String) {
    Span({
        style {
            display(DisplayStyle.Flex)
            property("display", "inline-flex")
            alignItems(AlignItems.Center)
            gap(4.px)
            padding(2.px, 8.px)
            backgroundColor(Color("${color}15"))
            borderRadius(4.px)
            fontSize(11.px)
            fontWeight("500")
            color(Color(color))
        }
    }) {
        I({
            classes("bi", icon)
            style { fontSize(10.px) }
        })
        Text(text)
    }
}
