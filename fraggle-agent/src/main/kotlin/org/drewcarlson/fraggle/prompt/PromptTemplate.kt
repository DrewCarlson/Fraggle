package org.drewcarlson.fraggle.prompt

/**
 * A parsed markdown prompt template with section extraction and variable substitution.
 *
 * Sections are delimited by `## Heading` markers. Content before the first heading
 * is stored under an empty-string key. Variables use `{{name}}` syntax.
 */
class PromptTemplate private constructor(
    private val sections: Map<String, String>,
) {
    companion object {
        private val SECTION_PATTERN = Regex("^## (.+)$", RegexOption.MULTILINE)
        private val VARIABLE_PATTERN = Regex("\\{\\{(\\w+)}}") // Change \\w to [\\w.] if dotted names needed

        /**
         * Parse markdown content into a [PromptTemplate].
         */
        fun parse(content: String): PromptTemplate {
            return PromptTemplate(parseSections(content))
        }

        private fun parseSections(content: String): Map<String, String> {
            val sections = mutableMapOf<String, String>()
            val matches = SECTION_PATTERN.findAll(content).toList()

            if (matches.isEmpty()) {
                // No headings — entire content is the default section
                sections[""] = content.trim()
                return sections
            }

            // Content before first heading
            val preamble = content.substring(0, matches.first().range.first).trim()
            if (preamble.isNotEmpty()) {
                sections[""] = preamble
            }

            for ((index, match) in matches.withIndex()) {
                val heading = match.groupValues[1].trim().lowercase()
                val bodyStart = match.range.last + 1
                val bodyEnd = if (index + 1 < matches.size) matches[index + 1].range.first else content.length
                val body = content.substring(bodyStart, bodyEnd).trim()
                sections[heading] = body
            }

            return sections
        }

        private fun substituteVariables(text: String, variables: Map<String, String>): String {
            return VARIABLE_PATTERN.replace(text) { matchResult ->
                val name = matchResult.groupValues[1]
                variables[name] ?: matchResult.value // Leave unresolved placeholders as-is
            }
        }
    }

    /**
     * Render the entire template with optional variable substitution.
     */
    fun render(variables: Map<String, String> = emptyMap()): String {
        val full = buildString {
            for ((index, entry) in sections.entries.withIndex()) {
                if (index > 0) append("\n\n")
                val (heading, body) = entry
                if (heading.isNotEmpty()) {
                    append("## $heading\n\n")
                }
                append(body)
            }
        }
        return if (variables.isEmpty()) full else substituteVariables(full, variables)
    }

    /**
     * Render a single section by heading name (case-insensitive).
     * Returns `null` if the section does not exist.
     */
    fun renderSection(name: String, variables: Map<String, String> = emptyMap()): String? {
        val body = sections[name.lowercase()] ?: return null
        return if (variables.isEmpty()) body else substituteVariables(body, variables)
    }

    /**
     * Check whether a section exists (case-insensitive).
     */
    fun hasSection(name: String): Boolean = sections.containsKey(name.lowercase())

    /**
     * Return all section names (lowercase). Includes empty string if preamble content exists.
     */
    fun sectionNames(): Set<String> = sections.keys
}
