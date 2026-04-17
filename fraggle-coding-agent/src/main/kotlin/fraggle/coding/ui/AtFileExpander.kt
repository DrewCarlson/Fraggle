package fraggle.coding.ui

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Expands `@path` file references in a user message by prepending a
 * `<context>…</context>` block with the referenced file contents, while
 * leaving the user's original text (including the `@path` tokens
 * themselves) untouched.
 *
 * This matches how pi and Claude-Code present file references in the UI:
 * the chat history shows what the user actually typed, and the LLM receives
 * the full file contents as surrounding context. Fraggle talks to a local
 * LLM so we can't rely on the provider to expand references server-side;
 * instead we inline the content ourselves but keep the display clean via a
 * known wrapper that the TUI renderer strips before rendering.
 *
 * ### Expansion format
 *
 * ```
 * <context>
 * `src/Foo.kt`:
 * ```
 * <file contents>
 * ```
 *
 * `src/Bar.kt`:
 * ```
 * <file contents>
 * ```
 * </context>
 *
 * <user's original text, including the @path tokens>
 * ```
 *
 * The wrapper uses the same XML-tag convention as the skill inclusion
 * (`<skill …>…</skill>`) so both features compose the same way. [stripContextBlock]
 * is the inverse operation used at display time.
 *
 * ### Trigger rules
 *
 * A `@path` is treated as a file reference only when `@` is at the start of
 * the input or immediately preceded by whitespace. This excludes email
 * addresses (`user@example.com`) and `@mentions` in prose.
 *
 * ### Deduplication
 *
 * Duplicate `@path` references in one message (e.g. `compare @foo to @foo`)
 * contribute a single context block entry. The user's literal text still
 * contains both occurrences.
 *
 * ### Limits
 *
 * Files larger than [DEFAULT_MAX_FILE_BYTES] are treated as unresolved —
 * local LLMs have hard context limits and a stray `@big.log` reference
 * should not blow the whole window. Unresolved references are recorded
 * separately so callers can surface a warning.
 */
object AtFileExpander {

    /** Default file-size cap for a single reference, in bytes. 256 KiB. */
    const val DEFAULT_MAX_FILE_BYTES: Long = 256 * 1024

    /** XML-tag marker that wraps the context block. */
    private const val CONTEXT_OPEN = "<context>"
    private const val CONTEXT_CLOSE = "</context>"

    /** Result of a single expansion pass. */
    data class ExpansionResult(
        /** Text to send to the LLM: `<context>…</context>\n\n<original>` when any ref resolved, or the original verbatim when nothing resolved. */
        val expandedText: String,
        /** Relative paths (as the user wrote them) that resolved to a readable file. Deduplicated, in first-seen order. */
        val resolved: List<String>,
        /** Path references that couldn't be resolved (missing, directory, oversize, read error). Deduplicated, in first-seen order. */
        val unresolved: List<String>,
    ) {
        /** True when at least one reference resolved and the expansion text differs from the input. */
        val isChanged: Boolean get() = resolved.isNotEmpty()
    }

    /**
     * Expand `@path` references in [text]. Paths are resolved against [cwd]
     * for relative references; absolute paths are used verbatim.
     *
     * The user's literal text is preserved verbatim in the result — only
     * the prepended `<context>` block adds content. See [stripContextBlock]
     * for the inverse.
     */
    fun expand(
        text: String,
        cwd: Path,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    ): ExpansionResult {
        if (text.isEmpty() || '@' !in text) {
            return ExpansionResult(text, emptyList(), emptyList())
        }

        val references = findReferences(text)
        if (references.isEmpty()) {
            return ExpansionResult(text, emptyList(), emptyList())
        }

        val resolved = LinkedHashMap<String, String>() // rawPath → file contents
        val unresolved = LinkedHashSet<String>()
        for (rawPath in references) {
            if (resolved.containsKey(rawPath) || rawPath in unresolved) continue
            val contents = tryReadFile(rawPath, cwd, maxFileBytes)
            if (contents != null) resolved[rawPath] = contents else unresolved += rawPath
        }

        if (resolved.isEmpty()) {
            return ExpansionResult(text, emptyList(), unresolved.toList())
        }

        val contextBlock = buildContextBlock(resolved)
        val expandedText = "$contextBlock\n\n$text"

        return ExpansionResult(
            expandedText = expandedText,
            resolved = resolved.keys.toList(),
            unresolved = unresolved.toList(),
        )
    }

    /**
     * Remove a leading `<context>…</context>\n\n` block from [text], if one is
     * present. Idempotent: messages with no wrapper pass through unchanged,
     * and repeated calls don't eat extra leading whitespace.
     *
     * Used by the TUI display path to hide the expansion from the rendered
     * user message. The wrapper exists only for the LLM's benefit.
     */
    fun stripContextBlock(text: String): String {
        if (!text.startsWith(CONTEXT_OPEN)) return text
        val close = text.indexOf(CONTEXT_CLOSE, CONTEXT_OPEN.length)
        if (close < 0) return text
        var tail = close + CONTEXT_CLOSE.length
        // Strip one or more newlines between the closing tag and the user text.
        while (tail < text.length && text[tail] == '\n') tail++
        return text.substring(tail)
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Collect every `@path` token from [text] in source order, with duplicates.
     * A token is `@` (at start of input or after whitespace) followed by a
     * non-whitespace path segment.
     */
    private fun findReferences(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val triggerValid = c == '@' && (i == 0 || text[i - 1].isWhitespace())
            if (!triggerValid) {
                i++
                continue
            }
            val pathStart = i + 1
            var pathEnd = pathStart
            while (pathEnd < text.length && !text[pathEnd].isWhitespace()) pathEnd++
            if (pathEnd > pathStart) {
                out += text.substring(pathStart, pathEnd)
            }
            i = pathEnd
        }
        return out
    }

    private fun tryReadFile(rawPath: String, cwd: Path, maxFileBytes: Long): String? {
        val path = try {
            val p = Path.of(rawPath)
            if (p.isAbsolute) p else cwd.resolve(rawPath)
        } catch (_: Throwable) {
            return null
        }
        if (!path.exists() || !path.isRegularFile()) return null
        val size = try {
            path.toFile().length()
        } catch (_: Throwable) {
            return null
        }
        if (size > maxFileBytes) return null
        return try {
            path.readText()
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildContextBlock(resolved: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append(CONTEXT_OPEN).append('\n')
        var first = true
        for ((path, contents) in resolved) {
            if (!first) sb.append('\n')
            first = false
            sb.append('`').append(path).append("`:\n")
            sb.append("```\n")
            sb.append(contents)
            if (!contents.endsWith("\n")) sb.append('\n')
            sb.append("```\n")
        }
        sb.append(CONTEXT_CLOSE)
        return sb.toString()
    }
}
