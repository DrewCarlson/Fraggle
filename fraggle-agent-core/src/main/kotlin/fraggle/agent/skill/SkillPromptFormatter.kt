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

    /**
     * Render available skills into the system prompt.
     *
     * @param skills All loaded skills (hidden ones are filtered automatically).
     * @param envChecker Optional callback that returns whether a given env var is
     *   configured for a skill: `(skillName, varName) -> Boolean`. Used to show
     *   the agent which required env vars are ready vs missing.
     */
    fun format(
        skills: List<Skill>,
        envChecker: (skillName: String, varName: String) -> Boolean = { _, _ -> false },
    ): String {
        val visible = skills.filterNot { it.disableModelInvocation }
        if (visible.isEmpty()) return ""

        val hasPythonSkills = visible.any { it.hasPythonDeps || it.requiredEnv.isNotEmpty() }

        return buildString {
            appendLine("<available_skills>")
            for (skill in visible) {
                appendLine("  <skill>")
                appendLine("    <name>${escapeXml(skill.name)}</name>")
                appendLine("    <description>${escapeXml(skill.description)}</description>")
                appendLine("    <location>${escapeXml(skill.filePath.toString())}</location>")
                if (skill.hasPythonDeps) {
                    appendLine("    <has_python>true</has_python>")
                }
                if (skill.requiredEnv.isNotEmpty()) {
                    appendLine("    <env>")
                    for (varName in skill.requiredEnv) {
                        val configured = envChecker(skill.name, varName)
                        appendLine("      <var name=\"${escapeXml(varName)}\" configured=\"$configured\"/>")
                    }
                    appendLine("    </env>")
                }
                appendLine("  </skill>")
            }
            appendLine("</available_skills>")
            appendLine()
            append("Use the read_file tool to load a skill's file when the task matches its description. ")
            append("Relative paths inside a skill are resolved against its containing directory.")
            if (hasPythonSkills) {
                appendLine()
                append("When a skill has Python support, use execute_command with skill=\"<name>\" to run its scripts. ")
                append("This activates the skill's Python virtual environment and injects configured secrets automatically. ")
                append("Never ask the user for secret values — they are injected by the system.")
            }
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
