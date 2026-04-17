package fraggle.coding.ui

import fraggle.tui.ui.Autocompletion
import fraggle.tui.ui.AutocompleteProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * Autocomplete source for `@` file references in the coding-agent editor.
 *
 * Walks the project working directory once per refresh window, skipping
 * standard build and dependency directories (see [DEFAULT_IGNORED_DIRS]),
 * and caches the result for [refreshTtlMs] milliseconds so rapid keystrokes
 * don't re-walk the filesystem. Fuzzy-matches the user's prefix against the
 * cached list and returns the top-scoring hits.
 *
 * Scoring (highest priority first):
 *  1. Exact filename start match (`src/Foo.kt` matches prefix `Foo`).
 *  2. Path-segment start match (`src/foo/bar.kt` matches prefix `foo/`).
 *  3. Substring match anywhere in the relative path.
 *
 * Directories rank above files at the same score, and directory entries
 * set [Autocompletion.continueCompletion] so the popup stays open after
 * selecting one.
 *
 * **Note:** this is a pure-JVM walker — it doesn't shell out to `fd` or
 * `git ls-files`. For very large repos (>50k files) you may want to plug in
 * a different provider; [cap] bounds the cached set for safety.
 *
 * @param root Project root to walk. Usually the coding agent's `workDir`.
 * @param cap Upper bound on the cached file set. Files beyond this are
 *   silently skipped — the walk terminates as soon as the cap is reached.
 * @param refreshTtlMs How long a cached walk is reused before re-scanning.
 *   Defaults to 2s — balances freshness against latency on each keystroke.
 * @param clock Injectable time source for tests.
 */
