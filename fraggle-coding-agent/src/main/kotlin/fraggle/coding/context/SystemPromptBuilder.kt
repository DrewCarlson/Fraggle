package fraggle.coding.context

/**
 * Composes the final system prompt sent to the LLM on every turn.
 *
 * Stateless and pure — every input is a parameter, every output is a string.
 * Callers (CLI, test fixtures) are responsible for loading files, capturing
 * workspace info, etc. This keeps the builder trivially testable and
 * independent of filesystem state.
 *
 * Order of the composed sections, top to bottom:
 * 1. [basePrompt] — typically the contents of `CODING_SYSTEM.md`, or the user's
 *    override (`.fraggle/coding/SYSTEM.md` / `$FRAGGLE_ROOT/coding/SYSTEM.md`).
 * 2. Workspace snapshot (cwd, git branch/head/status), if [workspace] is non-null.
 * 3. Context files (AGENTS.md, CLAUDE.md), in [contextFiles] order. The caller
 *    is responsible for ordering — [AgentsFileLoader.load] produces the
 *    expected order (global → outer → inner).
 * 4. Skill catalog (pre-rendered XML block from [SkillPromptFormatter]), if
 *    any skills are loaded.
 * 5. Available prompt templates (a flat list of `/name` entries with one-line
 *    descriptions so the model knows they exist).
 * 6. [appendText] — the user's optional `APPEND_SYSTEM.md`.
 *
 * Sections are separated by a single blank line. Empty sections are skipped
 * entirely so the prompt doesn't have stray headings for things that aren't there.
 */
object SystemPromptBuilder {
    fun build(
        basePrompt: String,
        workspace: WorkspaceSnapshot? = null,
        contextFiles: List<LoadedContextFile> = emptyList(),
        skillCatalog: String? = null,
        availableTemplates: List<TemplateDescriptor> = emptyList(),
        appendText: String? = null,
    ): String = buildString {
        append(basePrompt.trimEnd())

        if (workspace != null) {
            append("\n\n")
            append(renderWorkspace(workspace))
        }

        if (contextFiles.isNotEmpty()) {
            append("\n\n")
            append(renderContextFiles(contextFiles))
        }

        if (!skillCatalog.isNullOrBlank()) {
            append("\n\n")
            append("## Skills\n\n")
            append(skillCatalog.trimEnd())
        }

        if (availableTemplates.isNotEmpty()) {
            append("\n\n")
            append(renderTemplates(availableTemplates))
        }

        if (!appendText.isNullOrBlank()) {
            append("\n\n")
            append(appendText.trimEnd())
        }

        append('\n')
    }

    private fun renderWorkspace(w: WorkspaceSnapshot): String = buildString {
        appendLine("## Workspace")
        appendLine()
        appendLine("Current directory: `${w.cwd.toAbsolutePath()}`")
        if (w.gitBranch != null) appendLine("Git branch: `${w.gitBranch}`")
        if (w.gitHead != null) appendLine("Git HEAD: `${w.gitHead}`")
        if (!w.gitStatusShort.isNullOrBlank()) {
            appendLine()
            appendLine("Git status (short):")
            appendLine("```")
            appendLine(w.gitStatusShort)
            appendLine("```")
        }
    }.trimEnd()

    private fun renderContextFiles(files: List<LoadedContextFile>): String = buildString {
        appendLine("## Context from AGENTS.md files")
        appendLine()
        appendLine(
            "These files provide project-specific guidance. When sections conflict, " +
                "the one closest to the working directory takes precedence — it appears later below.",
        )
        for (file in files) {
            appendLine()
            appendLine("### ${file.path}")
            appendLine()
            appendLine(file.content.trim())
        }
    }.trimEnd()

    private fun renderTemplates(templates: List<TemplateDescriptor>): String = buildString {
        appendLine("## Available prompt templates")
        appendLine()
        appendLine("The user can invoke these with `/name` in the editor:")
        for (t in templates) {
            append("- `/${t.name}`")
            if (t.description != null) append(" — ${t.description}")
            append('\n')
        }
    }.trimEnd()
}

/**
 * A prompt template entry advertised to the model in the system prompt.
 * We deliberately don't inline the template body — the model sees only that
 * `/refactor` and `/review` exist, not what they expand to. The expansion
 * happens client-side when the user types `/refactor` in the editor.
 */
data class TemplateDescriptor(
    val name: String,
    val description: String? = null,
)
