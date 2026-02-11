package screens

import DashboardStyles
import DataState
import androidx.compose.runtime.*
import apiClient
import external.highlightElement
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.browser.document
import fraggle.documented.DocumentedValueType
import fraggle.documented.NestedClassDocumentationInfo
import fraggle.documented.PropertyDocumentationInfo
import fraggle.models.ConfigResponse
import fraggle.models.FraggleSettings
import fraggle.models.FraggleSettingsDoc
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import rememberRefreshableDataLoader

enum class ConfigViewMode {
    YAML,
    UI,
}

@Composable
fun SettingsScreen() {
    var viewMode by remember { mutableStateOf(ConfigViewMode.UI) }

    val (state, refresh) = rememberRefreshableDataLoader {
        apiClient.get("settings/config").body<ConfigResponse>()
    }

    Section({
        classes(DashboardStyles.section)
    }) {
        // Header with toggle
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
                    Text("Configuration")
                }
                DataStateLoadingSpinner(state)
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    gap(8.px)
                }
            }) {
                // View mode toggle
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall)
                    if (viewMode == ConfigViewMode.UI) {
                        classes(DashboardStyles.buttonPrimary)
                    } else {
                        classes(DashboardStyles.buttonOutline)
                    }
                    onClick { viewMode = ConfigViewMode.UI }
                }) {
                    I({ classes("bi", "bi-layout-text-sidebar") })
                    Text("UI View")
                }
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall)
                    if (viewMode == ConfigViewMode.YAML) {
                        classes(DashboardStyles.buttonPrimary)
                    } else {
                        classes(DashboardStyles.buttonOutline)
                    }
                    onClick { viewMode = ConfigViewMode.YAML }
                }) {
                    I({ classes("bi", "bi-code-slash") })
                    Text("YAML")
                }
                Button({
                    classes(DashboardStyles.button, DashboardStyles.buttonSmall, DashboardStyles.buttonOutline)
                    onClick { refresh() }
                }) {
                    I({ classes("bi", "bi-arrow-repeat") })
                }
            }
        }

        when (state) {
            is DataState.Loading -> {
                LoadingCard("Loading configuration...")
            }
            is DataState.Error -> {
                ErrorCard(state.message)
            }
            is DataState.Success -> {
                when (viewMode) {
                    ConfigViewMode.YAML -> YamlConfigView(state.data.yaml)
                    ConfigViewMode.UI -> UiConfigView(state.data.config)
                }
            }
        }
    }
}

@Composable
private fun YamlConfigView(yaml: String) {
    Div({
        classes(DashboardStyles.card)
    }) {
        // Use a unique key to track when we need to re-highlight
        val codeId = remember { "yaml-code-${yaml.hashCode()}" }

        Pre({
            style {
                margin(0.px)
                padding(0.px)
                backgroundColor(Color("#0d1117"))
                overflow("auto")
                property("max-height", "70vh")
                borderRadius(8.px)
            }
        }) {
            Code({
                id(codeId)
                classes("language-yaml", "hljs")
                style {
                    display(DisplayStyle.Block)
                    padding(24.px)
                    fontFamily("JetBrains Mono")
                    fontSize(13.px)
                    lineHeight("1.6")
                    property("tab-size", "2")
                }
            }) {
                Text(yaml)
            }
        }

        // Trigger highlight.js after render
        DisposableEffect(yaml) {
            val element = document.getElementById(codeId) as? HTMLElement
            if (element != null) {
                highlightElement(element)
            }
            onDispose { }
        }
    }
}

@Composable
private fun UiConfigView(config: FraggleSettings) {
    // Get documentation with current values
    val doc = FraggleSettingsDoc.withValues(config)

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            gap(24.px)
        }
    }) {
        // Render each nested class as a section
        doc.nestedClasses.forEach { nestedClass ->
            DocumentedSection(nestedClass)
        }
    }
}

@Composable
private fun DocumentedSection(nestedClass: NestedClassDocumentationInfo) {
    val doc = nestedClass.documentation
    val icon = doc.extras["icon"] ?: "bi-gear"

    Div({
        classes(DashboardStyles.card)
    }) {
        // Header with title and description
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(12.px)
                padding(16.px, 24.px)
                property("border-bottom", "1px solid #27273a")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    width(32.px)
                    height(32.px)
                    backgroundColor(Color("#6366f11a"))
                    borderRadius(8.px)
                    color(Color("#6366f1"))
                }
            }) {
                I({ classes("bi", icon) })
            }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    gap(2.px)
                }
            }) {
                H3({
                    style {
                        fontSize(16.px)
                        fontWeight("600")
                        color(Color("#e4e4e7"))
                        property("margin", "0")
                    }
                }) {
                    Text(doc.name)
                }
                if (doc.description.isNotBlank()) {
                    Span({
                        style {
                            fontSize(12.px)
                            color(Color("#71717a"))
                        }
                    }) {
                        Text(doc.description)
                    }
                }
            }
        }

        // Content
        Div({
            style {
                padding(20.px, 24.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
            }
        }) {
            // Render non-nested properties first
            val simpleProperties = doc.properties.filter { it.valueType != DocumentedValueType.NESTED_OBJECT }
            simpleProperties.forEachIndexed { index, prop ->
                if (index > 0) {
                    FieldSeparator()
                }
                DocumentedField(prop)
            }

            // Render nested classes as subsections
            doc.nestedClasses.forEachIndexed { index, nested ->
                if (index > 0 || simpleProperties.isNotEmpty()) {
                    Div({ style { height(16.px) } }) // Spacing before subsections
                }
                DocumentedSubSection(nested)
            }
        }
    }
}

