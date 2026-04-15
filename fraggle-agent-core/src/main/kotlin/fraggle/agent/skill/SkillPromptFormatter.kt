package fraggle.agent.skill

/**
 * Renders a skill catalog into the XML block defined by the
 * [agentskills.io integration guide](https://agentskills.io/integrate-skills).
 *
 * Only skills visible to the model are included — entries with
 * `disable-model-invocation: true` are hidden from the catalog but still reachable
 * via explicit `/skill:name` commands.
 *
 * The rendered block is designed to be injected near the top of the system prompt.
 * It carries only metadata (name, description, absolute path); the model loads the
 * full SKILL.md body on demand using the `read_file` tool. This is the "progressive
 * disclosure" pattern from the agentskills.io spec.
 *
 * An empty skill list renders as an empty string, so callers can unconditionally
 * append the result without guarding for the disabled/empty case.
 */
object SkillPromptFormatter {

    fun format(skills: List<Skill>): String {
        val visible = skills.filterNot { it.disableModelInvocation }
        if (visible.isEmpty()) return ""

        return buildString {
            appendLine("<available_skills>")
            for (skill in visible) {
                appendLine("  <skill>")
                appendLine("    <name>${escapeXml(skill.name)}</name>")
                appendLine("    <description>${escapeXml(skill.description)}</description>")
                appendLine("    <location>${escapeXml(skill.filePath.toString())}</location>")
                appendLine("  </skill>")
            }
            appendLine("</available_skills>")
            appendLine()
            append("Use the read_file tool to load a skill's file when the task matches its description. ")
            append("Relative paths inside a skill are resolved against its containing directory.")
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
