package fraggle.coding.context

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Loads `AGENTS.md` context files from the project tree and a global location.
 *
 * Walking strategy, matching pi-coding-agent:
 *
 * 1. Start at [cwd] and walk upward until either:
 *    - A directory containing `.git` is reached (the project root — we stop AFTER
 *      collecting its AGENTS.md), or
 *    - The filesystem root is reached.
 * 2. In each directory, look for `AGENTS.md`. If absent, fall back to `CLAUDE.md`
 *    as pi does (useful for Claude-Code compatible projects). If both exist,
 *    `AGENTS.md` wins and `CLAUDE.md` is ignored in that directory.
 * 3. Order the results outer→inner: outermost ancestor first, [cwd] last. When
 *    the prompt is built, this means the nearest AGENTS.md appears last in the
 *    system prompt and has the most influence ("nearest wins semantically").
 * 4. If [globalAgentsFile] is provided and exists, it's prepended so global
 *    guidance comes before any project-specific rules.
 *
 * This is pure I/O with no LLM interaction. Callers pass the loaded files to
 * `SystemPromptBuilder` to compose them into the final prompt.
 */
class AgentsFileLoader(
    private val cwd: Path,
    private val globalAgentsFile: Path? = null,
) {
    /**
     * Walk the filesystem and return every AGENTS.md / CLAUDE.md file found,
     * in the order they should be injected into the system prompt (global
     * first, then outer ancestors, then [cwd] last).
     *
     * Files that fail to read are silently skipped. Empty files are included
     * — the calling builder decides whether to show them.
     */
    fun load(): List<LoadedContextFile> {
        val collected = mutableListOf<LoadedContextFile>()

        // Global first, if present.
        globalAgentsFile
            ?.takeIf { it.exists() && it.isRegularFile() }
            ?.let { path ->
                runCatching { LoadedContextFile(path, path.readText(), LoadedContextFile.Source.GLOBAL) }
                    .onSuccess(collected::add)
            }

        // Walk cwd → root, collect project files in inner-to-outer order.
        val projectFiles = mutableListOf<LoadedContextFile>()
        var current: Path? = cwd.toAbsolutePath().normalize()
        while (current != null && current.exists() && current.isDirectory()) {
            val file = findContextFile(current)
            if (file != null) {
                runCatching { LoadedContextFile(file, file.readText(), LoadedContextFile.Source.PROJECT) }
                    .onSuccess(projectFiles::add)
            }
            // Stop after we've processed a directory that contains .git — that's the project root.
            if (current.resolve(".git").exists()) break
            current = current.parent
        }

        // Reverse so outermost ancestor comes first (and cwd comes last).
        collected += projectFiles.asReversed()

        return collected.toList()
    }

    /**
     * In a single directory, prefer `AGENTS.md` over `CLAUDE.md`. Returns null
     * if neither file exists as a regular file.
     */
    private fun findContextFile(dir: Path): Path? {
        val agents = dir.resolve("AGENTS.md")
        if (agents.exists() && agents.isRegularFile()) return agents
        val claude = dir.resolve("CLAUDE.md")
        if (claude.exists() && claude.isRegularFile()) return claude
        return null
    }
}

/**
 * A context file loaded from disk, ready to be injected into the system prompt.
 */
data class LoadedContextFile(
    val path: Path,
    val content: String,
    val source: Source,
) {
    enum class Source {
        /** Loaded from the global config location ($FRAGGLE_ROOT/coding/AGENTS.md). */
        GLOBAL,

        /** Loaded from somewhere in the project tree (cwd → git root). */
        PROJECT,
    }
}
