package org.drewcarlson.fraggle.signal

/**
 * Represents a text style range for Signal formatting.
 */
data class TextStyle(
    val start: Int,
    val length: Int,
    val style: StyleType,
) {
    /**
     * Convert to signal-cli format: "start:length:STYLE"
     */
    fun toCliFormat(): String = "$start:$length:${style.name}"
}

enum class StyleType {
    BOLD,
    ITALIC,
    SPOILER,
    STRIKETHROUGH,
    MONOSPACE,
}

/**
 * Result of formatting text, containing the plain text and style ranges.
 */
data class FormattedText(
    val text: String,
    val styles: List<TextStyle>,
) {
    /**
     * Check if there are any styles to apply.
     */
    fun hasStyles(): Boolean = styles.isNotEmpty()
}

/**
 * Parses a simple markdown-like format and converts it to Signal text styles.
 *
 * Supported syntax:
 * - `**bold**` → BOLD
 * - `*italic*` → ITALIC
 * - `~~strikethrough~~` → STRIKETHROUGH
 * - `||spoiler||` → SPOILER
 * - `` `monospace` `` → MONOSPACE
 *
 * Note: Nested styles are not supported. The formatter processes styles in order
 * of precedence: bold (**), strikethrough (~~), spoiler (||), italic (*), monospace (`).
 */
object TextFormatter {

    // Regex patterns for each style (ordered by precedence)
    private val patterns = listOf(
        // Bold: **text**
        Regex("""\*\*(.+?)\*\*""") to StyleType.BOLD,
        // Strikethrough: ~~text~~
        Regex("""~~(.+?)~~""") to StyleType.STRIKETHROUGH,
        // Spoiler: ||text||
        Regex("""\|\|(.+?)\|\|""") to StyleType.SPOILER,
        // Italic: *text* (single, not double)
        Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""") to StyleType.ITALIC,
        // Monospace: `text`
        Regex("""`(.+?)`""") to StyleType.MONOSPACE,
    )

    /**
     * Parse formatted text and extract styles.
     *
     * @param input The input text with markdown-like formatting
     * @return FormattedText containing plain text and style ranges
     */
    fun parse(input: String): FormattedText {
        if (input.isBlank()) {
            return FormattedText(input, emptyList())
        }

        val styles = mutableListOf<PendingStyle>()
        var workingText = input

        // Process each pattern and collect styles
        for ((pattern, styleType) in patterns) {
            workingText = processPattern(workingText, pattern, styleType, styles)
        }

        // Convert pending styles to final styles with correct positions
        val finalStyles = styles.map { pending ->
            TextStyle(
                start = pending.start,
                length = pending.text.length,
                style = pending.style,
            )
        }

        return FormattedText(workingText, finalStyles)
    }

    private data class PendingStyle(
        val start: Int,
        val text: String,
        val style: StyleType,
    )

    private fun processPattern(
        text: String,
        pattern: Regex,
        styleType: StyleType,
        styles: MutableList<PendingStyle>,
    ): String {
        var result = text
        var offset = 0

        val matches = pattern.findAll(text).toList()

        for (match in matches) {
            val fullMatch = match.value
            val content = match.groupValues[1]
            val originalStart = match.range.first

            // Calculate the actual position after previous replacements
            val adjustedStart = originalStart - offset

            // Replace the formatted text with just the content
            result = result.substring(0, adjustedStart) + content + result.substring(adjustedStart + fullMatch.length)

            // Record the style
            styles.add(PendingStyle(adjustedStart, content, styleType))

            // Update offset for next replacement
            offset += fullMatch.length - content.length
        }

        return result
    }

    /**
     * Format a message for signal-cli, returning the plain text and style arguments.
     *
     * @param input The input text with markdown-like formatting
     * @return Pair of (plain text, list of style arguments for --text-style)
     */
    fun formatForCli(input: String): Pair<String, List<String>> {
        val formatted = parse(input)
        return formatted.text to formatted.styles.map { it.toCliFormat() }
    }
}
