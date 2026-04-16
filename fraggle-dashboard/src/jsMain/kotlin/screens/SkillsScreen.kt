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
        val previouslyIgnored: Set<String>,
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

            val currentStep = step

            // Shared selection state for the Preview step, hoisted here so the
            // top toolbar and the scrollable body agree on what's selected.
            val previewSelected = if (currentStep is InstallStep.Preview) {
                remember(currentStep.sourceLabel, currentStep.skills) {
                    mutableStateOf(
                        currentStep.skills
                            .map { it.name }
                            .filter { it !in currentStep.previouslyIgnored }
                            .toSet(),
                    )
                }
            } else null

            // Fixed-top action area (outside scrolling body)
            when (currentStep) {
                is InstallStep.Input, is InstallStep.Previewing -> {
                    val isPreviewing = currentStep is InstallStep.Previewing
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Stretch)
                            gap(8.px)
                            marginBottom(12.px)
                            property("flex-shrink", "0")
                        }
                    }) {
                        Input(type = InputType.Text) {
                            classes(DashboardStyles.input)
                            placeholder("owner/repo, GitHub URL, git URL, or local path")
                            value(sourceInput)
                            onInput { sourceInput = it.value }
                            if (isPreviewing) {
                                attr("disabled", "true")
                            }
                            style { flex(1) }
                        }
                        Button({
                            classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
                            style { property("flex-shrink", "0") }
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
                                                    previouslyIgnored = preview.previouslyIgnored.toSet(),
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
                    PreviewToolbar(
                        preview = currentStep,
                        selectedState = previewSelected!!,
                        onInstall = { ignoredNames ->
                            step = InstallStep.Installing
                            scope.launch {
                                step = try {
                                    val response = apiClient.post("skills/install") {
                                        contentType(ContentType.Application.Json)
                                        setBody(
                                            SkillInstallRequest(
                                                source = sourceInput.trim(),
                                                ignored = ignoredNames.toList(),
                                            ),
                                        )
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
                        },
                    )
                }
                else -> Unit
            }

            // Scrollable body
            Div({
                style {
                    flex(1)
                    property("overflow-y", "auto")
                    property("min-height", "0")
                }
            }) {
                when (currentStep) {
                    is InstallStep.Input, is InstallStep.Previewing -> {
                        Div({
                            style {
                                fontSize(12.px)
                                color(Color("#71717a"))
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

                    is InstallStep.Preview -> {
                        PreviewStepContent(
                            preview = currentStep,
                            selectedState = previewSelected!!,
                        )
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
                            }
                        }) {
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

/**
 * Fixed-top toolbar for the Preview step: source label + Install button.
 * Lives outside the scrollable body so the primary action is always visible.
 */
@Composable
private fun PreviewToolbar(
    preview: InstallStep.Preview,
    selectedState: MutableState<Set<String>>,
    onInstall: (Set<String>) -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            gap(8.px)
            marginBottom(12.px)
            property("flex-shrink", "0")
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(8.px)
                flex(1)
                minWidth(0.px)
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
                    property("flex-shrink", "0")
                }
            })
            Code({
                style {
                    fontSize(13.px)
                    color(Color("#e4e4e7"))
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                }
            }) { Text(preview.sourceLabel) }
        }
        Button({
            classes(DashboardStyles.button, DashboardStyles.buttonPrimary)
            style { property("flex-shrink", "0") }
            if (selectedState.value.isEmpty()) {
                attr("disabled", "true")
                style { property("opacity", "0.6") }
            }
            onClick {
                val allNames = preview.skills.map { it.name }.toSet()
                val ignored = allNames - selectedState.value
                onInstall(ignored)
            }
        }) {
            I({ classes("bi", "bi-download") })
            val count = selectedState.value.size
            Text(
                if (count == preview.skills.size) "Install"
                else "Install ($count)",
            )
        }
    }
}

/**
 * Scrollable body for the Preview step: select-all/none header, skill
 * checklist, and any diagnostics. The primary Install action is hoisted to
 * [PreviewToolbar].
 */
@Composable
private fun PreviewStepContent(
    preview: InstallStep.Preview,
    selectedState: MutableState<Set<String>>,
) {
    // Header row with select-all/none
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            marginBottom(8.px)
        }
    }) {
        Span({
            style {
                fontSize(13.px)
                fontWeight("600")
                color(Color("#e4e4e7"))
            }
        }) {
            val total = preview.skills.size
            val picked = selectedState.value.size
            Text("$picked of $total selected")
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                gap(8.px)
            }
        }) {
            Span({
                style {
                    fontSize(12.px)
                    color(Color("#6366f1"))
                    cursor("pointer")
                }
                onClick { selectedState.value = preview.skills.map { it.name }.toSet() }
            }) { Text("Select all") }
            Span({
                style {
                    fontSize(12.px)
                    color(Color("#6366f1"))
                    cursor("pointer")
                }
                onClick { selectedState.value = emptySet() }
            }) { Text("Select none") }
        }
    }

    if (preview.previouslyIgnored.isNotEmpty()) {
        Div({
            style {
                fontSize(12.px)
                color(Color("#71717a"))
                marginBottom(12.px)
            }
        }) {
            Text("Pre-filled from prior install. Untick a skill to exclude it from this and future updates.")
        }
    }

    // Skills list
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            gap(8.px)
            marginBottom(16.px)
        }
    }) {
        preview.skills.forEach { entry ->
            val isSelected = entry.name in selectedState.value
            PreviewSkillCard(
                entry = entry,
                isSelected = isSelected,
                onToggle = {
                    selectedState.value = if (isSelected) {
                        selectedState.value - entry.name
                    } else {
                        selectedState.value + entry.name
                    }
                },
            )
        }
    }

    // Diagnostics
    if (preview.diagnostics.isNotEmpty()) {
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
                preview.diagnostics.forEach { diag ->
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
}

@Composable
private fun PreviewSkillCard(
    entry: SkillPreviewEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val accentColor = if (isSelected) "#6366f1" else "#52525b"
    val opacity = if (isSelected) "1.0" else "0.55"

    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.FlexStart)
            gap(12.px)
            padding(14.px, 16.px)
            backgroundColor(Color("#0f0f1a"))
            borderRadius(8.px)
            property("border-left", "3px solid $accentColor")
            property("transition", "opacity 0.15s ease")
            property("opacity", opacity)
            cursor("pointer")
        }
        onClick { onToggle() }
    }) {
        // Checkbox
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.Center)
                width(20.px)
                height(20.px)
                flexShrink(0)
                borderRadius(4.px)
                marginTop(2.px)
                if (isSelected) {
                    backgroundColor(Color(accentColor))
                } else {
                    backgroundColor(Color.transparent)
                    border(1.px, LineStyle.Solid, Color("#52525b"))
                }
                property("transition", "all 0.15s ease")
            }
        }) {
            if (isSelected) {
                I({
                    classes("bi", "bi-check")
                    style {
                        color(Color("#fff"))
                        fontSize(14.px)
                        fontWeight("700")
                    }
                })
            }
        }

        // Skill content
        Div({
            style {
                flex(1)
                minWidth(0.px)
            }
        }) {
            Div({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color("#e4e4e7"))
                    marginBottom(4.px)
                }
            }) { Text(entry.name) }

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
                    PreviewBadge(
                        "bi-key",
                        "${entry.requiredEnv.size} env var${if (entry.requiredEnv.size != 1) "s" else ""}",
                        "#f59e0b",
                    )
                }
                if (entry.allowedTools.isNotEmpty()) {
                    PreviewBadge(
                        "bi-tools",
                        "${entry.allowedTools.size} tool${if (entry.allowedTools.size != 1) "s" else ""}",
                        "#6366f1",
                    )
                }
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
