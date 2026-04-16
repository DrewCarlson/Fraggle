package fraggle.coding.prompt

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Loads Markdown prompt templates from a directory.
 *
 * Each `*.md` file becomes a [PromptTemplate] whose [PromptTemplate.name] is
 * the filename without extension. The user invokes a template in the editor
 * by typing `/name`, which expands to the template body with any `{{var}}`
 * placeholders filled in from editor-prompted values.
 *
 * The loader is non-recursive: only files directly under the given directory
 * are picked up.
 * Unreadable files and templates with malformed frontmatter (currently: no
 * frontmatter format; just raw Markdown) are skipped with a warning — no
 * exceptions bubble out of [loadFromDirectory], so a single bad file can't
 * prevent the rest from loading.
 */
class PromptTemplateLoader {

    /**
     * Walk [dir] and return every template file as a [PromptTemplate].
     * Returns an empty list if [dir] doesn't exist or isn't a directory.
     * Template names are lowercased so `/Review` and `/review` resolve to
     * the same file (prevents surprising case-sensitivity on macOS).
     */
    fun loadFromDirectory(dir: Path): List<PromptTemplate> {
        if (!dir.isDirectory()) return emptyList()
        val results = mutableListOf<PromptTemplate>()
        Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension.equals("md", ignoreCase = true) }
                .forEach { path ->
                    runCatching {
                        val raw = path.readText()
                        results += PromptTemplate(
                            name = path.nameWithoutExtension.lowercase(),
                            content = raw,
                            sourcePath = path,
                            variables = extractVariables(raw),
                        )
                    }
                }
        }
        return results.sortedBy { it.name }
    }

    /**
     * Find every `{{var}}` placeholder in [content] and return the variable
     * names (without the braces) in first-appearance order, deduplicated.
     * A variable name must match `[a-zA-Z_][a-zA-Z0-9_]*` to avoid matching
     * things like `{{ not-a-var }}` or random `{{...}}` inside code blocks.
     */
    private fun extractVariables(content: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (match in VARIABLE_PATTERN.findAll(content)) {
            seen += match.groupValues[1]
        }
        return seen.toList()
    }

    companion object {
        private val VARIABLE_PATTERN = Regex("""\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}\}""")
    }
}

/**
 * A loaded prompt template. [name] is what the user types after `/` in the
 * editor. [content] is the raw Markdown body (possibly with `{{var}}`
 * placeholders). [variables] is the ordered, deduplicated list of variable
 * names referenced in the body.
 *
 * [expand] performs literal substitution — unknown variables are left as-is
 * so a missing value doesn't silently produce an empty string.
 */
data class PromptTemplate(
    val name: String,
    val content: String,
    val sourcePath: Path,
    val variables: List<String>,
) {
    /**
     * Substitute [values] into [content]. Variables not present in [values]
     * are left verbatim (e.g., `{{focus}}` stays literal) so the user can see
     * something went wrong. Substitution is purely textual; no escaping.
     */
    fun expand(values: Map<String, String>): String {
        var result = content
        for ((name, value) in values) {
            // Match `{{ name }}` with optional whitespace, matching the loader's regex.
            val pattern = Regex("""\{\{\s*${Regex.escape(name)}\s*\}\}""")
            result = pattern.replace(result, Regex.escapeReplacement(value))
        }
        return result
    }
}
