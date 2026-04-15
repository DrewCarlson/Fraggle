package fraggle.agent.skill

import kotlin.io.path.readText

/**
 * Expands user-typed `/skill:name [args]` commands into an inlined skill block that can
 * flow through the normal agent loop.
 *
 * The resulting block carries the full SKILL.md body (frontmatter stripped), wrapped in
 * a `<skill>` XML tag with a preamble explaining the base directory for relative paths.
 * Any trailing arguments after the skill name are preserved as a user request appended
 * after the block.
 *
 * Hidden skills (`disable-model-invocation: true`) are still reachable via explicit
 * `/skill:name` invocation — that is the whole point of the hidden flag: keep them out
 * of the catalog but allow deliberate user invocation.
 *
 * Lives in `fraggle-agent-core` so both the messenger assistant and the future coding
 * agent can drive skill expansion from their own input layers.
 */
class SkillCommandExpander(
    private val registry: SkillRegistry,
) {

    fun tryExpand(text: String): Result {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith(PREFIX)) return Result.NotASkillCommand

        val rest = trimmed.substring(PREFIX.length)
        if (rest.isEmpty()) return Result.MalformedCommand("expected /skill:<name>")

        val spaceIndex = rest.indexOfFirst { it.isWhitespace() }
        val name = if (spaceIndex == -1) rest else rest.substring(0, spaceIndex)
        val args = if (spaceIndex == -1) "" else rest.substring(spaceIndex + 1).trim()

        if (name.isEmpty()) return Result.MalformedCommand("expected /skill:<name>")

        val skill = registry.findByName(name) ?: return Result.UnknownSkill(name)

        val body = try {
            stripFrontmatter(skill.filePath.readText())
        } catch (e: Exception) {
            return Result.ReadError(name, e.message ?: e::class.simpleName.orEmpty())
        }

        val block = buildString {
            append("<skill name=\"").append(skill.name)
                .append("\" location=\"").append(skill.filePath.toString()).append("\">")
            appendLine()
            append("References inside this skill are relative to ").append(skill.baseDir.toString()).append(".")
            appendLine()
            appendLine()
            append(body.trim())
            appendLine()
            append("</skill>")
        }

        val expanded = if (args.isEmpty()) block else "$block\n\n$args"
        return Result.Expanded(skill, expanded)
    }

    private fun stripFrontmatter(content: String): String {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        if (!normalized.startsWith("---")) return normalized
        val endIndex = normalized.indexOf("\n---", startIndex = 3)
        if (endIndex == -1) return normalized
        return normalized.substring(endIndex + 4).trim()
    }

    sealed class Result {
        data object NotASkillCommand : Result()
        data class MalformedCommand(val reason: String) : Result()
        data class UnknownSkill(val name: String) : Result()
        data class ReadError(val name: String, val reason: String) : Result()
        data class Expanded(val skill: Skill, val text: String) : Result()
    }

    companion object {
        const val PREFIX = "/skill:"
    }
}
