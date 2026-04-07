package fraggle.agent

/**
 * Strips reasoning/thinking content from LLM responses.
 *
 * Supported tag patterns:
 * - `<think>...</think>`
 * - `<thinking>...</thinking>`
 * - `<reasoning>...</reasoning>`
 * - `<reflection>...</reflection>`
 * - `<|channel>thought\n...<channel|>`
 */
object ReasoningContentFilter {

    private val REASONING_TAG_PATTERN = Regex(
        """<(think|thinking|reasoning|reflection)>.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // Gemma 4 reasoning: <|channel>thought\n...<channel|>
    private val GEMMA_CHANNEL_PATTERN = Regex(
        """<\|channel>thought\n.*?<channel\|>""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val EXCESS_NEWLINES = Regex("""\n{3,}""")

    /**
     * Remove reasoning tag blocks from the given text.
     * Returns the text with reasoning content removed and excess whitespace collapsed.
     */
    fun strip(text: String): String {
        var result = text
        var changed = false

        if (REASONING_TAG_PATTERN.containsMatchIn(result)) {
            result = result.replace(REASONING_TAG_PATTERN, "")
            changed = true
        }
        if (GEMMA_CHANNEL_PATTERN.containsMatchIn(result)) {
            result = result.replace(GEMMA_CHANNEL_PATTERN, "")
            changed = true
        }

        if (!changed) return text
        return result
            .replace(EXCESS_NEWLINES, "\n\n")
            .trim()
    }
}
