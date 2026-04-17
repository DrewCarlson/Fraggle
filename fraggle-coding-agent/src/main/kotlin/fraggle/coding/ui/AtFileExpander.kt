package fraggle.coding.ui

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Expands `@path` file references in a user message into inlined file
 * content, matching the behavior of `fraggle code`'s CLI positional `@file`
 * arguments.
 *
 * A valid `@path` reference is `@` followed by a path token, where `@` is
 * either at the start of the input or immediately preceded by whitespace.
 * The path token runs up to the next whitespace character. Non-file `@`
 * uses (email addresses, `@mentions` with no following path, etc.) are
 * detected heuristically and left untouched — the expander only fires when
 * the token actually resolves to an existing regular file.
 *
 * Expansion format matches the CLI:
 *
 * ```
 * `<relative path>`:
 * ```
 * <file contents>
 * ```
 * ```
 *
 * Multiple references in one message expand in order. Missing / unreadable
 * references are left as plain text in the output and recorded in
 * [ExpansionResult.unresolved] so the caller can surface a warning.
 *
 * Local LLM providers don't natively understand `@path` syntax the way
 * cloud assistants do, so we inline content client-side before sending.
 */
object AtFileExpander {

    /** Result of an expansion pass. */
    data class ExpansionResult(
        /** Input text with every resolved `@path` token replaced by an inlined code block. */
        val expandedText: String,
        /** Relative paths (as the user wrote them) that resolved to a readable file. */
        val resolved: List<String>,
        /** Path references that couldn't be resolved (missing file, directory, read error). */
        val unresolved: List<String>,
    ) {
        val isChanged: Boolean get() = resolved.isNotEmpty()
    }

    /**
     * Expand `@path` references in [text]. Paths are resolved against [cwd]
     * for relative references; absolute paths are used verbatim.
     *
     * Size cap: files larger than [maxFileBytes] are treated as unresolved
     * to avoid blowing out the LLM context. Defaults to 256 KiB — generous
     * for source files, strict enough to catch stray log-file references.
     */
    fun expand(
        text: String,
        cwd: Path,
        maxFileBytes: Long = 256 * 1024,
    ): ExpansionResult {
        if (text.isEmpty() || '@' !in text) {
            return ExpansionResult(text, emptyList(), emptyList())
        }

        val out = StringBuilder(text.length)
        val resolved = mutableListOf<String>()
        val unresolved = mutableListOf<String>()

        var i = 0
        while (i < text.length) {
            val c = text[i]

            // Only consider `@` as a trigger when at the start of input or
            // after whitespace. That excludes email addresses and most
            // `@mentions` in prose.
            val triggerValid = c == '@' && (i == 0 || text[i - 1].isWhitespace())
            if (!triggerValid) {
                out.append(c)
                i++
                continue
            }

            // Collect the path token: everything up to the next whitespace.
            // Zero-length tokens ("@ " or "@" at EOF) aren't references.
            val pathStart = i + 1
            var pathEnd = pathStart
            while (pathEnd < text.length && !text[pathEnd].isWhitespace()) {
                pathEnd++
            }
            val rawPath = text.substring(pathStart, pathEnd)
            if (rawPath.isEmpty()) {
                out.append(c)
                i++
                continue
            }

            val expanded = tryInline(rawPath, cwd, maxFileBytes)
            if (expanded != null) {
                out.append(expanded)
                resolved += rawPath
            } else {
                // Leave the original `@path` text in place so the user can
                // see what didn't resolve. Record it so the caller can
                // surface a warning.
                out.append('@').append(rawPath)
                unresolved += rawPath
            }
            i = pathEnd
        }

        return ExpansionResult(
            expandedText = out.toString(),
            resolved = resolved,
            unresolved = unresolved,
        )
    }

    /**
     * Read the file and format it as an inlined code block. Returns null if
     * the path doesn't resolve to a readable file under [maxFileBytes].
     */
    private fun tryInline(rawPath: String, cwd: Path, maxFileBytes: Long): String? {
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

        val contents = try {
            path.readText()
        } catch (_: Throwable) {
            return null
        }

        // Mirror the CLI positional-arg format verbatim so behavior is
        // identical whether the user typed `@file` in the editor or passed
        // it on the command line.
        return buildString {
            append('`').append(rawPath).append("`:\n")
            append("```\n")
            append(contents)
            if (!contents.endsWith("\n")) append('\n')
            append("```")
        }
    }
}