@Composable
private fun DocumentedSubSection(nestedClass: NestedClassDocumentationInfo) {
    val doc = nestedClass.documentation
    val hasValues = doc.properties.any { it.currentValue != null }
    val subIcon = doc.extras["icon"] ?: "bi-chevron-right"

    Div({
        style {
            padding(16.px)
            backgroundColor(Color("#0f0f1a"))
            borderRadius(8.px)
        }
    }) {
        // Header
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(8.px)
                marginBottom(12.px)
            }
        }) {
            I({
                classes("bi", subIcon)
                style {
                    fontSize(14.px)
                    color(Color("#6366f1"))
                }
            })
            H4({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color("#a1a1aa"))
                    property("margin", "0")
                }
            }) {
                Text(doc.name)
            }
            if (doc.description.isNotBlank()) {
                InfoTooltip(doc.description)
            }
        }

        // Show "Not configured" message for nullable nested classes without values
        if (nestedClass.isNullable && !hasValues) {
            Div({
                style {
                    color(Color("#71717a"))
                    fontStyle("italic")
                    marginBottom(12.px)
                    fontSize(12.px)
                }
            }) {
                Text("Not configured (showing defaults)")
            }
        }

        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
            }
        }) {
            // Render simple properties
            val simpleProperties = doc.properties.filter { it.valueType != DocumentedValueType.NESTED_OBJECT }
            simpleProperties.forEachIndexed { index, prop ->
                if (index > 0) {
                    FieldSeparator()
                }
                DocumentedField(prop)
            }

            // Recurse for deeply nested classes
            doc.nestedClasses.forEachIndexed { index, nested ->
                if (index > 0 || simpleProperties.isNotEmpty()) {
                    Div({ style { height(12.px) } })
                }
                DocumentedSubSection(nested)
            }
        }
    }
}

@Composable
private fun FieldSeparator() {
    Div({
        style {
            height(1.px)
            backgroundColor(Color("#27273a"))
            margin(12.px, 0.px)
        }
    })
}

@Composable
private fun DocumentedField(prop: PropertyDocumentationInfo) {
    val displayValue = remember { formatValue(prop) }
    val valueColor = remember { getValueColor(prop) }

    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            gap(16.px)
            padding(4.px, 0.px)
        }
    }) {
        // Label with optional info tooltip
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                gap(6.px)
                flexShrink(0)
            }
        }) {
            Span({
                style {
                    fontSize(14.px)
                    color(Color("#a1a1aa"))
                }
            }) {
                Text(prop.name)
            }
            if (prop.description.isNotBlank()) {
                InfoTooltip(prop.description)
            }
        }

        // Value
        Span({
            style {
                fontSize(13.px)
                color(Color(valueColor))
                fontFamily("JetBrains Mono")
                textAlign("right")
                property("word-break", "break-all")
            }
        }) {
            Text(displayValue)
        }
    }
}

@Composable
private fun InfoTooltip(text: String) {
    Span({
        style {
            display(DisplayStyle.InlineBlock)
            cursor("help")
            position(Position.Relative)
        }
        title(text)
    }) {
        I({
            classes("bi", "bi-info-circle")
            style {
                fontSize(12.px)
                color(Color("#52525b"))
                property("transition", "color 0.15s")
            }
        })
    }
}

private fun formatValue(prop: PropertyDocumentationInfo): String {
    val value = prop.currentValue

    // Handle null values
    if (value == null || value == "null") {
        return if (prop.isNullable) "(not set)" else "(default)"
    }

    // Handle empty strings
    if (value.isBlank()) {
        return "(empty)"
    }

    // Mask secret fields
    if (prop.isSecret) {
        return "••••••••"
    }

    // Format based on type
    return when (prop.valueType) {
        DocumentedValueType.ENUM -> value.lowercase()
        DocumentedValueType.LIST -> if (value == "[]") "(none)" else value
        else -> value
    }
}

private fun getValueColor(prop: PropertyDocumentationInfo): String {
    val value = prop.currentValue

    // Null or empty values get muted color
    if (value == null || value == "null" || value.isBlank()) {
        return "#71717a"
    }

    // Boolean coloring
    if (prop.valueType == DocumentedValueType.BOOLEAN) {
        return if (value == "true") "#22c55e" else "#71717a"
    }

    // Secret fields with a value show green to indicate it's configured
    if (prop.isSecret) {
        return "#22c55e"
    }

    // Default color
    return "#e4e4e7"
}