class FileAutocompleteProvider(
    private val root: Path,
    private val cap: Int = 10_000,
    private val refreshTtlMs: Long = 2_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutocompleteProvider {

    private data class Entry(
        val relativePath: String,
        val fileName: String,
        val isDirectory: Boolean,
    )

    @Volatile
    private var cached: List<Entry> = emptyList()

    @Volatile
    private var lastRefreshAt: Long = 0L

    override fun handlesTrigger(trigger: Char): Boolean = trigger == '@'

    override fun suggest(trigger: Char, prefix: String, limit: Int): List<Autocompletion> {
        if (!handlesTrigger(trigger)) return emptyList()
        val entries = entriesRefreshed()
        if (entries.isEmpty()) return emptyList()

        val scored = score(entries, prefix)
        return scored
            .take(limit)
            .map { it.toCompletion() }
    }

    // ── Caching ─────────────────────────────────────────────────────────────

    private fun entriesRefreshed(): List<Entry> {
        val now = clock()
        if (cached.isNotEmpty() && (now - lastRefreshAt) < refreshTtlMs) {
            return cached
        }
        val fresh = walk()
        cached = fresh
        lastRefreshAt = now
        return fresh
    }

    private fun walk(): List<Entry> {
        if (!root.isDirectory()) return emptyList()
        val out = ArrayList<Entry>(256)
        try {
            Files.walk(root).use { stream ->
                val iter = stream.iterator()
                while (iter.hasNext() && out.size < cap) {
                    val p = iter.next()
                    if (p == root) continue
                    if (shouldSkip(p)) {
                        // Skip the subtree: `Files.walk` doesn't support
                        // pruning, so we just filter. Large ignored trees
                        // cost walk time but don't appear in results.
                        continue
                    }
                    val isDir = p.isDirectory()
                    if (!isDir && !p.isRegularFile()) continue
                    val rel = p.relativeTo(root).toString().replace(java.io.File.separatorChar, '/')
                    if (rel.isEmpty()) continue
                    out += Entry(
                        relativePath = if (isDir) "$rel/" else rel,
                        fileName = p.name,
                        isDirectory = isDir,
                    )
                }
            }
        } catch (_: Throwable) {
            // Any IO error during walk (permissions, race with deletion) —
            // return what we've accumulated so far. Autocomplete degrades
            // gracefully rather than crashing the editor.
        }
        return out
    }

    private fun shouldSkip(path: Path): Boolean {
        val relFromRoot = try {
            path.relativeTo(root).toString().replace(java.io.File.separatorChar, '/')
        } catch (_: Throwable) {
            return true
        }
        // Ignore any path whose any segment is in the ignored list, or any
        // hidden-dir segment beyond the root itself.
        for (segment in relFromRoot.split('/')) {
            if (segment.isEmpty()) continue
            if (segment in DEFAULT_IGNORED_DIRS) return true
            if (segment.startsWith(".") && segment.length > 1 && segment != ".fraggle") return true
        }
        // Respect isHidden for OS-level hidden flags (Windows, HFS+ hidden
        // bit) even when the name isn't dot-prefixed.
        return try { path.isHidden() && path != root } catch (_: Throwable) { false }
    }

    // ── Scoring ────────────────────────────────────────────────────────────

    private data class Scored(
        val entry: Entry,
        val score: Int,
    ) {
        fun toCompletion(): Autocompletion {
            val label = entry.relativePath
            return Autocompletion(
                label = label,
                replacement = label,
                description = null,
                trailingSpace = !entry.isDirectory,
                continueCompletion = entry.isDirectory,
            )
        }
    }

    private fun score(entries: List<Entry>, prefix: String): List<Scored> {
        // Empty prefix — show entries sorted by depth (shallowest first)
        // then by name. Gives the user a sensible "top of project" preview.
        if (prefix.isEmpty()) {
            return entries.asSequence()
                .map { Scored(it, 0) }
                .sortedWith(compareBy({ it.entry.relativePath.count { c -> c == '/' } }, { it.entry.relativePath.lowercase() }))
                .toList()
        }

        val needle = prefix.lowercase()
        val result = ArrayList<Scored>(entries.size / 2)
        for (entry in entries) {
            val score = matchScore(entry, needle)
            if (score > 0) result += Scored(entry, score)
        }
        // Higher score first. Stable-sort: ties break by path length
        // (shorter is more likely what the user wants), then alphabetically.
        result.sortWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.entry.relativePath.length }
                .thenBy { it.entry.relativePath.lowercase() },
        )
        return result
    }

    private fun matchScore(entry: Entry, needle: String): Int {
        val rel = entry.relativePath.lowercase()
        val name = entry.fileName.lowercase()

        // 1. Filename starts with prefix → strongest signal.
        if (name.startsWith(needle)) {
            return 100 + directoryBoost(entry) + lengthBoost(entry)
        }
        // 2. Full relative path starts with prefix → also strong.
        if (rel.startsWith(needle)) {
            return 80 + directoryBoost(entry) + lengthBoost(entry)
        }
        // 3. Segment-start match anywhere in the path (e.g. `foo` matches
        //    `src/foo/bar.kt`).
        val segIdx = rel.indexOf("/$needle")
        if (segIdx >= 0) {
            return 60 + directoryBoost(entry) + lengthBoost(entry)
        }
        // 4. Substring match anywhere.
        if (rel.contains(needle)) {
            return 30 + directoryBoost(entry) + lengthBoost(entry)
        }
        return 0
    }

    private fun directoryBoost(entry: Entry): Int = if (entry.isDirectory) 10 else 0

    /**
     * Small preference for shallower paths and shorter filenames — a match
     * at `src/Foo.kt` outranks the same match at `deep/nested/path/Foo.kt`
     * even if the substring hit is identical.
     */
    private fun lengthBoost(entry: Entry): Int {
        val depth = entry.relativePath.count { it == '/' }
        return (10 - depth).coerceAtLeast(0)
    }

    companion object {
        /**
         * Directory segments whose subtrees are excluded from the walk.
         * These are the same "don't spider this" defaults `rg` / `fd` /
         * `git ls-files` conventions use. Case-sensitive.
         */
        val DEFAULT_IGNORED_DIRS: Set<String> = setOf(
            ".git",
            ".hg",
            ".svn",
            ".idea",
            ".vscode",
            ".gradle",
            "node_modules",
            "build",
            "dist",
            "out",
            "target",
            "__pycache__",
            "venv",
            ".venv",
            ".tox",
        )
    }
}
